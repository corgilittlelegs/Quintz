package com.quintz.wifi.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.quintz.wifi.R
import com.quintz.wifi.core.WifiController
import com.quintz.wifi.data.Preferences
import com.quintz.wifi.model.AdaptiveFallbackInfo
import com.quintz.wifi.model.BandType
import com.quintz.wifi.model.LockResult
import com.quintz.wifi.shizuku.ShizukuManager
import com.quintz.wifi.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque

class WatchdogService : Service() {

    private val serviceJob = kotlinx.coroutines.SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var controller: WifiController
    private lateinit var prefs: Preferences
    private val adaptiveSignalTracker = AdaptiveSignalTracker()

    override fun onCreate() {
        super.onCreate()
        controller = WifiController(this)
        prefs = Preferences(this)
        createNotificationChannel()
        startServiceInForeground()
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
            var lastKnownSsid = ""
            var consecutiveLowSignalSamples = 0
            var consecutiveDisconnectedSamples = 0
            while (isActive) {
                var loopDelay = 15000L
                if (prefs.isWatchdogEnabled && ShizukuManager.isReady()) {
                    try {
                        val status = controller.refreshStatus()
                        if (status.isConnected && status.ssid.isNotEmpty()) {
                            consecutiveDisconnectedSamples = 0
                            lastKnownSsid = status.ssid
                            val savedPassword = prefs.getPassword(status.ssid)

                            if (status.isLockedToBssid && (status.band == BandType.BAND_5_GHZ || status.band == BandType.BAND_6_GHZ)) {
                                // Passive RSSI check: no active radio scan needed when healthy on 5 GHz
                                fallbackScanInterval = 12000L
                                val adaptiveSignal = adaptiveSignalTracker.observe(
                                    bssid = status.bssid,
                                    rssi = status.rssi,
                                    linkSpeedMbps = status.linkSpeedMbps,
                                    configuredThreshold = prefs.fallbackThresholdRssi
                                )
                                prefs.saveAdaptiveFallbackInfo(
                                    AdaptiveFallbackInfo(
                                        bssid = status.bssid,
                                        thresholdDbm = adaptiveSignal.rssiThreshold,
                                        calibrationSamples = adaptiveSignal.calibrationSamples,
                                        isCalibrated = adaptiveSignal.isCalibrated
                                    )
                                )
                                val weakSignal = status.rssi < adaptiveSignal.rssiThreshold && status.rssi > -120
                                val mustFallbackForSafety = status.rssi < HARD_RSSI_FLOOR_DBM
                                val poorLinkQuality = !adaptiveSignal.isCalibrated ||
                                    !adaptiveSignal.hasLinkSpeedBaseline ||
                                    adaptiveSignal.isLinkSpeedDegraded(status.linkSpeedMbps)

                                if (weakSignal && (poorLinkQuality || mustFallbackForSafety)) {
                                    // RSSI can briefly dip during normal roaming. Require sustained
                                    // low signal and poor link quality before tearing down the BSSID-bound connection.
                                    consecutiveLowSignalSamples++
                                    if (consecutiveLowSignalSamples >= LOW_SIGNAL_SAMPLES_BEFORE_FALLBACK) {
                                        updateNotification(
                                            "Low 5 GHz signal (${status.rssi} dBm, floor ${adaptiveSignal.rssiThreshold} dBm). Falling back to Auto..."
                                        )
                                        controller.unlockToAuto(
                                            status.ssid,
                                            savedPassword,
                                            preserveTargetBand = true
                                        )
                                        consecutiveLowSignalSamples = 0
                                        loopDelay = 8000L
                                    } else {
                                        updateNotification(
                                            "Weak 5 GHz signal (${status.rssi} dBm, floor ${adaptiveSignal.rssiThreshold} dBm). Confirming before fallback..."
                                        )
                                    }
                                } else {
                                    consecutiveLowSignalSamples = 0
                                    updateNotification("Locked to 5 GHz • ${status.ssid} (${status.rssi} dBm)")
                                }
                            } else if (prefs.lastTargetBand == "5GHz") {
                                consecutiveLowSignalSamples = 0
                                adaptiveSignalTracker.reset()
                                prefs.clearAdaptiveFallbackInfo()
                                // In fallback mode: scan to check if 5 GHz is strong again
                                loopDelay = fallbackScanInterval
                                val radios = controller.scanRadios()
                                val strong5G = radios.firstOrNull {
                                    it.ssid.trim('"').equals(status.ssid.trim('"'), ignoreCase = true) &&
                                        (it.band == BandType.BAND_5_GHZ || it.band == BandType.BAND_6_GHZ) &&
                                        it.rssi >= prefs.recoveryThresholdRssi
                                }
                                val isOpen = strong5G != null && strong5G.flags.uppercase().let {
                                    !it.contains("PSK") && !it.contains("SAE") && !it.contains("WEP")
                                }
                                if (strong5G != null && (!savedPassword.isNullOrEmpty() || isOpen)) {
                                    updateNotification("Strong 5 GHz found (${strong5G.rssi} dBm). Locking to 5 GHz...")
                                    val lockRes = controller.lockToBssid(status.ssid, strong5G.bssid, savedPassword.orEmpty())
                                    if (lockRes is LockResult.Success) {
                                        fallbackScanInterval = 12000L
                                        updateNotification("Locked to 5 GHz • ${status.ssid} (${strong5G.rssi} dBm)")
                                    } else {
                                        fallbackScanInterval = 16000L
                                    }
                                } else {
                                    updateNotification("Connected (${status.band.displayName}) • Monitoring for 5 GHz")
                                    fallbackScanInterval = (fallbackScanInterval + 4000L).coerceAtMost(30000L)
                                }
                            } else {
                                consecutiveLowSignalSamples = 0
                                adaptiveSignalTracker.reset()
                                prefs.clearAdaptiveFallbackInfo()
                                fallbackScanInterval = 12000L
                                updateNotification("Auto-Roam • ${status.ssid}")
                            }
                        } else {
                            consecutiveLowSignalSamples = 0
                            adaptiveSignalTracker.reset()
                            prefs.clearAdaptiveFallbackInfo()
                            consecutiveDisconnectedSamples++
                            // A shell/status query can transiently report no connection while
                            // Android is still connected. Require a second observation before
                            // reconfiguring the network and causing a visible handoff.
                            if (lastKnownSsid.isNotEmpty() &&
                                prefs.lastTargetBand == "5GHz" &&
                                consecutiveDisconnectedSamples >= DISCONNECTED_SAMPLES_BEFORE_RECOVERY
                            ) {
                                val savedPassword = prefs.getPassword(lastKnownSsid)
                                updateNotification("5 GHz lost. Unlocking to Auto to restore connection...")
                                controller.unlockToAuto(
                                    lastKnownSsid,
                                    savedPassword,
                                    preserveTargetBand = true
                                )
                                consecutiveDisconnectedSamples = 0
                                loopDelay = 6000L
                            } else {
                                updateNotification("Wi-Fi connection lost. Confirming before recovery...")
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("Watchdog", "Watchdog loop error", e)
                    }
                }
                delay(loopDelay)
            }
        }
    }

    override fun onDestroy() {
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
        private const val LOW_SIGNAL_SAMPLES_BEFORE_FALLBACK = 3
        private const val DISCONNECTED_SAMPLES_BEFORE_RECOVERY = 2
        private const val HARD_RSSI_FLOOR_DBM = -90
    }
}

private class AdaptiveSignalTracker {
    private var trackedBssid = ""
    private val healthyRssiSamples = ArrayDeque<Int>()
    private val healthyLinkSpeedSamples = ArrayDeque<Int>()

