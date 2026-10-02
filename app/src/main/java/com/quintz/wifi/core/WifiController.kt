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
import com.quintz.wifi.model.WifiStatus
import com.quintz.wifi.shizuku.ShellResult
import com.quintz.wifi.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class WifiController(private val context: Context) {

    private val prefs = Preferences.get(context)
    private data class ProfileLockSnapshot(val ssid: String, val locked: Boolean, val bssid: String?, val observedAtMs: Long)
    private companion object {
        @Volatile var profileLockSnapshot: ProfileLockSnapshot? = null
    }

    @Volatile var lastPasswordStorageFailure: Boolean = false
        private set

    private val _status = MutableStateFlow(WifiStatus())
    val status: StateFlow<WifiStatus> = _status.asStateFlow()

    private val _radios = MutableStateFlow<List<AccessPointRadio>>(emptyList())
    val radios: StateFlow<List<AccessPointRadio>> = _radios.asStateFlow()

    val isOperating: StateFlow<Boolean> = WifiOperationCoordinator.isOperating

    val isScanning: StateFlow<Boolean> = WifiScanCoordinator.isScanning
    private val _isScanQueued = MutableStateFlow(false)
    val isScanQueued: StateFlow<Boolean> = _isScanQueued.asStateFlow()
    private val scanQueueGuard = Any()
    private var queuedScanCount = 0

    private fun changeQueuedScans(delta: Int) = synchronized(scanQueueGuard) {
        queuedScanCount += delta
        _isScanQueued.value = queuedScanCount > 0
    }

    fun getNativeWifiStatus(): WifiStatus {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return WifiStatus()
            val activeNetwork = cm.activeNetwork ?: return WifiStatus()
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return WifiStatus()
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return WifiStatus()
            }

            val linkProps = cm.getLinkProperties(activeNetwork)
            val ipAddress = linkProps?.linkAddresses
                ?.firstOrNull { it.address is java.net.Inet4Address }
                ?.address?.hostAddress.orEmpty()

            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val wmInfo = wm?.connectionInfo
            val capsWifiInfo = caps.transportInfo as? WifiInfo
            // WifiManager can retain permitted identity fields which transportInfo redacts.
            val wifiInfo = wmInfo?.takeIf {
                !it.bssid.isNullOrEmpty() && it.bssid != "02:00:00:00:00:00"
            } ?: capsWifiInfo ?: wmInfo

            val freq = wifiInfo?.frequency ?: 0
            val speed = wifiInfo?.linkSpeed ?: 0
            val rawSsid = wifiInfo?.ssid.orEmpty().trim('"')
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
                ipAddress = ipAddress,
                networkId = wifiInfo?.networkId?.takeIf { it >= 0 },
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
            DiagnosticLogger.log("WIFI_STATUS", "result=coalesced ageMs=${(System.currentTimeMillis() - status.observedAtMillis).coerceAtLeast(0L)}")
        }
        status
    }

    private suspend fun refreshStatusDirect(forceFresh: Boolean): WifiStatus = withContext(Dispatchers.IO) {
        val nativeStatus = getNativeWifiStatus()

        if (!ShizukuManager.isReady()) {
            profileLockSnapshot = null
            val observed = nativeStatus.copy(observedAtMillis = System.currentTimeMillis())
            _status.value = observed
            return@withContext observed
        }

        // 1. Primary shell query
        val statusResult = ShizukuManager.exec("cmd wifi status")
        var status = WifiParser.parseStatus(statusResult.stdout)

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
            if (status.ipAddress.isEmpty() && nativeStatus.ipAddress.isNotEmpty()) {
                status = status.copy(ipAddress = nativeStatus.ipAddress)
            }
            if (status.frequency == 0 && nativeStatus.frequency != 0) {
                status = status.copy(frequency = nativeStatus.frequency, band = BandType.fromFrequency(nativeStatus.frequency))
            }
            if ((status.rssi == 0 || status.rssi == -127) && nativeStatus.rssi != 0) {
                status = status.copy(rssi = nativeStatus.rssi)
            }
        }

        // 4. Determine if currently connected AP is locked in Android's saved network configuration
        val finalStatus = if (status.isConnected && status.ssid.isNotEmpty()) {
            val now = SystemClock.elapsedRealtime()
            val cachedLock = profileLockSnapshot?.takeIf {
                !forceFresh && it.ssid == status.ssid && now - it.observedAtMs in 0L..60_000L
            }
            val profileLock = cachedLock ?: run {
                val literalMatch = ShizukuManager.escapeShellArg("SSID: \"${status.ssid}\"")
                val lockCheck = ShizukuManager.exec("dumpsys wifi 2>/dev/null | grep -F $literalMatch")
                // Ignore mWifiInfo: only a saved profile line can prove a BSSID pin.
                val targetLine = lockCheck.stdout.lines().firstOrNull {
                    it.contains("PROVIDER-NAME:") && it.contains("SSID: \"${status.ssid}\"")
                } ?: ""
                val (locked, bssid) = WifiParser.parseLockedBssid(targetLine)
                ProfileLockSnapshot(status.ssid, locked, bssid, now).also {
                    profileLockSnapshot = if (lockCheck.isSuccess && targetLine.isNotEmpty()) it else null
                }
            }
            val isProfileLocked = profileLock.locked
            val lockedBssid = profileLock.bssid
            val isCurrentlyHardLocked = isProfileLocked && lockedBssid.equals(status.bssid, ignoreCase = true)
            val targetMode = prefs.getOrMigrateWifiTargetMode(status.ssid, if (isProfileLocked) lockedBssid else null)
            if (targetMode == WifiTargetMode.PIN_BSSID && isProfileLocked && !lockedBssid.isNullOrBlank()) {
                prefs.setWifiTargetMode(status.ssid, WifiTargetMode.PIN_BSSID, lockedBssid)
            }
            val has5GPreference = targetMode == WifiTargetMode.PREFER_5_GHZ && !isProfileLocked
            val isOn5G = status.band == BandType.BAND_5_GHZ || status.band == BandType.BAND_6_GHZ
            val isPreferred5G = has5GPreference && isOn5G
            val isPreferred5GFallback = has5GPreference && !isOn5G

            status.copy(
                isLockedToBssid = isCurrentlyHardLocked,
                lockedBssid = if (isProfileLocked) lockedBssid else null,
                isPreferred5GHz = isPreferred5G,
                isPreferred5GHzFallback = isPreferred5GFallback
            )
        } else {
            profileLockSnapshot = null
            status
        }

        val observedStatus = finalStatus.copy(observedAtMillis = System.currentTimeMillis())
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
        if (!ShizukuManager.isReady()) return@withContext emptyList()

        var lockAcquired = false
        changeQueuedScans(1)
        try {
            val (batch, coalesced) = WifiScanCoordinator.scan(freshForSsid, freshForBssid, onLockAcquired = {
                lockAcquired = true
                changeQueuedScans(-1)
            }) { previousAges ->
                scanRadiosDirect(correlationId, freshForSsid, freshForBssid, previousAges)
            }
            val currentBssid = _status.value.bssid
            val radios = batch.radios.map { radio ->
                radio.copy(isCurrent = currentBssid.isNotEmpty() && radio.bssid.equals(currentBssid, ignoreCase = true))
            }
            _radios.value = radios
            lastScanAttemptTimestamp = batch.completedAtMillis
            lastScanSucceeded = batch.succeeded
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
        val baselineResult = ShizukuManager.exec("cmd wifi list-scan-results", correlationId = correlationId)
        val baselineReadAt = System.currentTimeMillis()
        val baseline = if (baselineResult.isSuccess) {
            WifiParser.parseScanResults(baselineResult.stdout, _status.value.ssid, _status.value.bssid)
                .associate { it.bssid.lowercase() to (baselineReadAt - it.ageSeconds * 1000L) }
        } else {
            previousAgesByBssid.mapValues { baselineReadAt - it.value * 1000L }
        }
        val completion = WifiScanCompletion(context)
        try {
            val scanStartedAtMs = System.currentTimeMillis()
            val scanStartedElapsedMs = SystemClock.elapsedRealtime()
            val scanStart = ShizukuManager.exec("cmd wifi start-scan", correlationId = correlationId)
            if (!scanStart.isSuccess) {
                DiagnosticLogger.log(
                    "WIFI_SCAN",
                    "id=${correlationId ?: "none"} result=failed phase=start_scan durationMs=${SystemClock.elapsedRealtime() - scanStartedElapsedMs} exitCode=${scanStart.exitCode} stderr=${scanStart.stderr.take(160)}"
                )
                return@withContext WifiScanBatch(emptyList(), System.currentTimeMillis(), SystemClock.elapsedRealtime(), false, SystemClock.elapsedRealtime() - scanStartedElapsedMs)
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
                val scanResult = ShizukuManager.exec("cmd wifi list-scan-results", correlationId = correlationId)
                if (!scanResult.isSuccess) {
                    DiagnosticLogger.log(
                        "WIFI_SCAN",
                        "id=${correlationId ?: "none"} result=failed phase=list_scan_results durationMs=${SystemClock.elapsedRealtime() - scanStartedElapsedMs} exitCode=${scanResult.exitCode} stderr=${scanResult.stderr.take(160)}"
                    )
                    return@withContext WifiScanBatch(emptyList(), System.currentTimeMillis(), SystemClock.elapsedRealtime(), false, SystemClock.elapsedRealtime() - scanStartedElapsedMs)
                }
                val readAt = System.currentTimeMillis()
                list = WifiParser.parseScanResults(scanResult.stdout, _status.value.ssid, _status.value.bssid).map { radio ->
                    radio.copy(observedAtMillis = readAt - radio.ageSeconds * 1000L)
                }
                // A completion notification permits an empty successful scan. Targeted steering
                // still requires a new observation of the requested radio, not just a broadcast.
                refreshObserved = completionReported || hasNewScanObservation(list, baseline, scanStartedAtMs)
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
                    targetRefreshObserved = hasNewScanObservation(targets, baseline, scanStartedAtMs)
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
            val completedAtMs = System.currentTimeMillis()
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

    private suspend fun ensureProfileUnpinned(
        escapedSsid: String,
        escapedSec: String,
        passphrase: String,
        isOpen: Boolean,
        macFlag: String,
        ssid: String,
        correlationId: String
    ): Boolean {
        val unpinCmd = if (isOpen) {
            "cmd wifi add-network $escapedSsid $escapedSec $macFlag"
        } else if (passphrase.isNotEmpty()) {
            val escapedPass = ShizukuManager.escapeShellArg(passphrase)
            "cmd wifi add-network $escapedSsid $escapedSec $escapedPass $macFlag"
        } else null

        if (unpinCmd != null) {
            val unpinResult = ShizukuManager.exec(unpinCmd, correlationId = correlationId)
            val literalMatch = ShizukuManager.escapeShellArg("SSID: \"$ssid\"")
            val profileCheck = ShizukuManager.exec("dumpsys wifi 2>/dev/null | grep -F $literalMatch", correlationId = correlationId)
            val targetLine = profileCheck.stdout.lines().firstOrNull {
                it.contains("PROVIDER-NAME:") && it.contains("SSID: \"$ssid\"")
            } ?: ""
            val (isStillPinned, _) = WifiParser.parseLockedBssid(targetLine)
            val unpinVerified = unpinResult.isSuccess && !isStillPinned
            DiagnosticLogger.log(
                "WIFI",
                "id=$correlationId Dynamic steering unpin: cmdSuccess=${unpinResult.isSuccess}, isProfilePinned=$isStillPinned, unpinVerified=$unpinVerified"
            )
            return unpinVerified
        }
        return false
    }

    private suspend fun ensureProfilePinned(
        escapedSsid: String,
        escapedSec: String,
        passphrase: String,
        isOpen: Boolean,
        macFlag: String,
        ssid: String,
        bssid: String,
        correlationId: String
    ): Boolean {
        val escapedBssid = ShizukuManager.escapeShellArg(bssid)
        val command = if (isOpen) {
            "cmd wifi add-network $escapedSsid $escapedSec -b $escapedBssid $macFlag"
        } else if (passphrase.isNotEmpty()) {
            val escapedPass = ShizukuManager.escapeShellArg(passphrase)
            "cmd wifi add-network $escapedSsid $escapedSec $escapedPass -b $escapedBssid $macFlag"
        } else return false
        val addResult = ShizukuManager.exec(command, correlationId = correlationId)
        val literalMatch = ShizukuManager.escapeShellArg("SSID: \"$ssid\"")
        val profileCheck = ShizukuManager.exec("dumpsys wifi 2>/dev/null | grep -F $literalMatch", correlationId = correlationId)
        val targetLine = profileCheck.stdout.lines().firstOrNull {
            it.contains("PROVIDER-NAME:") && it.contains("SSID: \"$ssid\"")
        } ?: ""
        val (isPinned, pinnedBssid) = WifiParser.parseLockedBssid(targetLine)
        return addResult.isSuccess && isPinned && pinnedBssid.equals(bssid, ignoreCase = true)
    }

    suspend fun lockToBssid(
        ssid: String,
        bssid: String,
        passphrase: String,
        securityType: String = "",
        macAddressPolicy: com.quintz.wifi.model.MacAddressPolicy? = null,
        unpinProfileForRoaming: Boolean = false,
        deferIfHealthy24Ghz: Boolean = false,
        allowSwitchFromHealthy24Ghz: Boolean = false,
        requestSource: String = "unspecified",
        correlationId: String = DiagnosticLogger.newCorrelationId()
    ): Boolean = lockToBssidInternal(
        ssid, bssid, passphrase, securityType, macAddressPolicy, unpinProfileForRoaming,
        deferIfHealthy24Ghz, allowSwitchFromHealthy24Ghz, requestSource, correlationId,
        operationLockHeld = false
    )

    private suspend fun lockToBssidInternal(
        ssid: String,
        bssid: String,
        passphrase: String,
        securityType: String,
        macAddressPolicy: com.quintz.wifi.model.MacAddressPolicy?,
        unpinProfileForRoaming: Boolean,
        deferIfHealthy24Ghz: Boolean,
        allowSwitchFromHealthy24Ghz: Boolean,
        requestSource: String,
        correlationId: String,
        operationLockHeld: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        if (!operationLockHeld && !WifiOperationCoordinator.tryBegin()) {
            DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId result=aborted reason=profile_operation_in_progress")
            return@withContext false
        }
        try {
        if (!operationLockHeld) lastPasswordStorageFailure = false
        val preRequestStatus = refreshStatus(forceFresh = true)
        DiagnosticLogger.log(
            "WIFI_ACTION",
            "id=$correlationId source=$requestSource action=lock_to_bssid requestedSsid='$ssid' targetBssid=$bssid connected=${preRequestStatus.isConnected} currentSsid='${preRequestStatus.ssid}' currentBssid=${preRequestStatus.bssid} band=${preRequestStatus.band.displayName} rssi=${preRequestStatus.rssi} deferIfHealthy24Ghz=$deferIfHealthy24Ghz"
        )
        if (!ShizukuManager.isReady()) {
            DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId result=aborted reason=shizuku_not_ready")
            return@withContext false
        }

        val policy = macAddressPolicy ?: prefs.getMacPolicy(ssid) ?: prefs.defaultMacPolicy

        DiagnosticLogger.log(
            "WIFI",
            "id=$correlationId source=$requestSource Lock request: SSID='$ssid', BSSID=$bssid, sec='$securityType', MAC=${policy.displayName} (-r ${policy.shellFlagValue}), unpinForRoaming=$unpinProfileForRoaming, deferIfHealthy24Ghz=$deferIfHealthy24Ghz"
        )
        var profileWasForgotten = false
        var operationVerified = false
        var escapedSsid = ""
        var escapedSec = ""
        var macFlag = ""
        var sec = "wpa2"
        var isOpen = false
        val previousMode = prefs.getOrMigrateWifiTargetMode(
            ssid,
            preRequestStatus.lockedBssid.takeIf { preRequestStatus.isLockedToBssid }
        )
        val previousPinnedBssid = prefs.getPinnedBssid(ssid)
            ?: preRequestStatus.lockedBssid?.takeIf { preRequestStatus.isLockedToBssid }
        try {
            val targetRadio = scanRadios(correlationId, freshForSsid = ssid, freshForBssid = bssid)
                .firstOrNull { it.ssid == ssid && it.bssid.equals(bssid, ignoreCase = true) && it.ageSeconds <= 8L }
            if (targetRadio == null || !WifiSecurityPolicy.isSupported(targetRadio.flags)) {
                DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId result=aborted reason=target_security_unknown_or_stale")
                return@withContext false
            }
            sec = if (securityType.isNotEmpty()) securityType else detectSecurityType(ssid, bssid)
            val expectedSecurity = when (sec) {
                "open" -> "0"
                "wpa2" -> "2"
                "wpa3" -> "4"
                "owe" -> "6"
                else -> null
            }
            if (!WifiSecurityPolicy.matchesSecurityType(expectedSecurity, targetRadio.flags)) {
                DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId result=aborted reason=target_security_mismatch")
                return@withContext false
            }
            if (preRequestStatus.isConnected && preRequestStatus.ssid == ssid &&
                !WifiSecurityPolicy.allowsSameSsidSelection(preRequestStatus.securityType, expectedSecurity)
            ) {
                DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId result=aborted reason=same_ssid_security_change")
                return@withContext false
            }
            isOpen = sec == "open" || sec == "owe"

            if (!isOpen && passphrase.isEmpty()) {
                DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId source=$requestSource result=aborted reason=password_required ssid='$ssid'")
                return@withContext false
            }

            if (!isOpen && passphrase.isNotEmpty()) {
                if (!prefs.savePassword(ssid, passphrase)) {
                    lastPasswordStorageFailure = true
                    DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId result=aborted reason=secure_password_storage_unavailable")
                    return@withContext false
                }
            }
            prefs.isWatchdogFallbackActive = false

            escapedSsid = ShizukuManager.escapeShellArg(ssid)
            val escapedBssid = ShizukuManager.escapeShellArg(bssid)
            escapedSec = ShizukuManager.escapeShellArg(sec)
            macFlag = "-r ${policy.shellFlagValue}"

            // PRE-REQUEST SAFETY CHECK:
            // If already connected on the same SSID (e.g. 2.4 GHz):
            val preCheckStatus = refreshStatus(forceFresh = true)
            if (preCheckStatus.isConnected && preCheckStatus.ssid == ssid) {
                // If already on the target BSSID, skip connect-network entirely!
                if (preCheckStatus.bssid.equals(bssid, ignoreCase = true)) {
                    DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId source=$requestSource result=noop reason=already_on_target_bssid targetBssid=$bssid")
                    val profileVerified = if (unpinProfileForRoaming) {
                        ensureProfileUnpinned(escapedSsid, escapedSec, passphrase, isOpen, macFlag, ssid, correlationId)
                    } else {
                        ensureProfilePinned(escapedSsid, escapedSec, passphrase, isOpen, macFlag, ssid, bssid, correlationId)
                    }
                    refreshStatus(forceFresh = true)
                    val ok = profileVerified && _status.value.isConnected && _status.value.ssid == ssid && _status.value.bssid.equals(bssid, ignoreCase = true)
                    if (ok) {
                        val mode = if (unpinProfileForRoaming) WifiTargetMode.PREFER_5_GHZ else WifiTargetMode.PIN_BSSID
                        prefs.setWifiTargetMode(ssid, mode, bssid)
                        prefs.setMacPolicy(ssid, policy)
                        operationVerified = true
                        refreshStatus(forceFresh = true)
                    }
                    return@withContext ok
                }

                // Automated recovery normally preserves a healthy 2.4 GHz link; preferred-band recovery can explicitly override that hold-off.
                if (deferIfHealthy24Ghz && !allowSwitchFromHealthy24Ghz &&
                    preCheckStatus.band == BandType.BAND_2_4_GHZ &&
                    preCheckStatus.rssi >= com.quintz.wifi.model.HEALTHY_24G_THRESHOLD_RSSI
                ) {
                    DiagnosticLogger.log(
                        "WIFI_ACTION",
                        "id=$correlationId source=$requestSource result=deferred reason=healthy_24ghz_link targetBssid=$bssid currentBssid=${preCheckStatus.bssid} currentRssi=${preCheckStatus.rssi} threshold=${com.quintz.wifi.model.HEALTHY_24G_THRESHOLD_RSSI}"
                    )
                    return@withContext false
                }

                // Automated background routine safety abort: ensure candidate is fresh and meets recoveryThresholdRssi
                if (deferIfHealthy24Ghz) {
                    val targetCandidate = _radios.value.firstOrNull { it.bssid.equals(bssid, ignoreCase = true) }
                    if (targetCandidate == null || targetCandidate.ageSeconds > 8L || targetCandidate.rssi < prefs.recoveryThresholdRssi) {
                        DiagnosticLogger.log(
                            "WIFI_ACTION",
                            "id=$correlationId source=$requestSource result=aborted reason=target_not_fresh_or_strong targetBssid=$bssid found=${targetCandidate != null} age=${targetCandidate?.ageSeconds}s rssi=${targetCandidate?.rssi}dBm currentBssid=${preCheckStatus.bssid}"
                        )
                        return@withContext false
                    }
                } else {
                    // For manual user requests (deferIfHealthy24Ghz = false), confirm target AP is available in scan cache
                    var targetCandidate = _radios.value.firstOrNull { it.bssid.equals(bssid, ignoreCase = true) }
                    if (targetCandidate == null || targetCandidate.ageSeconds > 15L) {
                        scanRadios(correlationId)
                        targetCandidate = _radios.value.firstOrNull { it.bssid.equals(bssid, ignoreCase = true) }
                    }
                    if (targetCandidate == null) {
                        DiagnosticLogger.log(
                            "WIFI_ACTION",
                            "id=$correlationId source=$requestSource result=aborted reason=target_not_found targetBssid=$bssid ssid='$ssid'"
                        )
                        return@withContext false
                    }
                }
            }

            // Android's connect-network treats an already active saved network as a no-op, even
            // when the requested BSSID differs. Remove that profile only for this explicit BSSID
            // transition so the following connect-network creates a fresh user-selected config.
            // This causes an association handoff, but never toggles the Wi-Fi radio.
            val alreadyOnSameSsid = preCheckStatus.isConnected &&
                    preCheckStatus.ssid == ssid &&
                    !preCheckStatus.bssid.equals(bssid, ignoreCase = true)
            if (alreadyOnSameSsid) {
                val listNetworks = ShizukuManager.exec("cmd wifi list-networks", correlationId = correlationId)
                val netId = if (listNetworks.isSuccess) {
                    WifiParser.parseNetworkId(listNetworks.stdout, ssid, preCheckStatus.networkId, preCheckStatus.securityType)
                } else null
                val currentBeforeForget = refreshStatus(forceFresh = true)
                val sameActiveProfile = currentBeforeForget.isConnected && currentBeforeForget.ssid == ssid &&
                    currentBeforeForget.networkId == netId && currentBeforeForget.securityType == preCheckStatus.securityType &&
                    currentBeforeForget.bssid.equals(preCheckStatus.bssid, ignoreCase = true)
                if (netId != null && sameActiveProfile) {
                    DiagnosticLogger.log(
                        "WIFI",
                        "id=$correlationId source=$requestSource Replacing saved network ID $netId to force requested BSSID transition ${preCheckStatus.bssid} -> $bssid; Wi-Fi radio remains enabled."
                    )
                    val forgetResult = ShizukuManager.exec("cmd wifi forget-network $netId", correlationId = correlationId)
                    if (!forgetResult.isSuccess) {
                        DiagnosticLogger.log(
                            "WIFI_ACTION",
                            "id=$correlationId source=$requestSource result=aborted reason=forget_network_failed netId=$netId stderr=${forgetResult.stderr}"
                        )
                        return@withContext false
                    }
                    profileWasForgotten = true
                } else {
                    DiagnosticLogger.log(
                        "WIFI_ACTION",
                        "id=$correlationId source=$requestSource result=aborted reason=active_network_id_not_found; refusing to issue a BSSID request that Android would treat as a no-op."
                    )
                    return@withContext false
                }
            }

            val cmd = if (isOpen) {
                "cmd wifi connect-network $escapedSsid $escapedSec -b $escapedBssid $macFlag"
            } else {
                val escapedPass = ShizukuManager.escapeShellArg(passphrase)
                "cmd wifi connect-network $escapedSsid $escapedSec $escapedPass -b $escapedBssid $macFlag"
            }
            val result = ShizukuManager.exec(cmd, correlationId = correlationId)
            if (!result.isSuccess) {
                // If the active same-SSID profile was forgotten to force a BSSID transition,
                // put a usable roaming profile back even when connect-network itself fails.
                val profileRestored = if (alreadyOnSameSsid) {
                    ensureProfileUnpinned(escapedSsid, escapedSec, passphrase, isOpen, macFlag, ssid, correlationId)
                } else {
                    null
                }
                refreshStatus(forceFresh = true)
                DiagnosticLogger.log(
                    "WIFI_ACTION",
                    "id=$correlationId source=$requestSource result=failed reason=connect_network_failed exitCode=${result.exitCode} sameSsidHandoff=$alreadyOnSameSsid profileRestored=$profileRestored currentSsid='${_status.value.ssid}' currentBssid=${_status.value.bssid} band=${_status.value.band.displayName}"
                )
                return@withContext false
            }

            // Wait for in-place re-association to target BSSID without ever cycling Wi-Fi interface off/on
            val settled = awaitConnectionSettled(targetBssid = bssid, maxWaitMs = 8000L)

            // If the framework did not bind to target BSSID, verify if we are still connected to the same SSID (e.g. 2.4 GHz)
            if (!settled.isConnected || !settled.bssid.equals(bssid, ignoreCase = true)) {
                refreshStatus(forceFresh = true)
                val current = _status.value
                if (current.isConnected && current.ssid == ssid) {
                    // connect-network may have updated the saved profile's BSSID even when Android
                    // kept the existing association. Restore an unpinned profile so a failed
                    // transition cannot strand the user on a mismatched BSSID.
                    val profileRestored = ensureProfileUnpinned(
                        escapedSsid, escapedSec, passphrase, isOpen, macFlag, ssid, correlationId
                    )
                    DiagnosticLogger.log(
                        "WIFI_ACTION",
                        "id=$correlationId source=$requestSource result=not_verified reason=target_not_active targetBssid=$bssid currentBssid=${current.bssid} currentBand=${current.band.displayName} currentRssi=${current.rssi} profileRestored=$profileRestored"
                    )
                    return@withContext false
                }
            }

            refreshStatus(forceFresh = true)
            scanRadios(correlationId)
            val activeMatchesTarget = _status.value.isConnected && _status.value.ssid == ssid && _status.value.bssid.equals(bssid, ignoreCase = true)

            val isVerified = if (unpinProfileForRoaming) {
                // PREFERRED 5 GHz (Dynamic steering with roaming allowed):
                // Once bound to target BSSID, unpin the saved profile in Android (bssid = any).
                // Also restore the roaming profile if the target was never reached (including a
                // failed WPA3/SAE association), so retries do not inherit a stale BSSID pin.
                val unpinVerified = ensureProfileUnpinned(
                    escapedSsid, escapedSec, passphrase, isOpen, macFlag, ssid, correlationId
                )
                refreshStatus(forceFresh = true)
                activeMatchesTarget && unpinVerified
            } else {
                // SPECIFIC RADIO LOCK (Hard BSSID Pin):
                // Do NOT unpin the profile! Verify that the profile IS locked to target BSSID in dumpsys wifi.
                val literalMatch = ShizukuManager.escapeShellArg("SSID: \"$ssid\"")
                val profileCheck = ShizukuManager.exec("dumpsys wifi 2>/dev/null | grep -F $literalMatch", correlationId = correlationId)
                val targetLine = profileCheck.stdout.lines().firstOrNull {
                    it.contains("PROVIDER-NAME:") && it.contains("SSID: \"$ssid\"")
                } ?: ""
                val (isProfileLocked, lockedBssid) = WifiParser.parseLockedBssid(targetLine)
                val isHardPinVerified = isProfileLocked && lockedBssid.equals(bssid, ignoreCase = true)
                DiagnosticLogger.log(
                    "WIFI_ACTION",
                    "id=$correlationId source=$requestSource action=specific_ap_lock_check activeMatches=$activeMatchesTarget hardPinVerified=$isHardPinVerified lockedBssid=$lockedBssid"
                )
                refreshStatus(forceFresh = true)
                activeMatchesTarget && isHardPinVerified
            }

            DiagnosticLogger.log(
                "WIFI_ACTION",
                "id=$correlationId source=$requestSource result=${if (isVerified) "success" else "not_verified"} action=lock_to_bssid unpinForRoaming=$unpinProfileForRoaming connected=${_status.value.isConnected} currentBssid=${_status.value.bssid} band=${_status.value.band.displayName} rssi=${_status.value.rssi} locked=${_status.value.isLockedToBssid} preferred5G=${_status.value.isPreferred5GHz}"
            )
            if (isVerified) {
                val mode = if (unpinProfileForRoaming) WifiTargetMode.PREFER_5_GHZ else WifiTargetMode.PIN_BSSID
                prefs.setWifiTargetMode(ssid, mode, bssid)
                prefs.setMacPolicy(ssid, policy)
                operationVerified = true
                refreshStatus(forceFresh = true)
            }
            isVerified
        } finally {
            try {
                if (profileWasForgotten && !operationVerified) {
                    val restored = if (previousMode == WifiTargetMode.PIN_BSSID && !previousPinnedBssid.isNullOrBlank()) {
                        ensureProfilePinned(escapedSsid, escapedSec, passphrase, isOpen, macFlag, ssid, previousPinnedBssid, correlationId)
                    } else {
                        ensureProfileUnpinned(escapedSsid, escapedSec, passphrase, isOpen, macFlag, ssid, correlationId)
                    }
                    DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId result=rollback profileRestored=$restored previousMode=$previousMode previousPinnedBssid=${previousPinnedBssid.orEmpty()}")
                }
            } catch (e: Exception) {
                DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId result=rollback_failed error=${e.javaClass.simpleName}")
            }
        }
        } finally {
            if (!operationLockHeld) WifiOperationCoordinator.end()
        }
    }

    suspend fun unlockToAuto(
        ssid: String,
        passphrase: String? = null,
        securityType: String = "",
        macAddressPolicy: com.quintz.wifi.model.MacAddressPolicy? = null,
        isAutomatedFallback: Boolean = false,
        preserveTargetMode: Boolean = false,
        requestSource: String = "unspecified",
        correlationId: String = DiagnosticLogger.newCorrelationId()
    ): Boolean = withContext(Dispatchers.IO) {
        if (!WifiOperationCoordinator.tryBegin()) {
            DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId result=aborted reason=profile_operation_in_progress")
            return@withContext false
        }
        try {
        lastPasswordStorageFailure = false
        val initialStatus = _status.value
        DiagnosticLogger.log(
            "WIFI_ACTION",
            "id=$correlationId source=$requestSource action=unlock_to_auto ssid='$ssid' automatedFallback=$isAutomatedFallback connected=${initialStatus.isConnected} currentSsid='${initialStatus.ssid}' currentBssid=${initialStatus.bssid} band=${initialStatus.band.displayName} rssi=${initialStatus.rssi}"
        )
        if (!ShizukuManager.isReady()) {
            DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId result=aborted reason=shizuku_not_ready")
            return@withContext false
        }

        val policy = macAddressPolicy ?: prefs.getMacPolicy(ssid) ?: prefs.defaultMacPolicy
        DiagnosticLogger.log("WIFI", "id=$correlationId source=$requestSource Unlock request (Auto-Roam): SSID='$ssid', MAC=${policy.displayName}, isFallback=$isAutomatedFallback")
            val pass = passphrase ?: prefs.getPassword(ssid).orEmpty()
            val sec = if (securityType.isNotEmpty()) securityType else detectSecurityType(ssid, "")
            val isOpen = sec == "open" || sec == "owe"

            if (isAutomatedFallback) {
                // Automated watchdog fallback: keep target band as 5GHz so watchdog continues recovery scanning
                prefs.isWatchdogFallbackActive = true
            } else {
                prefs.isWatchdogFallbackActive = false
            }

            val escapedSsid = ShizukuManager.escapeShellArg(ssid)
            val escapedSec = ShizukuManager.escapeShellArg(sec)
            val macFlag = "-r ${policy.shellFlagValue}"

            // The watchdog may be handling an onLost callback for the old BSSID while Android
            // has already completed its same-SSID roam. Refresh before deciding to reconnect;
            // otherwise stale cached state can cause an unnecessary disconnect/reassociation.
            val currentStatus = refreshStatus(forceFresh = true)
            val alreadyConnectedToSsid = currentStatus.isConnected && currentStatus.ssid == ssid

            if (isAutomatedFallback && currentStatus.isConnected && !alreadyConnectedToSsid) {
                DiagnosticLogger.log(
                    "WIFI",
                    "Automated fallback: preserving active Wi-Fi connection (SSID unresolved or changed to '${currentStatus.ssid}'); skipping reconnect to '$ssid'."
                )
                return@withContext true
            }

            val result = if (isAutomatedFallback && alreadyConnectedToSsid) {
                // Tablet is ALREADY connected to the target SSID (e.g. 2.4 GHz).
                // Crucially do NOT call connect-network! Only ensure the saved profile is unpinned so firmware roams naturally.
                DiagnosticLogger.log(
                    "WIFI",
                    "Automated fallback: Already connected to '$ssid' on ${currentStatus.band.displayName} (${currentStatus.rssi} dBm). Unpinning profile without forcing reconnect."
                )
                if (isOpen) {
                    ShizukuManager.exec("cmd wifi add-network $escapedSsid $escapedSec $macFlag", correlationId = correlationId)
                } else if (pass.isNotEmpty()) {
                    val escapedPass = ShizukuManager.escapeShellArg(pass)
                    ShizukuManager.exec("cmd wifi add-network $escapedSsid $escapedSec $escapedPass $macFlag", correlationId = correlationId)
                } else {
                    ShellResult(0, "", "")
                }
            } else if (isOpen) {
                val addResult = ShizukuManager.exec("cmd wifi add-network $escapedSsid $escapedSec $macFlag", correlationId = correlationId)
                if (addResult.isSuccess) ShizukuManager.exec("cmd wifi connect-network $escapedSsid $escapedSec $macFlag", correlationId = correlationId) else addResult
            } else if (pass.isNotEmpty()) {
                val escapedPass = ShizukuManager.escapeShellArg(pass)
                val addResult = ShizukuManager.exec("cmd wifi add-network $escapedSsid $escapedSec $escapedPass $macFlag", correlationId = correlationId)
                if (addResult.isSuccess) ShizukuManager.exec("cmd wifi connect-network $escapedSsid $escapedSec $escapedPass $macFlag", correlationId = correlationId) else addResult
            } else {
                DiagnosticLogger.log("WIFI", "Unlock: no saved password available for secured SSID='$ssid'. Skipping command to preserve saved configuration.")
                return@withContext false
            }

            // Verify saved profile in dumpsys wifi is unpinned
            val literalMatch = ShizukuManager.escapeShellArg("SSID: \"$ssid\"")
            val profileCheck = ShizukuManager.exec("dumpsys wifi 2>/dev/null | grep -F $literalMatch", correlationId = correlationId)
            val targetLine = profileCheck.stdout.lines().firstOrNull {
                it.contains("PROVIDER-NAME:") && it.contains("SSID: \"$ssid\"")
            } ?: ""
            val (isStillPinned, _) = WifiParser.parseLockedBssid(targetLine)
            DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId source=$requestSource action=unlock_profile_check cmdSuccess=${result.isSuccess} isProfilePinned=$isStillPinned")

            if (!alreadyConnectedToSsid) {
                awaitConnectionSettled(targetBssid = null, maxWaitMs = 5000L)
            }
            refreshStatus(forceFresh = true)
            scanRadios(correlationId)
            val verified = result.isSuccess && targetLine.isNotEmpty() && !isStillPinned &&
                (isAutomatedFallback || (_status.value.isConnected && _status.value.ssid == ssid))
            if (verified && !isAutomatedFallback && !preserveTargetMode) {
                prefs.setWifiTargetMode(ssid, WifiTargetMode.AUTO)
                refreshStatus(forceFresh = true)
            }
            DiagnosticLogger.log(
                "WIFI_ACTION",
                "id=$correlationId source=$requestSource result=$verified action=unlock_to_auto connected=${_status.value.isConnected} currentBssid=${_status.value.bssid} band=${_status.value.band.displayName} rssi=${_status.value.rssi} locked=${_status.value.isLockedToBssid} preferred5G=${_status.value.isPreferred5GHz}"
            )
            verified
        } finally {
            WifiOperationCoordinator.end()
        }
    }

    private suspend fun awaitConnectionSettled(
        targetBssid: String? = null,
        maxWaitMs: Long = 12000L
    ): WifiStatus {
        val startTime = System.currentTimeMillis()
        var latestStatus = _status.value

        // If switching from an existing connected BSSID to a different target BSSID,
        // wait for the old connection to drop first so we don't prematurely sample stale state.
        if (targetBssid != null && latestStatus.bssid.isNotEmpty() && !latestStatus.bssid.equals(targetBssid, ignoreCase = true)) {
            val oldBssid = latestStatus.bssid
            val disconnectStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - disconnectStart < 4000L) {
                kotlinx.coroutines.delay(350)
                latestStatus = refreshStatus()
                if (!latestStatus.isConnected || !latestStatus.bssid.equals(oldBssid, ignoreCase = true)) {
                    break
                }
            }
        } else {
            kotlinx.coroutines.delay(1000)
        }

        // Wait until connection settles with target BSSID and valid DHCP IP
        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            latestStatus = refreshStatus()
            val targetMatched = if (targetBssid != null) {
                latestStatus.bssid.equals(targetBssid, ignoreCase = true)
            } else {
                latestStatus.isConnected
            }

            if (latestStatus.isConnected && targetMatched && latestStatus.ipAddress.isNotEmpty() && latestStatus.ipAddress != "0.0.0.0") {
                kotlinx.coroutines.delay(300)
                return refreshStatus(forceFresh = true)
            }
            kotlinx.coroutines.delay(400)
        }
        return latestStatus
    }

    suspend fun autoSelectAndLock5Ghz(
        ssid: String,
        passphrase: String,
        macAddressPolicy: com.quintz.wifi.model.MacAddressPolicy? = null,
        approvedBssid: String? = null,
        requestSource: String = "unspecified",
        correlationId: String = DiagnosticLogger.newCorrelationId()
    ): Boolean {
        if (!WifiOperationCoordinator.tryBegin()) {
            DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId result=aborted reason=profile_operation_in_progress")
            return false
        }
        try {
        lastPasswordStorageFailure = false
        val current = refreshStatus(forceFresh = true)
        if (!current.isConnected || current.ssid != ssid) {
            DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId result=aborted reason=active_network_changed")
            return false
        }
        DiagnosticLogger.log(
            "WIFI_ACTION",
            "id=$correlationId source=$requestSource action=prefer_5ghz_start ssid='$ssid' connected=${current.isConnected} currentBssid=${current.bssid} band=${current.band.displayName} rssi=${current.rssi} cachedRadios=${_radios.value.size}"
        )
        var currentRadios = _radios.value
        val hasFresh5G = currentRadios.any {
            it.ssid == ssid &&
                    (it.band == BandType.BAND_5_GHZ || it.band == BandType.BAND_6_GHZ) &&
                    it.ageSeconds <= 8L
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
                        it.rssi >= -80 && it.ageSeconds <= 8L &&
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
            return false
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
            return false
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
            operationLockHeld = true
        )
        } finally {
            WifiOperationCoordinator.end()
        }
    }

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
        return "wpa2"
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
            else -> "open"
        }
    }
}
