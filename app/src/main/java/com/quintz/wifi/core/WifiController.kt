package com.quintz.wifi.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.quintz.wifi.data.Preferences
import com.quintz.wifi.data.WifiTargetMode
import com.quintz.wifi.model.AccessPointRadio
import com.quintz.wifi.model.BandType
import com.quintz.wifi.model.MacAddressPolicy
import com.quintz.wifi.model.WifiOperationKind
import com.quintz.wifi.model.WifiStatus
import com.quintz.wifi.shizuku.ShellResult
import com.quintz.wifi.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.*
import com.quintz.wifi.core.profile.*
import android.os.Bundle

private class WifiContextChanged : Exception()

class WifiController(private val context: Context,
    private val prefs: Preferences = Preferences.get(context),
    private val clock: MonotonicClock = AndroidMonotonicClock,
    private val profiles: ProfileGateway = ProfileAccess) {
    @Volatile var lastTransitionResult: TransitionResult = TransitionResult.Failed
        private set
    @Volatile var lastRecoveryFailure: RecoveryFailure = RecoveryFailure.RESTORE_FAILED
        private set
    val actionFailureMessage: String get() = when (lastTransitionResult) {
        TransitionResult.Busy -> "Another Wi-Fi change is in progress. Try again when it finishes."
        TransitionResult.Unsupported -> "Android could not expose and verify the complete saved profile. No profile was deleted."
        TransitionResult.RecoveryPending -> lastRecoveryFailure.message
        TransitionResult.StorageFailed -> "Connection settings could not be saved securely. The original profile is being restored."
        else -> "The requested connection was not verified. Check the password and try again."
    }
    val macPolicyFailureMessage: String get() = when (lastTransitionResult) {
        TransitionResult.Busy -> actionFailureMessage
        TransitionResult.Unsupported -> "Android could not expose the saved profile or verify its MAC identity. The MAC policy was not changed."
        TransitionResult.RecoveryPending -> lastRecoveryFailure.message
        TransitionResult.StorageFailed -> "The MAC preference could not be saved. Restoring the previous settings."
        else -> "The MAC change was not verified. The previous settings were restored, or recovery remains pending."
    }
    @Volatile var lastPasswordStorageFailure: Boolean = false
        private set

    private val _status = MutableStateFlow(WifiStatus())
    val status: StateFlow<WifiStatus> = _status.asStateFlow()

    private val _radios = MutableStateFlow<List<AccessPointRadio>>(emptyList())
    val radios: StateFlow<List<AccessPointRadio>> = _radios.asStateFlow()
    private val _scanState = MutableStateFlow(ScanState())
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    val isOperating: StateFlow<Boolean> = WifiOperationCoordinator.isOperating
    val currentOperation: StateFlow<WifiOperationKind> = WifiOperationCoordinator.currentOperation

    val isScanning: StateFlow<Boolean> = WifiScanCoordinator.isScanning
    private val _isScanQueued = MutableStateFlow(false)
    val isScanQueued: StateFlow<Boolean> = _isScanQueued.asStateFlow()
    private val scanQueueGuard = Any()
    private var queuedScanCount = 0

    private fun changeQueuedScans(delta: Int) = synchronized(scanQueueGuard) {
        queuedScanCount += delta
        _isScanQueued.value = queuedScanCount > 0
    }

    fun invalidateRadios() {
        _radios.value = emptyList(); lastScanSucceeded = false
        _scanState.value = ScanState(ScanPhase.ACCESS_UNAVAILABLE)
    }

    fun publishConnectedObservation(observation: WifiStatus) {
        val previous = _status.value
        if (observation.isConnected && observation.ssid == previous.ssid && observation.bssid.equals(previous.bssid, true) && (observation.networkId == null || previous.networkId == observation.networkId))
            _status.value = observation.copy(isLockedToBssid = previous.isLockedToBssid, lockedBssid = previous.lockedBssid,
                requestedPinnedBssid = previous.requestedPinnedBssid, isPreferenceRequested = previous.isPreferenceRequested, profileInspectionKnown = previous.profileInspectionKnown,
                configuredMacPolicy = previous.configuredMacPolicy, securityType = previous.securityType,
                isMacPolicyPending = prefs.isMacPolicyPending(previous.ssid, previous.configuredMacPolicy, observation.observedMacAddress),
                profileObservedAtMillis = previous.profileObservedAtMillis,
                identityObservedAtMillis = if (observation.nativeIdentityLimited) previous.identityObservedAtMillis else observation.observedAtMillis, isPreferred5GHz = previous.isPreferred5GHz, isPreferred5GHzFallback = previous.isPreferred5GHzFallback)
    }

    fun getNativeWifiStatus(): WifiStatus {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return WifiStatus()
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val wmInfo = wm?.connectionInfo
            // A cellular default or VPN must not supply the Wi-Fi link's IP configuration.
            val candidates = cm.allNetworks.mapNotNull { network ->
                cm.getNetworkCapabilities(network)?.takeIf {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }?.let { network to it }
            }
            val selected = selectWifiNetwork(candidates) { (_, candidateCaps) ->
                val info = candidateCaps.transportInfo as? WifiInfo
                info != null && wmInfo?.networkId?.takeIf { it >= 0 } == info.networkId &&
                    !info.bssid.isNullOrEmpty() && info.bssid != "02:00:00:00:00:00" && info.bssid.equals(wmInfo.bssid, true)
            } ?: return WifiStatus()
            val (wifiNetwork, caps) = selected
            val addresses = cm.getLinkProperties(wifiNetwork)?.linkAddresses.orEmpty()
                .map { it.address }.filter(::isUsableWifiAddress)
                .sortedBy { it !is java.net.Inet4Address }.mapNotNull { it.hostAddress }
            val capsWifiInfo = caps.transportInfo as? WifiInfo
            // WifiManager can retain permitted identity fields which transportInfo redacts.
            val wifiInfo = wmInfo?.takeIf {
                !it.bssid.isNullOrEmpty() && it.bssid != "02:00:00:00:00:00"
            } ?: capsWifiInfo ?: wmInfo

            val freq = wifiInfo?.frequency ?: 0
            val speed = wifiInfo?.linkSpeed ?: 0
            val rawSsid = wifiInfo?.ssid.orEmpty().removeSurrounding("\"")
            val ssid = if (rawSsid == "<unknown ssid>" || rawSsid == "<none>") "" else rawSsid
            val rawBssid = wifiInfo?.bssid.orEmpty()
            val bssid = if (rawBssid == "02:00:00:00:00:00") "" else rawBssid

            val signalDbm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && caps.signalStrength != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) {
                caps.signalStrength
            } else {
                val r = wifiInfo?.rssi ?: 0
                if (r != -127 && r != 0) r else 0
            }

            val standard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wifiInfo != null) {
                when (wifiInfo.wifiStandard) {
                    ScanResult.WIFI_STANDARD_11AX -> "11ax"
                    ScanResult.WIFI_STANDARD_11AC -> "11ac"
                    ScanResult.WIFI_STANDARD_11N -> "11n"
                    ScanResult.WIFI_STANDARD_LEGACY -> "legacy"
                    8 -> "11be" // ScanResult.WIFI_STANDARD_11BE (API 33+)
                    else -> "Wi-Fi"
                }
            } else {
                "Wi-Fi"
            }

            return WifiStatus(
                isConnected = true,
                ssid = ssid,
                bssid = bssid,
                frequency = freq,
                band = BandType.fromFrequency(freq),
                rssi = signalDbm,
                linkSpeedMbps = speed,
                standard = standard,
                ipAddress = addresses.firstOrNull().orEmpty(),
                ipAddresses = addresses,
                internetValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                networkId = wifiInfo?.networkId?.takeIf { it >= 0 },
                observedMacAddress = wifiInfo?.macAddress?.takeIf { ssid.isNotEmpty() && bssid.isNotEmpty() && it != "02:00:00:00:00:00" && it != "00:00:00:00:00:00" },
                nativeIdentityLimited = ssid.isEmpty() || bssid.isEmpty()
            )
        } catch (_: Exception) {
            return WifiStatus()
        }
    }

    suspend fun refreshStatus(forceFresh: Boolean = false): WifiStatus = withContext(Dispatchers.IO) {
        val (status, coalesced) = WifiStatusCoordinator.refresh(forceFresh) { refreshStatusDirect(forceFresh) }
        _status.value = status
        if (coalesced) {
            DiagnosticLogger.log("WIFI_STATUS", "result=coalesced ageMs=${(clock.nowMillis() - status.observedAtMillis).coerceAtLeast(0L)}")
        }
        status
    }

    private suspend fun refreshStatusDirect(forceFresh: Boolean): WifiStatus = withContext(Dispatchers.IO) {
        val nativeStatus = getNativeWifiStatus()

        if (!ShizukuManager.isReady()) {
            val mode = prefs.getWifiTargetMode(nativeStatus.ssid)
            val observed = nativeStatus.copy(observedAtMillis = clock.nowMillis(),
                isPreferenceRequested = mode == WifiTargetMode.PREFER_5_GHZ,
                requestedPinnedBssid = prefs.getPinnedBssid(nativeStatus.ssid).takeIf { mode == WifiTargetMode.PIN_BSSID })
            _status.value = observed
            return@withContext observed
        }

        // 1. Primary shell query
        val statusResult = ShizukuManager.exec("cmd wifi status")
        var status = WifiParser.parseStatus(statusResult.stdout)
        val structured = profiles.call(context, "status", Bundle())
        @Suppress("DEPRECATION") val info = structured?.getParcelable<WifiInfo>("info")
        if (info != null && info.supplicantState == android.net.wifi.SupplicantState.COMPLETED &&
            info.ssid !in setOf("<unknown ssid>", "<none>", "") && !info.bssid.isNullOrEmpty() && info.bssid != "02:00:00:00:00:00") {
            @Suppress("DEPRECATION") val ipv4 = info.ipAddress.takeIf { it != 0 }?.let { value -> (0..3).joinToString(".") { ((value ushr (it * 8)) and 255).toString() } }.orEmpty()
            status = status.copy(isConnected = true, ssid = info.ssid.removeSurrounding("\""), bssid = info.bssid.orEmpty(),
                networkId = info.networkId.takeIf { it >= 0 }, frequency = info.frequency, band = BandType.fromFrequency(info.frequency),
                rssi = info.rssi.takeIf { it in -126..-1 } ?: 0, linkSpeedMbps = info.linkSpeed, ipAddress = ipv4,
                securityType = if (Build.VERSION.SDK_INT >= 31) info.currentSecurityType.toString() else status.securityType,
                observedMacAddress = info.macAddress?.takeIf { it != "02:00:00:00:00:00" && it != "00:00:00:00:00:00" })
        }

        // 2. Fallback: If cmd wifi status reports disconnected, query dumpsys wifi mWifiInfo
        if (!status.isConnected) {
            val fallback = ShizukuManager.exec("dumpsys wifi 2>/dev/null | grep -m 1 -A 2 'mWifiInfo SSID:'")
            if (fallback.stdout.contains("Supplicant state: COMPLETED", ignoreCase = true)) {
                status = WifiParser.parseStatus(fallback.stdout)
            }
        }

        // 3. Fallback: Cross-reference with Android OS native ConnectivityManager ground truth
        if (!status.isConnected && nativeStatus.isConnected) {
            var resolvedSsid = nativeStatus.ssid
            var resolvedBssid = nativeStatus.bssid
            if (resolvedSsid.isEmpty() || resolvedBssid.isEmpty()) {
                val dump = ShizukuManager.exec("dumpsys wifi 2>/dev/null | grep -m 1 'mWifiInfo SSID:'")
                val (dumpSsid, dumpBssid) = WifiParser.parseConnectionIdentity(dump.stdout)
                if (resolvedSsid.isEmpty()) resolvedSsid = dumpSsid
                if (resolvedBssid.isEmpty()) resolvedBssid = dumpBssid
            }

            status = nativeStatus.copy(
                ssid = resolvedSsid,
                bssid = resolvedBssid,
                frequency = if (nativeStatus.frequency != 0) nativeStatus.frequency else status.frequency,
                band = if (nativeStatus.frequency != 0) BandType.fromFrequency(nativeStatus.frequency) else status.band,
                rssi = if (nativeStatus.rssi != 0 && nativeStatus.rssi != -127) nativeStatus.rssi else status.rssi,
                linkSpeedMbps = if (nativeStatus.linkSpeedMbps > 0) nativeStatus.linkSpeedMbps else status.linkSpeedMbps,
                ipAddress = if (nativeStatus.ipAddress.isNotEmpty()) nativeStatus.ipAddress else status.ipAddress,
                nativeIdentityLimited = resolvedSsid.isEmpty() || resolvedBssid.isEmpty()
            )
        } else if (status.isConnected) {
            var resolvedSsid = status.ssid.ifEmpty { nativeStatus.ssid }
            var resolvedBssid = status.bssid.ifEmpty { nativeStatus.bssid }
            if (resolvedSsid.isEmpty() || resolvedBssid.isEmpty()) {
                val dump = ShizukuManager.exec("dumpsys wifi 2>/dev/null | grep -m 1 'mWifiInfo SSID:'")
                val (dumpSsid, dumpBssid) = WifiParser.parseConnectionIdentity(dump.stdout)
                if (resolvedSsid.isEmpty()) resolvedSsid = dumpSsid
                if (resolvedBssid.isEmpty()) resolvedBssid = dumpBssid
            }
            status = status.copy(
                ssid = resolvedSsid,
                bssid = resolvedBssid,
                nativeIdentityLimited = resolvedSsid.isEmpty() || resolvedBssid.isEmpty()
            )
            if (status.ipAddress.isEmpty() && nativeStatus.ipAddress.isNotEmpty() && status.ssid == nativeStatus.ssid && status.bssid.equals(nativeStatus.bssid, true)) {
                status = status.copy(ipAddress = nativeStatus.ipAddress, ipAddresses = nativeStatus.ipAddresses, internetValidated = nativeStatus.internetValidated)
            }
            if (status.frequency == 0 && nativeStatus.frequency != 0) {
                status = status.copy(frequency = nativeStatus.frequency, band = BandType.fromFrequency(nativeStatus.frequency))
            }
            if ((status.rssi == 0 || status.rssi == -127) && nativeStatus.rssi != 0) {
                status = status.copy(rssi = nativeStatus.rssi)
            }
        }

        // Exact saved identity, with unknown distinct from a proven unpinned profile.
        val finalStatus = if (status.isConnected && status.ssid.isNotEmpty()) {
            val inspection = status.networkId?.let { profile(status.ssid, status.securityType, it) }
                ?.takeUnless { it.getBoolean("absent") }
            val pin = inspection?.getString("pin")?.takeIf { it.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) }
            val mode = prefs.getWifiTargetMode(status.ssid) ?: if (inspection != null)
                prefs.getOrMigrateWifiTargetMode(status.ssid, pin) else WifiTargetMode.AUTO
            val preferred = mode == WifiTargetMode.PREFER_5_GHZ
            val on5G = status.band == BandType.BAND_5_GHZ || status.band == BandType.BAND_6_GHZ
            status.copy(
                isLockedToBssid = pin != null && pin.equals(status.bssid, true),
                profileObservedAtMillis = if (inspection != null) clock.nowMillis() else 0L,
                lockedBssid = pin, profileInspectionKnown = inspection != null,
                isPreferenceRequested = preferred,
                requestedPinnedBssid = if (mode == WifiTargetMode.PIN_BSSID) prefs.getPinnedBssid(status.ssid) else null,
                configuredMacPolicy = inspection?.let { when (it.getInt("mac", -1)) { 0 -> com.quintz.wifi.model.MacAddressPolicy.DEVICE; 1 -> com.quintz.wifi.model.MacAddressPolicy.RANDOMIZED; else -> null } },
                isPreferred5GHz = preferred && on5G, isPreferred5GHzFallback = preferred && !on5G
            )
        } else status

        val matchedLink = nativeStatus.isConnected && finalStatus.isConnected &&
            (nativeStatus.ssid == finalStatus.ssid && nativeStatus.bssid.equals(finalStatus.bssid, true) ||
                nativeStatus.nativeIdentityLimited && nativeStatus.networkId == finalStatus.networkId && nativeStatus.frequency == finalStatus.frequency)
        val observedStatus = finalStatus.copy(
            ipAddresses = if (matchedLink) nativeStatus.ipAddresses else finalStatus.ipAddresses,
            ipAddress = if (matchedLink && nativeStatus.ipAddresses.isNotEmpty()) nativeStatus.ipAddresses.first() else finalStatus.ipAddress,
            internetValidated = matchedLink && nativeStatus.internetValidated,
            observedAtMillis = clock.nowMillis(), identityObservedAtMillis = clock.nowMillis(),
            isMacPolicyPending = prefs.isMacPolicyPending(finalStatus.ssid, finalStatus.configuredMacPolicy, finalStatus.observedMacAddress))
        val oldStatus = _status.value
        _status.value = observedStatus
        if (oldStatus.bssid != observedStatus.bssid || oldStatus.isConnected != observedStatus.isConnected ||
            oldStatus.isLockedToBssid != observedStatus.isLockedToBssid || oldStatus.isPreferred5GHz != observedStatus.isPreferred5GHz ||
            oldStatus.isPreferred5GHzFallback != observedStatus.isPreferred5GHzFallback
        ) {
            DiagnosticLogger.log(
                "WIFI",
                "State changed: connected=${observedStatus.isConnected}, ssid='${observedStatus.ssid}', bssid=${observedStatus.bssid}, band=${observedStatus.band.displayName}, rssi=${observedStatus.rssi}dBm, locked=${observedStatus.isLockedToBssid}, preferred5G=${observedStatus.isPreferred5GHz}, fallback5G=${observedStatus.isPreferred5GHzFallback}"
            )
        }
        requestTileUpdate()
        observedStatus
    }

    private fun requestTileUpdate() {
        try {
            android.service.quicksettings.TileService.requestListeningState(
                context,
                android.content.ComponentName(context, com.quintz.wifi.service.TileService::class.java)
            )
        } catch (_: Exception) {}
    }

    var lastScanCompletedTimestamp: Long = 0L
        private set
    var lastScanAttemptTimestamp: Long = 0L
        private set
    var lastScanSucceeded: Boolean = false
        private set
    var lastScanDurationMillis: Long = 0L
        private set
    var lastScanWasCoalesced: Boolean = false
        private set

    suspend fun scanRadios(
        correlationId: String? = null,
        freshForSsid: String? = null,
        freshForBssid: String? = null
    ): List<AccessPointRadio> = withContext(Dispatchers.IO) {
        if (!ShizukuManager.isReady()) {
            invalidateRadios()
            return@withContext emptyList()
        }

        val requestedAtElapsed = SystemClock.elapsedRealtime()
        var lockAcquired = false
        changeQueuedScans(1)
        _scanState.value = ScanState(ScanPhase.QUEUED)
        try {
            val (batch, coalesced) = withTimeout(12_000L) { WifiScanCoordinator.scan(freshForSsid, freshForBssid, onLockAcquired = {
                lockAcquired = true
                _scanState.value = ScanState(ScanPhase.SCANNING)
                changeQueuedScans(-1)
            }) { previousAges ->
                scanRadiosDirect(correlationId, freshForSsid, freshForBssid, previousAges)
            } }
            val currentBssid = _status.value.bssid
            val radios = batch.radios.map { radio ->
                radio.copy(isCurrent = currentBssid.isNotEmpty() && radio.bssid.equals(currentBssid, ignoreCase = true))
            }
            _radios.value = radios
            lastScanAttemptTimestamp = batch.completedAtMillis
            lastScanSucceeded = batch.succeeded
            _scanState.value = if (batch.succeeded) ScanState(ScanPhase.READY)
                else ScanState(ScanPhase.FAILED, "Android did not provide a fresh scan. Refresh to try again.")
            lastScanDurationMillis = batch.durationMillis
            lastScanWasCoalesced = coalesced
            if (batch.succeeded) lastScanCompletedTimestamp = batch.completedAtMillis
            if (coalesced) {
                DiagnosticLogger.log(
                    "WIFI_SCAN",
                    "id=${correlationId ?: "none"} result=coalesced radios=${batch.radios.size} ageMs=${(SystemClock.elapsedRealtime() - batch.completedAtElapsedMillis).coerceAtLeast(0L)}"
                )
            }
            radios
        } catch (e: TimeoutCancellationException) {
            lastScanSucceeded = false
            lastScanAttemptTimestamp = clock.nowMillis()
            lastScanDurationMillis = SystemClock.elapsedRealtime() - requestedAtElapsed
            lastScanWasCoalesced = false
            _scanState.value = ScanState(ScanPhase.FAILED, "Scan timed out. Refresh to try again.")
            // Previous rows are no longer actionable after an incomplete refresh.
            _radios.value = emptyList()
            emptyList()
        } catch (failure: CancellationException) {
            _scanState.value = ScanState(ScanPhase.IDLE)
            throw failure
        } catch (_: Exception) {
            lastScanSucceeded = false
            lastScanAttemptTimestamp = clock.nowMillis()
            _radios.value = emptyList()
            _scanState.value = ScanState(ScanPhase.FAILED, "Scan could not be read. Check Shizuku and retry.")
            emptyList()
        } finally {
            if (!lockAcquired) changeQueuedScans(-1)
        }
    }

    private suspend fun scanRadiosDirect(
        correlationId: String?,
        freshForSsid: String?,
        freshForBssid: String?,
        previousAgesByBssid: Map<String, Long>
    ): WifiScanBatch = withContext(Dispatchers.IO) {
        // Read a baseline before requesting a scan: a successful shell exit alone does not
        // prove Android replaced its cache. No app location permission is needed for this path.
        val baselineReadAt = SystemClock.elapsedRealtime()
        val baseline = readStructuredRadios()?.associate { it.bssid.lowercase() to it.observedAtElapsedMillis }
            ?: previousAgesByBssid.mapValues { baselineReadAt - it.value * 1000L }
        val completion = WifiScanCompletion(context)
        try {
            val scanStartedAtMs = SystemClock.elapsedRealtime()
            val scanStartedElapsedMs = SystemClock.elapsedRealtime()
            WifiScanCoordinator.awaitScanStart()
            val scanStart = ShizukuManager.exec("cmd wifi start-scan", correlationId = correlationId, onStarted = WifiScanCoordinator::recordScanStart)
            if (!scanStart.isSuccess) {
                DiagnosticLogger.log(
                    "WIFI_SCAN",
                    "id=${correlationId ?: "none"} result=failed phase=start_scan durationMs=${SystemClock.elapsedRealtime() - scanStartedElapsedMs} exitCode=${scanStart.exitCode} stderr=${scanStart.stderr.take(160)}"
                )
                return@withContext WifiScanBatch(emptyList(), clock.nowMillis(), SystemClock.elapsedRealtime(), false, SystemClock.elapsedRealtime() - scanStartedElapsedMs)
            }

            val requiresFreshTarget = freshForSsid != null || freshForBssid != null
            val deadlineElapsedMs = scanStartedElapsedMs + SCAN_REFRESH_TIMEOUT_MS
            var list = emptyList<AccessPointRadio>()
            var refreshObserved = false
            var targetRefreshObserved = false
            var completionReported = false
            while (true) {
                val remainingMs = deadlineElapsedMs - SystemClock.elapsedRealtime()
                if (remainingMs > 0L) {
                    // Read immediately on completion, otherwise poll for OEMs that suppress it.
                    if (completion.await(minOf(SCAN_RESULT_POLL_MS, remainingMs)) == true) {
                        completionReported = true
                    }
                }
                list = readStructuredRadios() ?: return@withContext WifiScanBatch(emptyList(), clock.nowMillis(), SystemClock.elapsedRealtime(), false, SystemClock.elapsedRealtime() - scanStartedElapsedMs)
                // A completion notification permits an empty successful scan. Targeted steering
                // still requires a new observation of the requested radio, not just a broadcast.
                refreshObserved = completionReported || hasNewScanObservation(list.map { it.copy(observedAtMillis = it.observedAtElapsedMillis) }, baseline, scanStartedAtMs)
                if (requiresFreshTarget) {
                    val targets = list.filter { radio ->
                        val isTarget = if (!freshForBssid.isNullOrBlank()) {
                            radio.bssid.equals(freshForBssid, ignoreCase = true) && (freshForSsid == null || radio.ssid == freshForSsid)
                        } else {
                            radio.ssid == freshForSsid &&
                                (radio.band == BandType.BAND_5_GHZ || radio.band == BandType.BAND_6_GHZ)
                        }
                        isTarget && radio.ageSeconds <= 8L
                    }
                    targetRefreshObserved = hasNewScanObservation(targets.map { it.copy(observedAtMillis = it.observedAtElapsedMillis) }, baseline, scanStartedAtMs)
                }
                val requestSatisfied = if (requiresFreshTarget) targetRefreshObserved else refreshObserved
                if (requestSatisfied || SystemClock.elapsedRealtime() >= deadlineElapsedMs) break
            }

            val usableList = if (requiresFreshTarget && !targetRefreshObserved) {
                DiagnosticLogger.log(
                    "WIFI_SCAN",
                    "id=${correlationId ?: "none"} result=target_not_refreshed targetSsid='${freshForSsid.orEmpty()}' targetBssid='${freshForBssid.orEmpty()}'"
                )
                list.filterNot {
                    if (!freshForBssid.isNullOrBlank()) it.bssid.equals(freshForBssid, ignoreCase = true)
                    else it.ssid == freshForSsid &&
                        (it.band == BandType.BAND_5_GHZ || it.band == BandType.BAND_6_GHZ)
                }
            } else list
            val completedAtMs = clock.nowMillis()
            val durationMillis = SystemClock.elapsedRealtime() - scanStartedElapsedMs
            val succeeded = if (requiresFreshTarget) targetRefreshObserved else refreshObserved
            DiagnosticLogger.log(
                "WIFI_SCAN",
                "id=${correlationId ?: "none"} result=${if (succeeded) "completed" else "not_refreshed"} radios=${usableList.size} targetSsid='${freshForSsid.orEmpty()}' targetRefreshObserved=$targetRefreshObserved completionReported=$completionReported durationMs=$durationMillis completedAtMs=$completedAtMs"
            )
            WifiScanBatch(usableList, completedAtMs, SystemClock.elapsedRealtime(), succeeded, durationMillis)
        } finally {
            // Also unregister on cancellation, failed shell commands, and timeout.
            completion.close()
        }
    }

    private suspend fun readStructuredRadios(): List<AccessPointRadio>? {
        val result = profiles.call(context, "scan", Bundle()) ?: return null
        @Suppress("DEPRECATION") val scans = result.getParcelableArrayList<ScanResult>("radios") ?: return null
        val now = SystemClock.elapsedRealtime()
        return scans.mapNotNull { scan ->
            val ageMicros = SystemClock.elapsedRealtimeNanos() / 1000L - scan.timestamp
            if (ageMicros < 0L) return@mapNotNull null
            val observed = scan.timestamp / 1000L
            val age = (ageMicros + 999L) / 1000L
            val name = scan.SSID
            if (age < 0L || age > 15_000L || name.isNullOrEmpty() || scan.level !in -126..-1 ||
                !scan.BSSID.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))) null
            else AccessPointRadio(scan.BSSID, name, scan.frequency, BandType.fromFrequency(scan.frequency),
                AccessPointRadio.frequencyToChannel(scan.frequency), scan.level, scan.capabilities.orEmpty(),
                scan.BSSID.equals(_status.value.bssid, true), (age + 999L) / 1000L,
                observed, observed)
        }.groupBy { it.bssid.lowercase() }.map { (_, values) -> values.sortedWith(compareByDescending<AccessPointRadio> { it.observedAtElapsedMillis }.thenByDescending { it.rssi }).first() }
            .sortedWith(compareByDescending<AccessPointRadio> { it.isCurrent }.thenByDescending { it.ssid == _status.value.ssid }
                .thenByDescending { it.band == BandType.BAND_5_GHZ || it.band == BandType.BAND_6_GHZ }.thenByDescending { it.rssi })
    }

    suspend fun lockToBssid(
        ssid: String, bssid: String, passphrase: String, securityType: String = "",
        macAddressPolicy: com.quintz.wifi.model.MacAddressPolicy? = null,
        unpinProfileForRoaming: Boolean = false, deferIfHealthy24Ghz: Boolean = false,
        allowSwitchFromHealthy24Ghz: Boolean = false, requestSource: String = "unspecified",
        correlationId: String = DiagnosticLogger.newCorrelationId()
    ): Boolean = lockToBssidResult(ssid, bssid, passphrase, securityType, macAddressPolicy,
        unpinProfileForRoaming, deferIfHealthy24Ghz, allowSwitchFromHealthy24Ghz, requestSource, correlationId).verified

    suspend fun lockToBssidResult(
        ssid: String, bssid: String, passphrase: String, securityType: String = "",
        macAddressPolicy: MacAddressPolicy? = null, unpinProfileForRoaming: Boolean = false,
        deferIfHealthy24Ghz: Boolean = false, allowSwitchFromHealthy24Ghz: Boolean = false,
        requestSource: String = "unspecified", correlationId: String = DiagnosticLogger.newCorrelationId(),
        actionContext: WifiActionContext? = null
    ): WifiActionResult = lockToBssidInternal(ssid, bssid, passphrase, securityType, macAddressPolicy,
        unpinProfileForRoaming, deferIfHealthy24Ghz, allowSwitchFromHealthy24Ghz,
        requestSource, correlationId, false, actionContext)

    private suspend fun lockToBssidInternal(
        ssid: String, bssid: String, passphrase: String, securityType: String,
        macAddressPolicy: com.quintz.wifi.model.MacAddressPolicy?, unpinProfileForRoaming: Boolean,
        deferIfHealthy24Ghz: Boolean, allowSwitchFromHealthy24Ghz: Boolean,
        requestSource: String, correlationId: String, operationLockHeld: Boolean, actionContext: WifiActionContext? = null
    ): WifiActionResult = transition(ssid, bssid, passphrase, securityType, macAddressPolicy,
        if (unpinProfileForRoaming) WifiTargetMode.PREFER_5_GHZ else WifiTargetMode.PIN_BSSID,
        preserveMode = false, reconnect = true, operationLockHeld = operationLockHeld,
        automated = deferIfHealthy24Ghz || requestSource.startsWith("watchdog"), holdHealthy = deferIfHealthy24Ghz && !allowSwitchFromHealthy24Ghz,
        requestSource = requestSource, correlationId = correlationId, actionContext = actionContext)

    suspend fun unlockToAuto(
        ssid: String, passphrase: String? = null, securityType: String = "",
        macAddressPolicy: MacAddressPolicy? = null, isAutomatedFallback: Boolean = false,
        preserveTargetMode: Boolean = false, requestSource: String = "unspecified",
        correlationId: String = DiagnosticLogger.newCorrelationId()
    ): Boolean = unlockToAutoResult(ssid, securityType, isAutomatedFallback,
        preserveTargetMode, requestSource, correlationId).verified

    /** Only the saved BSSID and app steering intent change; credentials and MAC are retained. */
    suspend fun unlockToAutoResult(
        ssid: String, securityType: String = "", isAutomatedFallback: Boolean = false,
        preserveTargetMode: Boolean = false, requestSource: String = "unspecified",
        correlationId: String = DiagnosticLogger.newCorrelationId(), actionContext: WifiActionContext? = null
    ): WifiActionResult = transition(ssid, null, "", securityType, null, WifiTargetMode.AUTO,
        preserveTargetMode || isAutomatedFallback, reconnect = isAutomatedFallback && !status.value.isConnected,
        operationLockHeld = false, automated = isAutomatedFallback, holdHealthy = false,
        requestSource = requestSource, correlationId = correlationId, pinOnly = true, actionContext = actionContext)

    private suspend fun profile(ssid: String, security: String, id: Int? = null): Bundle? =
        profiles.call(context, "inspect", ProfileAccess.identity(ssid, security, id))

    /** Existing-profile operation: never supplies a password, clears a pin, or chooses another AP. */
    suspend fun changeMacPolicy(ssid: String, policy: MacAddressPolicy): MacPolicyChangeResult =
        changeMacPolicyResult(ssid, policy).outcome

    suspend fun changeMacPolicyResult(ssid: String, policy: MacAddressPolicy,
        actionContext: WifiActionContext? = null): MacPolicyActionResult = withContext(Dispatchers.IO) {
        if (!WifiOperationCoordinator.tryBegin(WifiOperationKind.CHANGE_MAC_POLICY)) {
            lastTransitionResult = TransitionResult.Busy
            return@withContext MacPolicyActionResult(MacPolicyChangeResult.FAILED, WifiActionResult(TransitionResult.Busy))
        }
        val backup = ProfileBackupStore(context)
        var result: TransitionResult = TransitionResult.Failed
        var outcome = MacPolicyChangeResult.FAILED
        var completion = WifiActionResult(result)
        var transaction: ProfileTransaction<Bundle>? = null
        lastPasswordStorageFailure = false
        lastRecoveryFailure = RecoveryFailure.RESTORE_FAILED
        try {
            result = withTimeout(60_000L) {
                if (!ShizukuManager.isReady()) return@withTimeout TransitionResult.AccessUnavailable
                if (!recoverPendingProfile(backup, operationLockHeld = true)) return@withTimeout TransitionResult.RecoveryPending
                val initial = refreshStatus(forceFresh = true)
                if (actionContext != null && !actionContext.matches(initial)) return@withTimeout TransitionResult.NetworkChanged
                if (!initial.isConnected || initial.ssid != ssid || initial.networkId == null || !initial.profileInspectionKnown)
                    return@withTimeout TransitionResult.Unsupported
                val sec = initial.securityType
                val identity = ProfileAccess.identity(ssid, sec, initial.networkId)
                val existing = profiles.callChecked(context, "lookup", identity)
                if (existing.getBoolean("absent")) return@withTimeout TransitionResult.Unsupported
                val configured = when (existing.getInt("mac", -1)) {
                    0 -> MacAddressPolicy.DEVICE
                    1 -> MacAddressPolicy.RANDOMIZED
                    else -> return@withTimeout TransitionResult.Unsupported
                }
                val mode = prefs.getWifiTargetMode(ssid) ?: if (existing.getString("pin").isNullOrBlank())
                    WifiTargetMode.AUTO else WifiTargetMode.PIN_BSSID
                val plan = macPolicyChangePlan(configured, policy, mode)
                if (plan == MacPolicyChangePlan.UNCHANGED) {
                    outcome = MacPolicyChangeResult.UNCHANGED
                    return@withTimeout TransitionResult.Verified
                }
                val pin = existing.getString("pin")
                if (mode == WifiTargetMode.PIN_BSSID && (pin.isNullOrBlank() ||
                        prefs.getWifiTargetMode(ssid) == WifiTargetMode.PIN_BSSID && !pin.equals(prefs.getPinnedBssid(ssid), true)))
                    return@withTimeout TransitionResult.Unsupported
                if (mode == WifiTargetMode.PREFER_5_GHZ && !pin.isNullOrBlank())
                    return@withTimeout TransitionResult.Unsupported
                val original = profiles.callChecked(context, "preview", Bundle(existing).apply { putBoolean("macOnly", true) })
                check(sameProfile(original, existing))
                val target = profiles.callChecked(context, "preview", Bundle(existing).apply {
                    putBoolean("macOnly", true)
                    putInt("newMac", if (policy == MacAddressPolicy.DEVICE) 0 else 1)
                })
                check(!target.getString("expectedMac").isNullOrBlank())
                // Attach the old app preference only, leaving Quintz's password copy untouched.
                target.putBundle("appSettings", prefs.macTransitionSettings(ssid))
                target.putString("priorActiveSsid", initial.ssid)
                target.putString("operation", "mac_policy")
                val backend = transactionBackend(backup, identity, ssid, original.getString("pin"), sec,
                    policy, "", null, false, plan == MacPolicyChangePlan.RECONNECT, false,
                    macOnly = true, requestedMode = mode, actionContext = actionContext ?: WifiActionContext.capture(initial))
                // Preserve the MAC identity metadata used to verify rollback after a reconnect.
                val store = object : RecoveryStore<Bundle> {
                    private val delegate = bundleStore(backup)
                    override fun pending() = delegate.pending()
                    override fun clear() = delegate.clear()
                    override fun save(record: RecoveryRecord<Bundle>) = delegate.save(record.copy(original = original))
                }
                transaction = ProfileTransaction(store, backend)
                val verified = transaction!!.execute(target, selectProfile = plan == MacPolicyChangePlan.RECONNECT)
                if (verified == TransitionResult.Verified) outcome = if (plan == MacPolicyChangePlan.RECONNECT)
                    MacPolicyChangeResult.RECONNECTED else MacPolicyChangeResult.SAVED
                verified
            }
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
        } catch (_: WifiContextChanged) { result = TransitionResult.NetworkChanged
        } catch (e: ProfileRecoveryException) {
            lastRecoveryFailure = e.reason
            result = if (backup.exists()) TransitionResult.RecoveryPending else TransitionResult.Unsupported
        } catch (_: Exception) { result = TransitionResult.Failed }
        finally {
            if (result != TransitionResult.Verified && backup.exists()) {
                val restored = withContext(NonCancellable) { withTimeoutOrNull(20_000L) {
                    try { transaction?.recover() ?: recoverPendingProfile(backup, operationLockHeld = true) }
                    catch (failure: ProfileRecoveryException) { lastRecoveryFailure = failure.reason; false }
                    catch (_: Exception) { false }
                } == true }
                if (!restored) result = TransitionResult.RecoveryPending
            }
            lastTransitionResult = result
            completion = WifiActionResult(result, lastRecoveryFailure.takeIf { result == TransitionResult.RecoveryPending })
            WifiOperationCoordinator.end()
        }
        MacPolicyActionResult(if (result == TransitionResult.Verified) outcome else MacPolicyChangeResult.FAILED, completion)
    }

    /** Credentials remain provisional until the final connection and full profile both agree. */
    private suspend fun transition(
        ssid: String, bssid: String?, password: String, securityType: String,
        macPolicy: com.quintz.wifi.model.MacAddressPolicy?, mode: WifiTargetMode,
        preserveMode: Boolean, reconnect: Boolean, operationLockHeld: Boolean,
        automated: Boolean, holdHealthy: Boolean, requestSource: String, correlationId: String,
        pinOnly: Boolean = false, actionContext: WifiActionContext? = null
    ): WifiActionResult = withContext(Dispatchers.IO) {
        val opKind = when (mode) {
            WifiTargetMode.PREFER_5_GHZ -> WifiOperationKind.PREFER_5GHZ
            WifiTargetMode.PIN_BSSID -> WifiOperationKind.LOCK_BSSID
            WifiTargetMode.AUTO -> WifiOperationKind.UNLOCK_ROAM
        }
        if (!operationLockHeld && !WifiOperationCoordinator.tryBegin(opKind)) {
            return@withContext WifiActionResult(TransitionResult.Busy)
        }
        lastPasswordStorageFailure = false
        val backup = ProfileBackupStore(context)
        var result: TransitionResult = TransitionResult.Failed
        var completion = WifiActionResult(result)
        var transaction: ProfileTransaction<Bundle>? = null
        lastRecoveryFailure = RecoveryFailure.RESTORE_FAILED
        try {
            result = withTimeout(60_000L) {
                if (!ShizukuManager.isReady()) {
                    if (backup.exists()) { lastRecoveryFailure = RecoveryFailure.ACCESS_UNAVAILABLE; return@withTimeout TransitionResult.RecoveryPending }
                    return@withTimeout TransitionResult.AccessUnavailable
                }
                // Resume the durable affected-profile recovery before accepting another request.
                if (!recoverPendingProfile(backup, operationLockHeld = true)) return@withTimeout TransitionResult.RecoveryPending
                val initial = refreshStatus(forceFresh = true)
                if (actionContext != null && !actionContext.matches(initial)) return@withTimeout TransitionResult.NetworkChanged
                if (automated && (!prefs.isWatchdogEnabled || !wifiEnabled() || initial.isConnected && initial.ssid != ssid))
                    return@withTimeout TransitionResult.Failed
                val radio = if (bssid != null) scanRadios(correlationId, ssid, bssid)
                    .firstOrNull { it.ssid == ssid && it.bssid.equals(bssid, true) && it.ageSeconds <= 8L } else null
                if (bssid != null && (radio == null || !WifiSecurityPolicy.isSupported(radio.flags))) return@withTimeout TransitionResult.Failed
                val secName = securityType.ifEmpty {
                    if (radio != null) detectSecurityFromFlags(radio.flags, initial.securityType)
                    else if (initial.ssid == ssid) shellSecurity(initial.securityType).orEmpty() else ""
                }
                val sec = mapOf("open" to "0", "wpa2" to "2", "wpa3" to "4", "owe" to "6")[secName]
                    ?: return@withTimeout TransitionResult.Unsupported
                if (radio != null && !WifiSecurityPolicy.matchesSecurityType(sec, radio.flags)) return@withTimeout TransitionResult.Failed
                if (initial.isConnected && initial.ssid == ssid && !WifiSecurityPolicy.allowsSameSsidSelection(initial.securityType, sec))
                    return@withTimeout TransitionResult.Failed
                if (!pinOnly && sec in setOf("2", "4") && (password.isEmpty() || !prefs.isPasswordStorageAvailable)) return@withTimeout TransitionResult.StorageFailed
                if (holdHealthy && initial.band == BandType.BAND_2_4_GHZ && initial.rssi in com.quintz.wifi.model.HEALTHY_24G_THRESHOLD_RSSI..-1)
                    return@withTimeout TransitionResult.Failed
                if (automated && radio != null && radio.rssi < prefs.recoveryThresholdRssi) return@withTimeout TransitionResult.Failed
                var policy = macPolicy ?: prefs.getMacPolicy(ssid) ?: prefs.defaultMacPolicy
                val identity = ProfileAccess.identity(ssid, sec, initial.networkId.takeIf { initial.ssid == ssid && initial.securityType == sec })
                val existing = profiles.call(context, "lookup", identity) ?: return@withTimeout TransitionResult.Unsupported
                if (pinOnly) {
                    if (existing.getBoolean("absent")) return@withTimeout TransitionResult.Unsupported
                    policy = when (existing.getInt("mac", -1)) {
                        0 -> MacAddressPolicy.DEVICE
                        1 -> MacAddressPolicy.RANDOMIZED
                        else -> return@withTimeout TransitionResult.Unsupported
                    }
                }
                val preview = Bundle(if (existing.getBoolean("absent")) identity else existing).apply {
                    putString("newPin", if (bssid != null) bssid else null)
                    if (!pinOnly) putInt("newMac", if (policy == com.quintz.wifi.model.MacAddressPolicy.DEVICE) 0 else 1)
                    if (pinOnly) putBoolean("macOnly", true)
                    if (!pinOnly && sec in setOf("2", "4")) putString("newPassword", if (password.length == 64 && password.all { it in "0123456789abcdefABCDEF" }) password else "\"$password\"")
                }
                val target = profiles.call(context, "preview", preview) ?: return@withTimeout TransitionResult.Unsupported
                val settings = if (pinOnly) prefs.pinTransitionSettings(ssid) else prefs.transitionSettings(ssid, bssid.orEmpty())
                target.putBundle("appSettings", settings)
                target.putString("priorActiveSsid", initial.ssid)
                if (pinOnly) target.putString("operation", "unpin")
                val backend = transactionBackend(backup, identity, ssid, bssid, sec, policy, password,
                    if (preserveMode) null else mode, !automated, reconnect,
                    unpinAfter = !pinOnly && mode != WifiTargetMode.PIN_BSSID, automated = automated, requestedMode = mode,
                    pinOnly = pinOnly, actionContext = actionContext)
                transaction = ProfileTransaction(bundleStore(backup), backend)
                transaction!!.execute(target, selectProfile = reconnect)
            }
            lastPasswordStorageFailure = result == TransitionResult.StorageFailed
        } catch (e: CancellationException) {
            result = TransitionResult.Failed
            if (e !is TimeoutCancellationException) throw e
        } catch (_: WifiContextChanged) { result = TransitionResult.NetworkChanged
        } catch (failure: ProfileRecoveryException) {
            lastRecoveryFailure = failure.reason
            result = if (backup.exists()) TransitionResult.RecoveryPending else TransitionResult.Unsupported
        } catch (_: Exception) { result = TransitionResult.Failed }
        finally {
            if (result != TransitionResult.Verified && backup.exists()) {
                val recovered = withContext(NonCancellable) {
                    withTimeoutOrNull(20_000L) {
                        try { transaction?.recover() ?: recoverPendingProfile(backup, operationLockHeld = true) }
                        catch (failure: ProfileRecoveryException) { lastRecoveryFailure = failure.reason; false }
                        catch (_: Exception) { false }
                    } == true
                }
                if (!recovered) result = TransitionResult.RecoveryPending
            }
            lastTransitionResult = result
            completion = WifiActionResult(result, lastRecoveryFailure.takeIf { result == TransitionResult.RecoveryPending })
            DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId source=$requestSource result=${result.javaClass.simpleName}")
            if (!operationLockHeld) WifiOperationCoordinator.end()
        }
        completion
    }

    private fun wifiEnabled(): Boolean = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true
    private fun sameProfile(a: Bundle, b: Bundle, allowReassignedId: Boolean = false): Boolean {
        fun identity(bundle: Bundle) = ProfileIdentity(bundle.getInt("id", -1), bundle.getString("ssid"),
            bundle.getString("security"), bundle.getString("platform"), bundle.getInt("fingerprintVersion", 1))
        return ProfileRecoveryPolicy.same(identity(a), identity(b),
            a.getByteArray("fingerprint")?.contentEquals(b.getByteArray("fingerprint") ?: byteArrayOf()) == true, allowReassignedId)
    }
    private fun bundleStore(backup: ProfileBackupStore) = object : RecoveryStore<Bundle> {
        override fun pending(): RecoveryRecord<Bundle>? = backup.read()?.let { RecoveryRecord(it.getBundle("original")?.takeUnless { p -> p.getBoolean("absent") }, it.getBundle("expected")!!, it.getBoolean("selectProfile", true)) }
        override fun save(record: RecoveryRecord<Bundle>) {
            check(ProfileBackupCompatibility.valid(record.expected))
            backup.write(Bundle().apply { putBundle("original", record.original ?: record.expected.apply { putBoolean("absent", true) }); putBundle("expected", record.expected); putBoolean("selectProfile", record.selectProfile) })
        }
        override fun clear() = backup.clear()
    }

    /** Read in the privileged process so hidden fields participate in the complete comparison.
     * No profile is written; only a recorded selection can accept a generated address change. */
    private suspend fun selectedReadback(backup: ProfileBackupStore, snapshot: Bundle,
                                        allowReassignedId: Boolean = false): Bundle? {
        val record = backup.read() ?: return null
        return try {
            profiles.callChecked(context, "selectedReadback", Bundle(snapshot).apply {
                val originalExists = record.getBundle("original")?.getBoolean("absent") == false
                putBoolean("selectionRecorded", record.getBoolean("selectionStarted") && record.getBoolean("selectProfile", true))
                putBoolean("originalExists", originalExists)
                putBoolean("allowReassignedId", allowReassignedId && originalExists)
            })
        } catch (failure: ProfileRecoveryException) {
            if (failure.reason != RecoveryFailure.PROFILE_CHANGED) throw failure
            null
        }
    }

    /** Keep the original parcel intact, and durably retain the former expected state before
     * recording Android's verified generated address. Metadata belongs to the transaction. */
    private fun recordSelectedReadback(backup: ProfileBackupStore, snapshot: Bundle, current: Bundle,
                                       restoring: Boolean = false) {
        val changed = !sameProfile(snapshot, current)
        val merged = Bundle(snapshot).apply { putAll(current) }
        if (changed) {
            val record = backup.read()!!
            @Suppress("DEPRECATION") val owned = record.getParcelableArrayList<Bundle>("owned") ?: arrayListOf()
            val ownedState = if (restoring) merged else Bundle(snapshot)
            if (owned.none { sameProfile(it, ownedState) }) owned.add(ownedState)
            record.putParcelableArrayList("owned", owned)
            if (!restoring) record.putBundle("expected", merged)
            backup.write(record)
            DiagnosticLogger.log("PROFILE_RECOVERY", "result=selected_readback_verified generatedAddressUpdated=true restoring=$restoring")
        } else if (!restoring) {
            val record = backup.read()!!
            record.putBundle("expected", merged); backup.write(record)
        }
        snapshot.putAll(merged)
    }

    private fun connectedToProfile(status: WifiStatus, profile: Bundle): Boolean =
        status.isConnected && status.ssid == profile.getString("ssid")?.removeSurrounding("\"") &&
            status.securityType == profile.getString("security") && status.networkId == profile.getInt("id") &&
            status.hasIpConfiguration &&
            (profile.getString("pin").isNullOrBlank() || status.bssid.equals(profile.getString("pin"), true)) &&
            !profile.getString("expectedMac").isNullOrBlank() && status.observedMacAddress.equals(profile.getString("expectedMac"), true)
    private fun transactionBackend(
        backup: ProfileBackupStore, identity: Bundle, ssid: String, bssid: String?, sec: String,
        policy: com.quintz.wifi.model.MacAddressPolicy, password: String, mode: WifiTargetMode?,
        establishTrust: Boolean, reconnect: Boolean, unpinAfter: Boolean,
        automated: Boolean = false, requestedMode: WifiTargetMode = WifiTargetMode.AUTO,
        macOnly: Boolean = false, pinOnly: Boolean = false, actionContext: WifiActionContext? = null
    ) = object : TransactionBackend<Bundle> {
        private var recovering = false
        private var hasApplied = false
        override fun beginRecovery() { recovering = true }
        override fun recoveryConflict() { lastRecoveryFailure = RecoveryFailure.PROFILE_CHANGED }
        override fun recoveryRestoreFailed() { lastRecoveryFailure = RecoveryFailure.RESTORE_FAILED }
        private suspend fun guardForward() {
            if (!recovering && actionContext != null) {
                if (!ShizukuManager.isReady()) throw ProfileRecoveryException(RecoveryFailure.ACCESS_UNAVAILABLE)
                val current = refreshStatus(forceFresh = true)
                if (!hasApplied && !actionContext.matches(current)) throw WifiContextChanged()
                if (hasApplied && current.isConnected && current.ssid !in setOf(actionContext.ssid, ssid)) throw WifiContextChanged()
            }
            if (macOnly && !recovering) {
                val savedMode = prefs.getWifiTargetMode(ssid)
                check(savedMode == null || savedMode == requestedMode) { "Steering intent changed during MAC update" }
                if (savedMode == WifiTargetMode.PIN_BSSID)
                    check(bssid != null && prefs.getPinnedBssid(ssid).equals(bssid, true)) { "Saved pin changed during MAC update" }
                val native = getNativeWifiStatus()
                check(!native.isConnected || native.ssid.isEmpty() || native.ssid == ssid) { "Active network changed during MAC update" }
            }
            if (!automated || recovering) return
            check(prefs.isWatchdogEnabled && wifiEnabled() && ShizukuManager.isReady())
            val savedMode = prefs.getWifiTargetMode(ssid)
            check(when (requestedMode) {
                WifiTargetMode.PIN_BSSID -> savedMode == WifiTargetMode.PIN_BSSID && prefs.getPinnedBssid(ssid).equals(bssid, true) && bssid != null && prefs.getTrustedRadioSecurity(ssid, bssid) == sec
                WifiTargetMode.PREFER_5_GHZ -> savedMode == WifiTargetMode.PREFER_5_GHZ && bssid != null && sec in setOf("2", "4") && prefs.getTrustedRadioSecurity(ssid, bssid) == sec
                WifiTargetMode.AUTO -> savedMode == WifiTargetMode.PREFER_5_GHZ && prefs.getTrustedSecurity(ssid) == sec
            })
        }
        override suspend fun current(): Bundle? {
            val lookup = Bundle(identity).apply { if (recovering) remove("id") }
            val found = profiles.callChecked(context, "lookup", lookup).takeUnless { it.getBoolean("absent") }
            val record = backup.read()
            if (recovering && found != null && record?.getBundle("original")?.getBoolean("absent") == true && record.getBundle("expected")!!.getInt("id", -1) < 0)
                throw ProfileRecoveryException(RecoveryFailure.OWNERSHIP_UNKNOWN)
            if (recovering && found != null && record?.getBoolean("selectionStarted") == true) {
                val expected = record.getBundle("expected")!!
                // An interrupted selection can leave Android's generated address newer than
                // the recorded target. Rebase only after the complete protected comparison.
                if (!sameProfile(found, expected, allowReassignedId = true)) {
                    selectedReadback(backup, expected, allowReassignedId = true)?.let {
                        recordSelectedReadback(backup, expected, it)
                        return it
                    }
                }
            }
            return found
        }
        override fun same(a: Bundle, b: Bundle) = sameProfile(a, b,
            allowReassignedId = recovering && backup.read()?.getBundle("original")?.getBoolean("absent") == false)
        override fun owns(snapshot: Bundle): Boolean {
            @Suppress("DEPRECATION") val states = backup.read()?.getParcelableArrayList<Bundle>("owned").orEmpty()
            return states.any { same(snapshot, it) }
        }
        override suspend fun apply(snapshot: Bundle): Bundle {
            guardForward()
            val record = backup.read()!!
            profiles.callChecked(context, "validate", snapshot)
            val original = record.getBundle("original")!!; val expected = record.getBundle("expected")!!
            val now = current()
            check(now == null && original.getBoolean("absent") || now != null && (same(now, original) || same(now, expected) || owns(now)))
            val updated = profiles.callChecked(context, "put", Bundle(snapshot).apply {
                putByteArray("expected", now?.getByteArray("fingerprint"))
            })
            hasApplied = true
            if (!recovering) {
                updated.putBundle("appSettings", expected.getBundle("appSettings"))
                updated.putString("priorActiveSsid", expected.getString("priorActiveSsid"))
                if (pinOnly) updated.putString("operation", "unpin")
                if (macOnly) {
                    updated.putString("operation", "mac_policy")
                    updated.putString("expectedMac", expected.getString("expectedMac"))
                }
                record.putBundle("expected", updated); backup.write(record)
            }
            return updated
        }
        override suspend fun remove(snapshot: Bundle) {
            profiles.callChecked(context, "delete", snapshot)
        }
        override suspend fun select(snapshot: Bundle) {
            val record = backup.read()!!
            val original = record.getBundle("original")!!
            val restoring = recovering && !original.getBoolean("absent")
            if (restoring && !record.getBoolean("selectionStarted")) return
            if (restoring && record.getBundle("expected")!!.getString("priorActiveSsid") != ssid) return
            if (reconnect || restoring) {
                // If a user connected to another network during recovery, preserve that choice.
                val native = getNativeWifiStatus()
                if (restoring && native.isConnected && native.ssid.isNotEmpty() && native.ssid != ssid) return
                guardForward()
                check(wifiEnabled())
                if (!recovering) { record.putBoolean("selectionStarted", true); backup.write(record) }
                profiles.callChecked(context, "select", snapshot)
            }
        }
        override suspend fun verifyRecovery(snapshot: Bundle): Boolean {
            val record = backup.read()!!
            if (!record.getBoolean("selectionStarted") || record.getBundle("expected")!!.getString("priorActiveSsid") != ssid)
                return current()?.let { same(it, snapshot) } == true
            var status = refreshStatus(forceFresh = true)
            if (status.isConnected && status.ssid != ssid) return current()?.let { same(it, snapshot) } == true
            val deadline = SystemClock.elapsedRealtime() + 12_000L
            do {
                val inspection = selectedReadback(backup, snapshot, allowReassignedId = true) ?: return false
                if (connectedToProfile(status, inspection)) {
                    recordSelectedReadback(backup, snapshot, inspection, restoring = true)
                    return current()?.let { same(it, snapshot) } == true
                }
                delay(400); status = refreshStatus(forceFresh = true)
            } while (SystemClock.elapsedRealtime() < deadline)
            return false
        }
        override suspend fun verify(snapshot: Bundle): Boolean {
            guardForward()
            if ((macOnly || pinOnly) && !reconnect) return current()?.let {
                same(it, snapshot) && (!pinOnly || it.getString("pin").isNullOrBlank())
            } == true
            var final = refreshStatus(forceFresh = true)
            val deadline = SystemClock.elapsedRealtime() + 12_000L
            var selected: Bundle? = null
            fun connected() = final.isConnected && final.ssid == ssid && final.securityType == sec && final.hasIpConfiguration &&
                final.networkId == snapshot.getInt("id") && (bssid == null || final.bssid.equals(bssid, true)) &&
                selected?.let { connectedToProfile(final, it) } == true
            do {
                selected = selectedReadback(backup, snapshot) ?: return false
                if (connected()) break
                delay(400); final = refreshStatus(forceFresh = true)
            } while (SystemClock.elapsedRealtime() < deadline)
            if (!connected()) return false
            recordSelectedReadback(backup, snapshot, selected!!)
            if (unpinAfter && !snapshot.getString("pin").isNullOrBlank()) {
                val unpinned = profiles.call(context, "preview", Bundle(snapshot).apply { putString("newPin", null); putInt("newMac", snapshot.getInt("mac")) }) ?: return false
                // Journal the new expected state before changing the pin.
                val record = backup.read()!!
                @Suppress("DEPRECATION") val owned = record.getParcelableArrayList<Bundle>("owned") ?: arrayListOf()
                owned.add(Bundle(snapshot)); record.putParcelableArrayList("owned", owned)
                unpinned.putString("priorActiveSsid", record.getBundle("expected")!!.getString("priorActiveSsid")); unpinned.putBundle("appSettings", record.getBundle("expected")!!.getBundle("appSettings")); record.putBundle("expected", unpinned); backup.write(record)
                guardForward()
                if (profiles.call(context, "put", Bundle(unpinned).apply { putByteArray("expected", snapshot.getByteArray("fingerprint")) }) == null) return false
                snapshot.putString("pin", null); snapshot.putByteArray("fingerprint", unpinned.getByteArray("fingerprint")); snapshot.putByteArray("payload", unpinned.getByteArray("payload"))
            }
            val inspection = current() ?: return false
            final = refreshStatus(forceFresh = true)
            return connected() && same(inspection, snapshot) && inspection.getInt("mac") == snapshot.getInt("mac") &&
                (if (macOnly) inspection.getString("pin") == snapshot.getString("pin")
                else if (mode == WifiTargetMode.PIN_BSSID) inspection.getString("pin").equals(bssid, true) else inspection.getString("pin").isNullOrBlank())
        }
        override suspend fun commit(): Boolean {
            guardForward()
            if (pinOnly) return prefs.commitUnpin(ssid, mode != null)
            if (macOnly) return prefs.commitMacPolicy(ssid, policy,
                backup.read()!!.getBundle("expected")!!.getString("expectedMac").takeUnless { reconnect })
            return prefs.commitTransition(ssid, bssid, sec, password, policy, mode, establishTrust)
        }
        override suspend fun rollbackSettings(): Boolean = prefs.restoreTransitionSettings(backup.read()!!.getBundle("expected")!!.getBundle("appSettings")!!)
            .also { if (!it) lastRecoveryFailure = RecoveryFailure.SETTINGS_FAILED }
    }
    suspend fun recoverPendingProfile(backup: ProfileBackupStore = ProfileBackupStore(context), operationLockHeld: Boolean = false): Boolean =
        recoverPendingProfileResult(backup, operationLockHeld).verified

    suspend fun recoverPendingProfileResult(backup: ProfileBackupStore = ProfileBackupStore(context), operationLockHeld: Boolean = false): WifiActionResult {
        if (!backup.exists()) return WifiActionResult(TransitionResult.Verified)
        if (!operationLockHeld && !WifiOperationCoordinator.tryBegin(WifiOperationKind.RECONNECT)) { return WifiActionResult(TransitionResult.Busy) }
        lastRecoveryFailure = RecoveryFailure.RESTORE_FAILED
        return try {
            val recovered = withTimeoutOrNull(20_000L) {
                try { recoverPendingProfileInternal(backup) }
                catch (failure: ProfileRecoveryException) { lastRecoveryFailure = failure.reason; false }
                catch (failure: CancellationException) { throw failure }
                catch (_: Exception) { lastRecoveryFailure = RecoveryFailure.RESTORE_FAILED; false }
            }
            if (recovered == null) lastRecoveryFailure = RecoveryFailure.TIMEOUT
            if (recovered != true) {
                lastTransitionResult = TransitionResult.RecoveryPending
                DiagnosticLogger.log("PROFILE_RECOVERY", "result=pending reason=${lastRecoveryFailure.name}")
            } else DiagnosticLogger.log("PROFILE_RECOVERY", "result=verified")
            WifiActionResult(if (recovered == true) TransitionResult.Verified else TransitionResult.RecoveryPending,
                lastRecoveryFailure.takeIf { recovered != true })
        } finally { if (!operationLockHeld) WifiOperationCoordinator.end() }
    }
    private suspend fun recoverPendingProfileInternal(backup: ProfileBackupStore): Boolean {
        if (!backup.exists()) return true
        if (!ShizukuManager.isReady()) throw ProfileRecoveryException(RecoveryFailure.ACCESS_UNAVAILABLE)
        val record = ProfileBackupMigrator(context).upgrade(backup)
        val expected = record.getBundle("expected") ?: throw ProfileRecoveryException(RecoveryFailure.BACKUP_INVALID)
        val quotedSsid = expected.getString("ssid") ?: throw ProfileRecoveryException(RecoveryFailure.BACKUP_INVALID)
        val ssid = quotedSsid.removeSurrounding("\"")
        val sec = expected.getString("security") ?: throw ProfileRecoveryException(RecoveryFailure.BACKUP_INVALID)
        // A MAC-only change may have succeeded in Android before the old verifier rejected
        // its newly generated address. Finish it only while the target profile and connection
        // agree; failed or unrelated changes still follow the normal rollback path below.
        if (expected.getString("operation") == "mac_policy" && record.getBoolean("selectionStarted") &&
            record.getBoolean("selectProfile", true) && record.getBundle("original")?.getBoolean("absent") == false) {
            val selected = selectedReadback(backup, expected, allowReassignedId = true)
            if (selected != null && connectedToProfile(refreshStatus(forceFresh = true), selected)) {
                recordSelectedReadback(backup, expected, selected)
                val policy = when (selected.getInt("mac", -1)) {
                    0 -> MacAddressPolicy.DEVICE
                    1 -> MacAddressPolicy.RANDOMIZED
                    else -> throw ProfileRecoveryException(RecoveryFailure.PROFILE_CHANGED)
                }
                if (prefs.commitMacPolicy(ssid, policy, null)) {
                    val checked = selectedReadback(backup, selected, allowReassignedId = true)
                    if (checked != null && sameProfile(checked, selected, allowReassignedId = true) &&
                        connectedToProfile(refreshStatus(forceFresh = true), checked)) {
                        backup.clear()
                        DiagnosticLogger.log("PROFILE_RECOVERY", "result=completed operation=mac_policy")
                        return true
                    }
                }
            }
        }
        val backend = transactionBackend(backup, ProfileAccess.identity(ssid, sec), ssid, null, sec,
            prefs.getMacPolicy(ssid) ?: prefs.defaultMacPolicy, "", null, false, true, true,
            macOnly = expected.getString("operation") == "mac_policy", pinOnly = expected.getString("operation") == "unpin")
        return ProfileTransaction(bundleStore(backup), backend).recover()
    }

    suspend fun autoSelectAndLock5Ghz(
        ssid: String, passphrase: String, macAddressPolicy: MacAddressPolicy? = null,
        approvedBssid: String? = null, requestSource: String = "unspecified",
        correlationId: String = DiagnosticLogger.newCorrelationId()
    ): Boolean = autoSelectAndLock5GhzResult(ssid, passphrase, macAddressPolicy, approvedBssid,
        requestSource, correlationId).verified

    suspend fun autoSelectAndLock5GhzResult(
        ssid: String,
        passphrase: String,
        macAddressPolicy: com.quintz.wifi.model.MacAddressPolicy? = null,
        approvedBssid: String? = null,
        requestSource: String = "unspecified",
        correlationId: String = DiagnosticLogger.newCorrelationId(), actionContext: WifiActionContext? = null
    ): WifiActionResult {
        if (!WifiOperationCoordinator.tryBegin(WifiOperationKind.PREFER_5GHZ)) {
            DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId result=aborted reason=profile_operation_in_progress")
            return WifiActionResult(TransitionResult.Busy)
        }
        return try {
            withTimeoutOrNull(60_000L) {
                autoSelect5GhzInternal(ssid, passphrase, macAddressPolicy, approvedBssid, requestSource, correlationId, actionContext)
            } ?: WifiActionResult(TransitionResult.Failed)
        } finally { WifiOperationCoordinator.end() }
    }
    private suspend fun autoSelect5GhzInternal(
        ssid: String, passphrase: String, macAddressPolicy: com.quintz.wifi.model.MacAddressPolicy?,
        approvedBssid: String?, requestSource: String, correlationId: String, actionContext: WifiActionContext?
    ): WifiActionResult {
        lastTransitionResult = TransitionResult.Failed
        lastPasswordStorageFailure = false
        val current = refreshStatus(forceFresh = true)
        if (!current.isConnected || current.ssid != ssid || actionContext != null && !actionContext.matches(current)) {
            DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId result=aborted reason=active_network_changed")
            return WifiActionResult(TransitionResult.NetworkChanged)
        }
        DiagnosticLogger.log(
            "WIFI_ACTION",
            "id=$correlationId source=$requestSource action=prefer_5ghz_start ssid='$ssid' connected=${current.isConnected} currentBssid=${current.bssid} band=${current.band.displayName} rssi=${current.rssi} cachedRadios=${_radios.value.size}"
        )
        var currentRadios = _radios.value
        val hasFresh5G = currentRadios.any {
            it.ssid == ssid &&
                    (it.band == BandType.BAND_5_GHZ || it.band == BandType.BAND_6_GHZ) &&
                    it.observedAtElapsedMillis > 0L && clock.nowMillis() - it.observedAtElapsedMillis in 0L..8_000L
        }
        if (!hasFresh5G) {
            DiagnosticLogger.log("WIFI", "id=$correlationId source=$requestSource autoSelectAndLock5Ghz: Scanning for fresh 5 GHz candidates...")
            currentRadios = scanRadios(correlationId, freshForSsid = ssid)
        }

        val currentFlags = currentRadios.firstOrNull {
            it.bssid.equals(current.bssid, ignoreCase = true) && it.ssid == ssid
        }?.flags.orEmpty()
        val best5G = currentRadios
            .filter {
                it.ssid == ssid &&
                        (it.band == BandType.BAND_5_GHZ || it.band == BandType.BAND_6_GHZ) &&
                        it.rssi >= -80 && it.observedAtElapsedMillis > 0L && clock.nowMillis() - it.observedAtElapsedMillis in 0L..8_000L &&
                        (if (approvedBssid != null) {
                            WifiSecurityPolicy.allowsApprovedManualSwitch(
                                current.securityType, currentFlags, it.flags, approvedBssid, it.bssid
                            )
                        } else {
                            WifiSecurityPolicy.allowsTrustedAutomaticSwitch(
                                current.securityType, currentFlags, it.flags,
                                prefs.getTrustedRadioSecurity(ssid, it.bssid)
                            )
                        })
            }
            .maxByOrNull { it.rssi }

        if (best5G == null) {
            DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId result=aborted reason=no_eligible_5ghz_candidate ssid='$ssid' candidateCount=${currentRadios.size}")
            return WifiActionResult(TransitionResult.NoCandidate)
        }

        DiagnosticLogger.log(
            "WIFI",
            "id=$correlationId source=$requestSource autoSelectAndLock5Ghz: Selected 5 GHz candidate [${best5G.bssid}] (RSSI=${best5G.rssi} dBm, age=${best5G.ageSeconds}s)"
        )
        val latest = refreshStatus(forceFresh = true)
        if (!latest.isConnected || latest.ssid != current.ssid ||
            !latest.bssid.equals(current.bssid, ignoreCase = true) ||
            latest.securityType != current.securityType
        ) {
            DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId result=aborted reason=active_network_changed_during_scan")
            return WifiActionResult(TransitionResult.NetworkChanged)
        }
        val sec = detectSecurityFromFlags(best5G.flags, current.securityType)
        return lockToBssidInternal(
            ssid,
            best5G.bssid,
            passphrase,
            sec,
            macAddressPolicy,
            unpinProfileForRoaming = true,
            deferIfHealthy24Ghz = false,
            allowSwitchFromHealthy24Ghz = false,
            requestSource = requestSource,
            correlationId = correlationId,
            operationLockHeld = true, actionContext = actionContext ?: WifiActionContext.capture(current)
        )

    }

    private fun shellSecurity(security: String): String? = mapOf("0" to "open", "2" to "wpa2", "4" to "wpa3", "6" to "owe")[security]

    private fun detectSecurityType(ssid: String, targetBssid: String): String {
        if (targetBssid.isNotEmpty()) {
            val radio = _radios.value.firstOrNull { it.bssid.equals(targetBssid, ignoreCase = true) }
            if (radio != null) {
                return detectSecurityFromFlags(radio.flags)
            }
        }
        val curSec = _status.value.securityType
        if (curSec == "4") return "wpa3"
        if (curSec == "2") return "wpa2"
        return shellSecurity(curSec).orEmpty()
    }

    private fun detectSecurityFromFlags(flags: String, currentSecurityType: String = _status.value.securityType): String {
        val upper = flags.uppercase()
        val supportsSae = upper.contains("SAE")
        val supportsPsk = upper.contains("PSK")

        // A transition-mode AP advertises both PSK and SAE. Keep the mode Android is already
        // using for this SSID instead of silently replacing its saved profile with SAE-only.
        if (supportsSae && supportsPsk) {
            return when (currentSecurityType.lowercase()) {
                "4", "wpa3", "sae" -> "wpa3"
                "2", "wpa2", "psk" -> "wpa2"
                else -> "wpa3"
            }
        }

        return when {
            supportsSae -> "wpa3"
            supportsPsk -> "wpa2"
            upper.contains("OWE") -> "owe"
            upper.contains("WEP") -> "wep"
            WifiSecurityPolicy.fromFlags(flags).isOpen -> "open"
            else -> ""
        }
    }
}