    fun observe(
        bssid: String,
        rssi: Int,
        linkSpeedMbps: Int,
        configuredThreshold: Int
    ): AdaptiveSignalSnapshot {
        val safeConfiguredThreshold = configuredThreshold.coerceAtLeast(HARD_RSSI_FLOOR_DBM)
        if (bssid.isEmpty() || !bssid.equals(trackedBssid, ignoreCase = true)) {
            reset()
            trackedBssid = bssid
        }

        // Only learn from readings that are already above the normal fallback
        // threshold. This prevents a brief weak-signal period from lowering its
        // own future fallback floor.
        if (rssi in safeConfiguredThreshold..-1) {
            addBounded(healthyRssiSamples, rssi)
            if (linkSpeedMbps > 0) {
                addBounded(healthyLinkSpeedSamples, linkSpeedMbps)
            }
        }

        val isCalibrated = healthyRssiSamples.size >= MIN_CALIBRATION_SAMPLES
        val threshold = if (isCalibrated) {
            // Adapt only toward a lower (less aggressive) fallback point. -90 dBm
            // remains an absolute safety floor regardless of the learned baseline.
            (median(healthyRssiSamples) - RSSI_DROP_FROM_HEALTHY_BASELINE_DB).coerceIn(
                HARD_RSSI_FLOOR_DBM,
                safeConfiguredThreshold
            )
        } else {
            safeConfiguredThreshold
        }

        return AdaptiveSignalSnapshot(
            rssiThreshold = threshold,
            calibrationSamples = healthyRssiSamples.size,
            isCalibrated = isCalibrated,
            healthyLinkSpeedMbps = if (healthyLinkSpeedSamples.size >= MIN_CALIBRATION_SAMPLES) {
                median(healthyLinkSpeedSamples)
            } else {
                null
            }
        )
    }

    fun reset() {
        trackedBssid = ""
        healthyRssiSamples.clear()
        healthyLinkSpeedSamples.clear()
    }

    private fun addBounded(samples: ArrayDeque<Int>, value: Int) {
        if (samples.size == MAX_CALIBRATION_SAMPLES) samples.removeFirst()
        samples.addLast(value)
    }

    private fun median(samples: Collection<Int>): Int {
        val sorted = samples.sorted()
        return sorted[sorted.size / 2]
    }

    private companion object {
        const val HARD_RSSI_FLOOR_DBM = -90
        const val MIN_CALIBRATION_SAMPLES = 6
        const val MAX_CALIBRATION_SAMPLES = 8
        const val RSSI_DROP_FROM_HEALTHY_BASELINE_DB = 20
    }
}

private data class AdaptiveSignalSnapshot(
    val rssiThreshold: Int,
    val calibrationSamples: Int,
    val isCalibrated: Boolean,
    val healthyLinkSpeedMbps: Int?
) {
    val hasLinkSpeedBaseline: Boolean get() = healthyLinkSpeedMbps != null

    fun isLinkSpeedDegraded(currentLinkSpeedMbps: Int): Boolean {
        val baseline = healthyLinkSpeedMbps ?: return false
        return currentLinkSpeedMbps > 0 && currentLinkSpeedMbps * 100 <= baseline * 35
    }
}
