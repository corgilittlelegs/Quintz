package com.quintz.wifi.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.quintz.wifi.data.Preferences
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

    private val prefs = Preferences(context)

    private val _status = MutableStateFlow(WifiStatus())
    val status: StateFlow<WifiStatus> = _status.asStateFlow()

    private val _radios = MutableStateFlow<List<AccessPointRadio>>(emptyList())
    val radios: StateFlow<List<AccessPointRadio>> = _radios.asStateFlow()

    private val _isOperating = MutableStateFlow(false)
    val isOperating: StateFlow<Boolean> = _isOperating.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

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
            val wifiInfo = capsWifiInfo ?: wmInfo

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
                ipAddress = ipAddress
            )
        } catch (_: Exception) {
            return WifiStatus()
        }
    }

    suspend fun refreshStatus(): WifiStatus = withContext(Dispatchers.IO) {
        val nativeStatus = getNativeWifiStatus()

        if (!ShizukuManager.isReady()) {
            _status.value = nativeStatus
            return@withContext nativeStatus
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
                ipAddress = if (nativeStatus.ipAddress.isNotEmpty()) nativeStatus.ipAddress else status.ipAddress
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
            if (status.ssid != resolvedSsid || status.bssid != resolvedBssid) {
                status = status.copy(ssid = resolvedSsid, bssid = resolvedBssid)
            }
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
            val literalMatch = ShizukuManager.escapeShellArg("SSID: \"${status.ssid}\"")
            val lockCheck = ShizukuManager.exec("dumpsys wifi 2>/dev/null | grep -F $literalMatch")
            // Saved network configurations in dumpsys wifi always contain "PROVIDER-NAME:".
            // Crucially ignore lines like "mWifiInfo" which represent active connection telemetry, not configuration locks.
            val targetLine = lockCheck.stdout.lines().firstOrNull {
                it.contains("PROVIDER-NAME:") && it.contains("SSID: \"${status.ssid}\"")
            } ?: ""
            val (isProfileLocked, lockedBssid) = WifiParser.parseLockedBssid(targetLine)
            val isCurrentlyHardLocked = isProfileLocked && lockedBssid.equals(status.bssid, ignoreCase = true)
            val has5GPreference = !isProfileLocked && prefs.lastTargetBand == "5GHz"
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
            status
        }

        val oldStatus = _status.value
        _status.value = finalStatus
        if (oldStatus.bssid != finalStatus.bssid || oldStatus.isConnected != finalStatus.isConnected ||
            oldStatus.isLockedToBssid != finalStatus.isLockedToBssid || oldStatus.isPreferred5GHz != finalStatus.isPreferred5GHz ||
            oldStatus.isPreferred5GHzFallback != finalStatus.isPreferred5GHzFallback
        ) {
            DiagnosticLogger.log(
                "WIFI",
                "State changed: connected=${finalStatus.isConnected}, ssid='${finalStatus.ssid}', bssid=${finalStatus.bssid}, band=${finalStatus.band.displayName}, rssi=${finalStatus.rssi}dBm, locked=${finalStatus.isLockedToBssid}, preferred5G=${finalStatus.isPreferred5GHz}, fallback5G=${finalStatus.isPreferred5GHzFallback}"
            )
        }
        requestTileUpdate()
        finalStatus
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

    suspend fun scanRadios(
        correlationId: String? = null,
        freshForSsid: String? = null
    ): List<AccessPointRadio> = withContext(Dispatchers.IO) {
        if (!ShizukuManager.isReady()) return@withContext emptyList()

        _isScanning.value = true
        try {
            val previousAgesByBssid = _radios.value.associate { it.bssid.lowercase() to it.ageSeconds }
            val scanStartedAtMs = System.currentTimeMillis()
            val scanStart = ShizukuManager.exec("cmd wifi start-scan", correlationId = correlationId)
            if (!scanStart.isSuccess) {
                DiagnosticLogger.log(
                    "WIFI_SCAN",
                    "id=${correlationId ?: "none"} result=failed phase=start_scan exitCode=${scanStart.exitCode} stderr=${scanStart.stderr.take(160)}"
                )
                return@withContext emptyList()
            }

            // start-scan can return success while Android still exposes its previous results.
            // For watchdog recovery, poll until the target SSID has a fresh 5/6 GHz observation
            // or the bounded wait expires; age advancing naturally does not count as a refresh.
            val refreshDeadlineMs = scanStartedAtMs + if (freshForSsid != null) 12000L else 2500L
            var list = emptyList<AccessPointRadio>()
            var targetRefreshObserved = freshForSsid == null
            var firstRead = true
            while (true) {
                val waitMs = if (firstRead) 2500L else 1000L
                val remainingMs = refreshDeadlineMs - System.currentTimeMillis()
                if (remainingMs > 0L) kotlinx.coroutines.delay(minOf(waitMs, remainingMs))
                firstRead = false

                val scanResult = ShizukuManager.exec("cmd wifi list-scan-results", correlationId = correlationId)
                if (!scanResult.isSuccess) {
                    DiagnosticLogger.log(
                        "WIFI_SCAN",
                        "id=${correlationId ?: "none"} result=failed phase=list_scan_results exitCode=${scanResult.exitCode} stderr=${scanResult.stderr.take(160)}"
                    )
                    return@withContext emptyList()
                }

                val currentSsid = _status.value.ssid
                val currentBssid = _status.value.bssid
                list = WifiParser.parseScanResults(scanResult.stdout, currentSsid, currentBssid)

                if (freshForSsid != null) {
                    val elapsedSeconds = ((System.currentTimeMillis() - scanStartedAtMs) / 1000L).coerceAtLeast(0L)
                    targetRefreshObserved = list.any { radio ->
                        val isTargetBand = radio.band == BandType.BAND_5_GHZ || radio.band == BandType.BAND_6_GHZ
                        if (!radio.ssid.equals(freshForSsid, ignoreCase = true) || !isTargetBand || radio.ageSeconds > 8L) {
                            false
                        } else {
                            val previousAge = previousAgesByBssid[radio.bssid.lowercase()]
                            previousAge == null || radio.ageSeconds < previousAge + elapsedSeconds
                        }
                    }
                }

                val nowMs = System.currentTimeMillis()
                if (targetRefreshObserved || nowMs >= refreshDeadlineMs) break
            }

            val usableList = if (freshForSsid != null && !targetRefreshObserved) {
                DiagnosticLogger.log(
                    "WIFI_SCAN",
                    "id=${correlationId ?: "none"} result=target_not_refreshed targetSsid='$freshForSsid' action=exclude_target_5ghz_candidates"
                )
                list.filterNot {
                    it.ssid.equals(freshForSsid, ignoreCase = true) &&
                            (it.band == BandType.BAND_5_GHZ || it.band == BandType.BAND_6_GHZ)
                }
            } else {
                list
            }

            _radios.value = usableList
            lastScanCompletedTimestamp = System.currentTimeMillis()
            DiagnosticLogger.log(
                "WIFI_SCAN",
                "id=${correlationId ?: "none"} result=completed radios=${usableList.size} targetSsid='${freshForSsid.orEmpty()}' targetRefreshObserved=$targetRefreshObserved waitedMs=${lastScanCompletedTimestamp - scanStartedAtMs} completedAtMs=$lastScanCompletedTimestamp"
            )
            usableList
        } finally {
            _isScanning.value = false
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
    ): Boolean = withContext(Dispatchers.IO) {
        val preRequestStatus = _status.value
        DiagnosticLogger.log(
            "WIFI_ACTION",
            "id=$correlationId source=$requestSource action=lock_to_bssid requestedSsid='$ssid' targetBssid=$bssid connected=${preRequestStatus.isConnected} currentSsid='${preRequestStatus.ssid}' currentBssid=${preRequestStatus.bssid} band=${preRequestStatus.band.displayName} rssi=${preRequestStatus.rssi} deferIfHealthy24Ghz=$deferIfHealthy24Ghz"
        )
        if (!ShizukuManager.isReady()) {
            DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId result=aborted reason=shizuku_not_ready")
            return@withContext false
        }

        val policy = macAddressPolicy ?: prefs.getMacPolicy(ssid) ?: prefs.defaultMacPolicy
        prefs.setMacPolicy(ssid, policy)

        _isOperating.value = true
        DiagnosticLogger.log(
            "WIFI",
            "id=$correlationId source=$requestSource Lock request: SSID='$ssid', BSSID=$bssid, sec='$securityType', MAC=${policy.displayName} (-r ${policy.shellFlagValue}), unpinForRoaming=$unpinProfileForRoaming, deferIfHealthy24Ghz=$deferIfHealthy24Ghz"
        )
        try {
            val sec = if (securityType.isNotEmpty()) securityType else detectSecurityType(ssid, bssid)
            val isOpen = sec == "open" || sec == "owe"

            if (!isOpen && passphrase.isEmpty()) {
                DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId source=$requestSource result=aborted reason=password_required ssid='$ssid'")
                return@withContext false
            }

            if (!isOpen && passphrase.isNotEmpty()) {
                prefs.savePassword(ssid, passphrase)
            }
            if (unpinProfileForRoaming) {
                prefs.lastTargetBand = "5GHz"
            }
            prefs.isWatchdogFallbackActive = false

            val escapedSsid = ShizukuManager.escapeShellArg(ssid)
            val escapedBssid = ShizukuManager.escapeShellArg(bssid)
            val escapedSec = ShizukuManager.escapeShellArg(sec)
            val macFlag = "-r ${policy.shellFlagValue}"

            // PRE-REQUEST SAFETY CHECK:
            // If already connected on the same SSID (e.g. 2.4 GHz):
            val preCheckStatus = _status.value
            if (preCheckStatus.isConnected && preCheckStatus.ssid.equals(ssid, ignoreCase = true)) {
                // If already on the target BSSID, skip connect-network entirely!
                if (preCheckStatus.bssid.equals(bssid, ignoreCase = true)) {
                    DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId source=$requestSource result=noop reason=already_on_target_bssid targetBssid=$bssid")
                    if (unpinProfileForRoaming) {
                        ensureProfileUnpinned(escapedSsid, escapedSec, passphrase, isOpen, macFlag, ssid, correlationId)
                    }
                    refreshStatus()
                    return@withContext true
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
                    preCheckStatus.ssid.equals(ssid, ignoreCase = true) &&
                    !preCheckStatus.bssid.equals(bssid, ignoreCase = true)
            if (alreadyOnSameSsid) {
                val listNetworks = ShizukuManager.exec("cmd wifi list-networks", correlationId = correlationId)
                val netId = WifiParser.parseNetworkId(listNetworks.stdout, ssid)
                if (netId != null && netId >= 0) {
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
                refreshStatus()
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
                refreshStatus()
                val current = _status.value
                if (current.isConnected && current.ssid.equals(ssid, ignoreCase = true)) {
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

            refreshStatus()
            scanRadios(correlationId)
            val activeMatchesTarget = _status.value.isConnected && _status.value.bssid.equals(bssid, ignoreCase = true)

            val isVerified = if (unpinProfileForRoaming) {
                // PREFERRED 5 GHz (Dynamic steering with roaming allowed):
                // Once bound to target BSSID, unpin the saved profile in Android (bssid = any).
                // Also restore the roaming profile if the target was never reached (including a
                // failed WPA3/SAE association), so retries do not inherit a stale BSSID pin.
                val unpinVerified = ensureProfileUnpinned(
                    escapedSsid, escapedSec, passphrase, isOpen, macFlag, ssid, correlationId
                )
                refreshStatus()
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
                refreshStatus()
                activeMatchesTarget && isHardPinVerified
            }

            DiagnosticLogger.log(
                "WIFI_ACTION",
                "id=$correlationId source=$requestSource result=${if (isVerified) "success" else "not_verified"} action=lock_to_bssid unpinForRoaming=$unpinProfileForRoaming connected=${_status.value.isConnected} currentBssid=${_status.value.bssid} band=${_status.value.band.displayName} rssi=${_status.value.rssi} locked=${_status.value.isLockedToBssid} preferred5G=${_status.value.isPreferred5GHz}"
            )
            isVerified
        } finally {
            _isOperating.value = false
        }
    }

    suspend fun unlockToAuto(
        ssid: String,
        passphrase: String? = null,
        securityType: String = "",
        macAddressPolicy: com.quintz.wifi.model.MacAddressPolicy? = null,
        isAutomatedFallback: Boolean = false,
        requestSource: String = "unspecified",
        correlationId: String = DiagnosticLogger.newCorrelationId()
    ): Boolean = withContext(Dispatchers.IO) {
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
        _isOperating.value = true
        DiagnosticLogger.log("WIFI", "id=$correlationId source=$requestSource Unlock request (Auto-Roam): SSID='$ssid', MAC=${policy.displayName}, isFallback=$isAutomatedFallback")
        try {
            val pass = passphrase ?: prefs.getPassword(ssid).orEmpty()
            val sec = if (securityType.isNotEmpty()) securityType else detectSecurityType(ssid, "")
            val isOpen = sec == "open" || sec == "owe"

            if (isAutomatedFallback) {
                // Automated watchdog fallback: keep target band as 5GHz so watchdog continues recovery scanning
                prefs.isWatchdogFallbackActive = true
            } else {
                // User-initiated auto-roam: explicitly switch target band to Auto
                prefs.lastTargetBand = "Auto"
                prefs.isWatchdogFallbackActive = false
            }

            val escapedSsid = ShizukuManager.escapeShellArg(ssid)
            val escapedSec = ShizukuManager.escapeShellArg(sec)
            val macFlag = "-r ${policy.shellFlagValue}"

            // The watchdog may be handling an onLost callback for the old BSSID while Android
            // has already completed its same-SSID roam. Refresh before deciding to reconnect;
            // otherwise stale cached state can cause an unnecessary disconnect/reassociation.
            val currentStatus = if (isAutomatedFallback) refreshStatus() else _status.value
            val alreadyConnectedToSsid = currentStatus.isConnected && currentStatus.ssid.equals(ssid, ignoreCase = true)

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
                ShizukuManager.exec("cmd wifi add-network $escapedSsid $escapedSec $macFlag", correlationId = correlationId)
                ShizukuManager.exec("cmd wifi connect-network $escapedSsid $escapedSec $macFlag", correlationId = correlationId)
            } else if (pass.isNotEmpty()) {
                val escapedPass = ShizukuManager.escapeShellArg(pass)
                ShizukuManager.exec("cmd wifi add-network $escapedSsid $escapedSec $escapedPass $macFlag", correlationId = correlationId)
                ShizukuManager.exec("cmd wifi connect-network $escapedSsid $escapedSec $escapedPass $macFlag", correlationId = correlationId)
            } else {
                DiagnosticLogger.log("WIFI", "Unlock: no saved password available for secured SSID='$ssid'. Skipping command to preserve saved configuration.")
                return@withContext true
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
            refreshStatus()
            scanRadios(correlationId)
            DiagnosticLogger.log(
                "WIFI_ACTION",
                "id=$correlationId source=$requestSource result=${result.isSuccess && !isStillPinned} action=unlock_to_auto connected=${_status.value.isConnected} currentBssid=${_status.value.bssid} band=${_status.value.band.displayName} rssi=${_status.value.rssi} locked=${_status.value.isLockedToBssid} preferred5G=${_status.value.isPreferred5GHz}"
            )
            result.isSuccess && !isStillPinned
        } finally {
            _isOperating.value = false
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
                return refreshStatus()
            }
            kotlinx.coroutines.delay(400)
        }
        return latestStatus
    }

    suspend fun autoSelectAndLock5Ghz(
        ssid: String,
        passphrase: String,
        macAddressPolicy: com.quintz.wifi.model.MacAddressPolicy? = null,
        requestSource: String = "unspecified",
        correlationId: String = DiagnosticLogger.newCorrelationId()
    ): Boolean {
        val current = _status.value
        DiagnosticLogger.log(
            "WIFI_ACTION",
            "id=$correlationId source=$requestSource action=prefer_5ghz_start ssid='$ssid' connected=${current.isConnected} currentBssid=${current.bssid} band=${current.band.displayName} rssi=${current.rssi} cachedRadios=${_radios.value.size}"
        )
        var currentRadios = _radios.value
        val hasFresh5G = currentRadios.any {
            it.ssid.equals(ssid, ignoreCase = true) &&
                    (it.band == BandType.BAND_5_GHZ || it.band == BandType.BAND_6_GHZ) &&
                    it.ageSeconds <= 8L
        }
        if (!hasFresh5G) {
            DiagnosticLogger.log("WIFI", "id=$correlationId source=$requestSource autoSelectAndLock5Ghz: Scanning for fresh 5 GHz candidates...")
            currentRadios = scanRadios(correlationId)
        }

        val best5G = currentRadios
            .filter {
                it.ssid.equals(ssid, ignoreCase = true) &&
                        (it.band == BandType.BAND_5_GHZ || it.band == BandType.BAND_6_GHZ) &&
                        it.rssi >= -80
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
        val sec = detectSecurityFromFlags(best5G.flags)
        return lockToBssid(
            ssid,
            best5G.bssid,
            passphrase,
            sec,
            macAddressPolicy,
            unpinProfileForRoaming = true,
            deferIfHealthy24Ghz = false,
            requestSource = requestSource,
            correlationId = correlationId
        )
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
