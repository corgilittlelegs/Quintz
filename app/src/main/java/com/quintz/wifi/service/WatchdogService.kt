package com.quintz.wifi.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.quintz.wifi.R
import com.quintz.wifi.core.DiagnosticLogger
import com.quintz.wifi.core.WifiController
import com.quintz.wifi.data.Preferences
import com.quintz.wifi.data.WifiTargetMode
import com.quintz.wifi.model.BandType
import com.quintz.wifi.model.WifiStatus
import com.quintz.wifi.shizuku.ShizukuManager
import com.quintz.wifi.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WatchdogService : Service() {

    private val serviceJob = kotlinx.coroutines.SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var controller: WifiController
    private lateinit var prefs: Preferences
    private var connectivityManager: ConnectivityManager? = null

    private val transitionMutex = Mutex()
    private val isHandlingDisconnect = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastDisconnectHandledTimeMs: Long = 0L

    private data class CandidateObservation(
        var observations: Int,
        var lastConfirmedAtMs: Long
    )

    private val candidateObservations = mutableMapOf<String, CandidateObservation>()
    private var last5GSwitchAttemptTimeMs: Long = 0L
    private var switchCooldownMs: Long = 60000L

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            if (!prefs.isWatchdogEnabled) return
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm?.isWifiEnabled != true) return // User toggled Wi-Fi off intentionally

            if (prefs.watchdogLastSsid?.let { prefs.getOrMigrateWifiTargetMode(it) } != WifiTargetMode.AUTO) {
                scope.launch {
                    handlePotentialDisconnect("networkCallback.onLost")
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (!prefs.isWatchdogEnabled) return
            if (!networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return

            val wifiInfo = networkCapabilities.transportInfo as? WifiInfo ?: return
            val is24Ghz = wifiInfo.frequency in 2400..2500

            // If the Qualcomm driver gracefully roamed to 2.4 GHz while 5GHz is targeted, update status immediately
            val cleanSsid = wifiInfo.ssid?.replace("\"", "").orEmpty()
            val effectiveSsid = if (cleanSsid.isNotEmpty() && !cleanSsid.equals("<unknown ssid>", ignoreCase = true)) cleanSsid else prefs.watchdogLastSsid.orEmpty()
            if (is24Ghz && effectiveSsid.isNotEmpty() && prefs.getOrMigrateWifiTargetMode(effectiveSsid) == WifiTargetMode.PREFER_5_GHZ) {
                updateNotification("Graceful 2.4 GHz fallback • ${effectiveSsid.ifEmpty { "Archer" }}")
            }
        }
    }

    private suspend fun handlePotentialDisconnect(source: String) {
        if (!isHandlingDisconnect.compareAndSet(false, true)) {
            DiagnosticLogger.log("WATCHDOG", "Disconnect handling already active; dropping redundant request from $source.")
            return
        }
        try {
            val now = System.currentTimeMillis()
            if (now - lastDisconnectHandledTimeMs < 8000L) {
                DiagnosticLogger.log("WATCHDOG", "Disconnect recently handled (${now - lastDisconnectHandledTimeMs}ms ago); skipping $source.")
                return
            }
            lastDisconnectHandledTimeMs = now

            transitionMutex.withLock {
                // Android can take several seconds to run its own reconnect attempts after a
                // failed handshake. In observed failures it retried for about 18 seconds; a
                // 4-second app fallback can race that work and start a competing connect.
                // Wait through a 20-second recovery window before issuing an app reconnect.
                for (observation in 0 until 20) {
                    val status = controller.refreshStatus()
                    if (status.isConnected) {
                        if (status.ssid.isNotEmpty()) {
                            handleConnectedState(status, "handover check ${observation + 1}/20 ($source)")
                        } else {
                            DiagnosticLogger.log(
                                "WATCHDOG",
                                "Wi-Fi reports connected but SSID is not resolved during handover ($source); deferring fallback."
                            )
                        }
                        return@withLock
                    }

                    if (observation < 19) delay(1000L)
                }

                // Still disconnected across 20 observations over 19 seconds; Android had time
                // to complete its normal reconnect retries before the app intervenes.
                val targetSsid = prefs.watchdogLastSsid.orEmpty()
                DiagnosticLogger.log("WATCHDOG", "Confirmed disconnect across 20 observations over 19s ($source) for target '$targetSsid'.")
                prefs.isWatchdogFallbackActive = true
                updateNotification("Wi-Fi disconnected • Monitoring...")

                if (targetSsid.isNotEmpty()) {
                    val targetMode = prefs.getOrMigrateWifiTargetMode(targetSsid)
                    val savedPassword = prefs.getPassword(targetSsid)
                    val correlationId = DiagnosticLogger.newCorrelationId()
                    if (targetMode == WifiTargetMode.PIN_BSSID) {
                        val pinnedBssid = prefs.getPinnedBssid(targetSsid)
                        val radios = controller.scanRadios(correlationId, freshForSsid = targetSsid, freshForBssid = pinnedBssid)
                        val target = radios.firstOrNull {
                            it.bssid.equals(pinnedBssid, ignoreCase = true) && it.ssid.equals(targetSsid, ignoreCase = true) && it.ageSeconds <= 8L
                        }
                        val isOpen = target?.flags?.uppercase()?.let { !it.contains("PSK") && !it.contains("SAE") && !it.contains("WEP") } == true
                        if (target != null && target.rssi >= prefs.recoveryThresholdRssi && (savedPassword != null || isOpen)) {
                            DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId source=watchdog_disconnect_fallback action=restore_bssid_pin targetSsid='$targetSsid' targetBssid=${target.bssid}")
                            controller.lockToBssid(targetSsid, target.bssid, savedPassword.orEmpty(), requestSource = "watchdog_disconnect_restore_pin", correlationId = correlationId)
                        } else {
                            DiagnosticLogger.log("WATCHDOG", "disconnect recovery skipped reason=pinned_target_unavailable targetSsid='$targetSsid' pinnedBssid=${pinnedBssid.orEmpty()}")
                        }
                    } else if (targetMode == WifiTargetMode.PREFER_5_GHZ) {
                        DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId source=watchdog_disconnect_fallback action=unlock_to_auto targetSsid='$targetSsid'")
                        controller.unlockToAuto(targetSsid, savedPassword, isAutomatedFallback = true, requestSource = "watchdog_disconnect_fallback", correlationId = correlationId)
                    }
                }
            }
        } finally {
            isHandlingDisconnect.set(false)
        }
    }

    private fun handleConnectedState(status: WifiStatus, contextDesc: String) {
        val targetSsid = prefs.watchdogLastSsid.orEmpty()
        DiagnosticLogger.log(
            "WATCHDOG",
            "Device connected ($contextDesc): SSID='${status.ssid}', band=${status.band.displayName}, rssi=${status.rssi} dBm. Link preserved."
        )
        if (targetSsid.isEmpty() || status.ssid.equals(targetSsid, ignoreCase = true)) {
            val mode = prefs.getOrMigrateWifiTargetMode(status.ssid, status.lockedBssid.takeIf { status.isLockedToBssid })
            if (status.band == BandType.BAND_2_4_GHZ && mode == WifiTargetMode.PREFER_5_GHZ) {
                prefs.isWatchdogFallbackActive = true
                updateNotification("Graceful 2.4 GHz fallback • ${status.ssid}")
            } else {
                prefs.isWatchdogFallbackActive = false
                val label = when (mode) {
                    WifiTargetMode.PIN_BSSID -> "Pinned to BSSID"
                    WifiTargetMode.PREFER_5_GHZ -> "Preferred 5 GHz (Roam Allowed)"
                    WifiTargetMode.AUTO -> "Auto-Roam"
                }
                updateNotification("$label • ${status.ssid} (${status.rssi} dBm)")
            }
        } else {
            // Connected to a DIFFERENT SSID! (e.g. user selected another network)
            // NEVER disrupt an active connection to another network!
            DiagnosticLogger.log(
                "WATCHDOG",
                "Connected to different SSID '${status.ssid}' (previous target '$targetSsid'). Preserving active connection."
            )
            prefs.watchdogLastSsid = status.ssid
            prefs.isWatchdogFallbackActive = false
            updateNotification("Connected • ${status.ssid}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        controller = WifiController(this)
        prefs = Preferences(this)
        createNotificationChannel()
        startServiceInForeground()
        
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            android.util.Log.e("Watchdog", "Failed to register NetworkCallback", e)
        }

        startWatchdogLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startServiceInForeground()
        return START_STICKY
    }

    private fun startServiceInForeground() {
        val notification = buildNotification("Monitoring Wi-Fi band state...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startWatchdogLoop() {
        scope.launch {
            var fallbackScanInterval = 12000L
            var lastKnownSsid = prefs.watchdogLastSsid.orEmpty()
            while (isActive) {
                var loopDelay = 12000L
                if (prefs.isWatchdogEnabled) {
                    if (!ShizukuManager.isReady()) {
                        DiagnosticLogger.log("WATCHDOG", "5 GHz recovery skipped reason=shizuku_not_ready")
                        updateNotification("Waiting for Shizuku permission...")
                        delay(10000L)
                        continue
                    }

                    try {
                        val status = controller.refreshStatus()
                        if (status.isConnected && status.ssid.isNotEmpty()) {
                            val targetMode = prefs.getOrMigrateWifiTargetMode(
                                status.ssid,
                                status.lockedBssid.takeIf { status.isLockedToBssid }
                            )
                            lastKnownSsid = status.ssid
                            prefs.watchdogLastSsid = status.ssid
                            val savedPassword = prefs.getPassword(status.ssid)
                            val pinnedBssid = prefs.getPinnedBssid(status.ssid)
                            val onDesiredTarget = when (targetMode) {
                                WifiTargetMode.AUTO -> true
                                WifiTargetMode.PREFER_5_GHZ -> status.band == BandType.BAND_5_GHZ || status.band == BandType.BAND_6_GHZ
                                WifiTargetMode.PIN_BSSID -> status.isLockedToBssid && status.bssid.equals(pinnedBssid, ignoreCase = true)
                            }

                            if (onDesiredTarget) {
                                // The saved state already matches this network's requested behavior.
                                fallbackScanInterval = 12000L
                                prefs.isWatchdogFallbackActive = false
                                candidateObservations.clear()
                                switchCooldownMs = 60000L
                                val label = when (targetMode) {
                                    WifiTargetMode.PIN_BSSID -> "Pinned to BSSID"
                                    WifiTargetMode.PREFER_5_GHZ -> "Preferred 5 GHz (Roam Allowed)"
                                    WifiTargetMode.AUTO -> "Auto-Roam"
                                }
                                updateNotification("$label • ${status.ssid} (${status.rssi} dBm)")
                            } else if (targetMode == WifiTargetMode.PREFER_5_GHZ || targetMode == WifiTargetMode.PIN_BSSID) {
                                // Restore the explicit per-network target after a fallback or unexpected roam.
                                loopDelay = fallbackScanInterval
                                val now = System.currentTimeMillis()
                                val timeSinceLastAttempt = now - last5GSwitchAttemptTimeMs

                                if (timeSinceLastAttempt < switchCooldownMs) {
                                    val remainingSec = ((switchCooldownMs - timeSinceLastAttempt) / 1000L).coerceAtLeast(1L)
                                    val modeDesc = if (prefs.isWatchdogFallbackActive) "Graceful 2.4 GHz fallback" else "Connected (2.4 GHz)"
                                    DiagnosticLogger.log(
                                        "WATCHDOG",
                                        "5 GHz recovery deferred reason=cooldown remainingSec=$remainingSec cooldownMs=$switchCooldownMs ssid='${status.ssid}' currentBssid=${status.bssid} currentRssi=${status.rssi}"
                                    )
                                    updateNotification("$modeDesc (${status.ssid}) • Cooldown ${remainingSec}s")
                                } else {
                                    transitionMutex.withLock {
                                        val radios = controller.scanRadios(
                                            freshForSsid = status.ssid,
                                            freshForBssid = pinnedBssid.takeIf { targetMode == WifiTargetMode.PIN_BSSID }
                                        )
                                        val threshold = prefs.recoveryThresholdRssi
                                        val sameNetworkRadios = radios.filter {
                                            it.ssid.equals(status.ssid, ignoreCase = true) && when (targetMode) {
                                                WifiTargetMode.PIN_BSSID -> it.bssid.equals(pinnedBssid, ignoreCase = true)
                                                else -> it.band == BandType.BAND_5_GHZ || it.band == BandType.BAND_6_GHZ
                                            }
                                        }
                                        val eligibleRadios = sameNetworkRadios.filter {
                                            it.rssi >= threshold && it.ageSeconds <= 8L
                                        }
                                        val eligibleBssids = eligibleRadios.map { it.bssid.lowercase() }.toSet()
                                        val lostBssids = candidateObservations.keys.filter { it !in eligibleBssids }
                                        lostBssids.forEach { candidateObservations.remove(it) }

                                        sameNetworkRadios.forEach { radio ->
                                            val rejectionReason = when {
                                                radio.rssi < threshold -> "below_rssi_threshold"
                                                radio.ageSeconds > 8L -> "scan_result_stale"
                                                else -> null
                                            }
                                            if (rejectionReason != null) {
                                                DiagnosticLogger.log(
                                                    "WATCHDOG",
                                                    "Target candidate rejected reason=$rejectionReason mode=${targetMode.name} bssid=${radio.bssid} rssi=${radio.rssi}dBm threshold=${threshold}dBm age=${radio.ageSeconds}s ssid='${radio.ssid}'"
                                                )
                                            }
                                        }

                                        val observationTimeMs = System.currentTimeMillis()
                                        eligibleRadios.forEach { radio ->
                                            val key = radio.bssid.lowercase()
                                            val observation = candidateObservations[key]
                                            val elapsedSincePriorMs = observation?.let {
                                                observationTimeMs - it.lastConfirmedAtMs
                                            }
                                            if (observation == null) {
                                                candidateObservations[key] = CandidateObservation(1, observationTimeMs)
                                            } else if (radio.ageSeconds <= 6L && elapsedSincePriorMs != null && elapsedSincePriorMs >= 10000L) {
                                                observation.observations++
                                                observation.lastConfirmedAtMs = observationTimeMs
                                            }
                                            val currentObservation = candidateObservations.getValue(key)
                                            DiagnosticLogger.log(
                                                "WATCHDOG",
                                                "Target candidate mode=${targetMode.name} bssid=${radio.bssid} observation=${currentObservation.observations}/2 rssi=${radio.rssi}dBm age=${radio.ageSeconds}s elapsedSincePrior=${elapsedSincePriorMs ?: -1L}ms"
                                            )
                                        }

                                        val verified5G = eligibleRadios
                                            .filter { (candidateObservations[it.bssid.lowercase()]?.observations ?: 0) >= 2 }
                                            .maxByOrNull { it.rssi }

                                        if (verified5G != null) {
                                            val isOpen = verified5G.flags.uppercase().let {
                                                !it.contains("PSK") && !it.contains("SAE") && !it.contains("WEP")
                                            }
                                            if (savedPassword.isNullOrEmpty() && !isOpen) {
                                                DiagnosticLogger.log(
                                                    "WATCHDOG",
                                                    "5 GHz recovery deferred reason=saved_password_missing ssid='${status.ssid}' bssid=${verified5G.bssid} rssi=${verified5G.rssi}dBm"
                                                )
                                                updateNotification("Target AP is strong • Save network password to recover")
                                            } else {
                                                DiagnosticLogger.log(
                                                    "WATCHDOG",
                                                    "5 GHz candidate verified across repeated distinct scans. Returning to preferred band: ${verified5G.bssid} (${verified5G.rssi} dBm), current link ${status.band.displayName} at ${status.rssi} dBm."
                                                )
                                                updateNotification("Returning to verified target AP (${verified5G.rssi} dBm)...")
                                                last5GSwitchAttemptTimeMs = System.currentTimeMillis()
                                                val correlationId = DiagnosticLogger.newCorrelationId()
                                                DiagnosticLogger.log(
                                                    "WIFI_ACTION",
                                                    "id=$correlationId source=watchdog_5ghz_recovery action=lock_to_bssid ssid='${status.ssid}' targetBssid=${verified5G.bssid} candidateRssi=${verified5G.rssi} currentBssid=${status.bssid} currentBand=${status.band.displayName} currentRssi=${status.rssi}"
                                                )

                                                val switched = controller.lockToBssid(
                                                    status.ssid,
                                                    verified5G.bssid,
                                                    savedPassword.orEmpty(),
                                                    unpinProfileForRoaming = targetMode == WifiTargetMode.PREFER_5_GHZ,
                                                    deferIfHealthy24Ghz = true,
                                                    allowSwitchFromHealthy24Ghz = true,
                                                    requestSource = "watchdog_5ghz_recovery",
                                                    correlationId = correlationId
                                                )
                                                if (switched) {
                                                    prefs.isWatchdogFallbackActive = false
                                                    fallbackScanInterval = 12000L
                                                    candidateObservations.clear()
                                                    switchCooldownMs = 60000L
                                                    val postStatus = controller.status.value
                                                    val label = if (postStatus.isLockedToBssid) "Locked to BSSID" else "Preferred 5 GHz (Roam Allowed)"
                                                    updateNotification("$label • ${postStatus.ssid} (${postStatus.rssi} dBm)")
                                                } else {
                                                    // A failed attempt still backs off to avoid repeated association churn.
                                                    switchCooldownMs = (switchCooldownMs + 30000L).coerceAtMost(300000L)
                                                    candidateObservations.clear()
                                                    fallbackScanInterval = (fallbackScanInterval + 4000L).coerceAtMost(30000L)
                                                    DiagnosticLogger.log(
                                                        "WATCHDOG",
                                                        "5 GHz recovery attempt failed. Backing off retry cooldown to ${switchCooldownMs / 1000L}s."
                                                    )
                                                    updateNotification("5 GHz recovery failed • Retrying later")
                                                }
                                            }
                                        } else {
                                            if (eligibleRadios.isEmpty()) {
                                                candidateObservations.clear()
                                                val reason = when {
                                                    radios.isEmpty() -> "scan_empty_or_failed"
                                                    sameNetworkRadios.isEmpty() -> "no_same_ssid_5ghz_candidate"
                                                    else -> "no_candidate_passed_rssi_and_freshness"
                                                }
                                                DiagnosticLogger.log(
                                                    "WATCHDOG",
                                                    "5 GHz recovery skipped reason=$reason ssid='${status.ssid}' threshold=${threshold}dBm sameNetworkCandidates=${sameNetworkRadios.size} scannedRadios=${radios.size} currentBand=${status.band.displayName} currentRssi=${status.rssi}"
                                                )
                                                val modeDesc = if (prefs.isWatchdogFallbackActive) "Graceful 2.4 GHz fallback" else "Monitoring for 5 GHz"
                                                updateNotification("$modeDesc (${status.band.displayName}) • ${status.ssid}")
                                                fallbackScanInterval = (fallbackScanInterval + 4000L).coerceAtMost(30000L)
                                            } else {
                                                val bestObserved = eligibleRadios.maxByOrNull { it.rssi }!!
                                                val observationCount = candidateObservations[bestObserved.bssid.lowercase()]?.observations ?: 1
                                                if (savedPassword.isNullOrEmpty() && bestObserved.flags.uppercase().let {
                                                        it.contains("PSK") || it.contains("SAE") || it.contains("WEP")
                                                    }
                                                ) {
                                                    DiagnosticLogger.log(
                                                        "WATCHDOG",
                                                        "5 GHz recovery deferred reason=saved_password_missing ssid='${status.ssid}' bssid=${bestObserved.bssid} rssi=${bestObserved.rssi}dBm observation=$observationCount/2"
                                                    )
                                                }
                                                DiagnosticLogger.log(
                                                    "WATCHDOG",
                                                    "5 GHz recovery waiting reason=second_observation ssid='${status.ssid}' bestBssid=${bestObserved.bssid} rssi=${bestObserved.rssi}dBm age=${bestObserved.ageSeconds}s observation=$observationCount/2 threshold=${threshold}dBm"
                                                )
                                                    updateNotification("Observing target AP (${bestObserved.rssi} dBm, $observationCount/2) • ${status.ssid}")
                                            }
                                        }
                                    }
                                }
                            } else {
                                fallbackScanInterval = 12000L
                                prefs.isWatchdogFallbackActive = false
                                candidateObservations.clear()
                                DiagnosticLogger.log(
                                    "WATCHDOG",
                                    "5 GHz recovery skipped reason=target_band_auto ssid='${status.ssid}' currentBand=${status.band.displayName} currentRssi=${status.rssi}"
                                )
                                updateNotification("Auto-Roam • ${status.ssid}")
                            }
                        } else {
                            // Connection lost / disconnected: debounce before any command
                            handlePotentialDisconnect("watchdogLoop")
                            loopDelay = 6000L
                        }
                    } catch (e: Exception) {
                        DiagnosticLogger.log("WATCHDOG", "Error in watchdog loop: ${e.message}")
                        android.util.Log.e("Watchdog", "Watchdog loop error", e)
                    }
                }
                delay(loopDelay)
            }
        }
    }

    override fun onDestroy() {
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Quintz Watchdog",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors 5 GHz Wi-Fi signal and manages seamless fallbacks"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Quintz Watchdog Active")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_qs_tile)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "watchdog_channel"
        private const val NOTIFICATION_ID = 4001
    }
}
