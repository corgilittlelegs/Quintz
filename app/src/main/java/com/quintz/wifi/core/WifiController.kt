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
import com.quintz.wifi.model.LockResult
import com.quintz.wifi.model.WifiStatus
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
                val parsedDump = WifiParser.parseStatus(dump.stdout)
                if (resolvedSsid.isEmpty()) resolvedSsid = parsedDump.ssid
                if (resolvedBssid.isEmpty()) resolvedBssid = parsedDump.bssid
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
            val grepPattern = ShizukuManager.escapeShellArg("ID: [0-9]+ SSID: \"${status.ssid}\"")
            val lockCheck = ShizukuManager.exec("dumpsys wifi 2>/dev/null | grep -m 1 -E $grepPattern")
            val (isProfileLocked, lockedBssid) = WifiParser.parseLockedBssid(lockCheck.stdout)
            // True only if currently bound/locked to the active BSSID
            val isCurrentlyLocked = isProfileLocked && lockedBssid.equals(status.bssid, ignoreCase = true)
            status.copy(isLockedToBssid = isCurrentlyLocked, lockedBssid = lockedBssid)
        } else {
            status
        }

        _status.value = finalStatus
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

    suspend fun scanRadios(): List<AccessPointRadio> = withContext(Dispatchers.IO) {
        if (!ShizukuManager.isReady()) return@withContext emptyList()

        _isScanning.value = true
        try {
            ShizukuManager.exec("cmd wifi start-scan")
            kotlinx.coroutines.delay(1200)
            val scanResult = ShizukuManager.exec("cmd wifi list-scan-results")
            
            val currentSsid = _status.value.ssid
            val currentBssid = _status.value.bssid
            
            val list = WifiParser.parseScanResults(scanResult.stdout, currentSsid, currentBssid)
            _radios.value = list
            list
        } finally {
            _isScanning.value = false
        }
    }

    suspend fun lockToBssid(
        ssid: String,
        bssid: String,
        passphrase: String,
        securityType: String = ""
    ): LockResult = withContext(Dispatchers.IO) {
        if (!ShizukuManager.isReady()) return@withContext LockResult.ShizukuNotReady

        _isOperating.value = true
        try {
            val sec = if (securityType.isNotEmpty()) securityType else detectSecurityType(ssid, bssid)
            val isOpen = sec == "open" || sec == "owe"
            val pass = if (passphrase.isNotEmpty()) passphrase else prefs.getPassword(ssid).orEmpty()

            if (!isOpen && pass.isNotEmpty()) {
                prefs.savePassword(ssid, pass)
            }
            prefs.lastTargetBand = "5GHz"

            val escapedSsid = ShizukuManager.escapeShellArg(ssid)
            val escapedBssid = ShizukuManager.escapeShellArg(bssid)
            val escapedSec = ShizukuManager.escapeShellArg(sec)
            val escapedPass = if (!isOpen && pass.isNotEmpty()) ShizukuManager.escapeShellArg(pass) else null

            // 1. Update the saved network profile in WifiConfigStore to lock the BSSID
            val addCmd = if (isOpen) {
                "cmd wifi add-network $escapedSsid $escapedSec -b $escapedBssid"
            } else if (escapedPass != null) {
                "cmd wifi add-network $escapedSsid $escapedSec $escapedPass -b $escapedBssid"
            } else {
                "cmd wifi add-network $escapedSsid $escapedSec -b $escapedBssid"
            }
            ShizukuManager.exec(addCmd)

            // 2. Request connection to the target network and BSSID
            val connectCmd = if (isOpen) {
                "cmd wifi connect-network $escapedSsid $escapedSec -b $escapedBssid"
            } else if (escapedPass != null) {
                "cmd wifi connect-network $escapedSsid $escapedSec $escapedPass -b $escapedBssid"
            } else {
                "cmd wifi connect-network $escapedSsid $escapedSec -b $escapedBssid"
            }
            val result = ShizukuManager.exec(connectCmd)

            val currentStatus = _status.value
            val isSameSsidDifferentBssid = currentStatus.isConnected &&
                currentStatus.ssid.trim('"').equals(ssid.trim('"'), ignoreCase = true) &&
                !currentStatus.bssid.equals(bssid, ignoreCase = true)

            if (isSameSsidDifferentBssid) {
                // If already connected to this network on a different BSSID,
                // Android's WifiNetworkSelector skips re-association. Cycle Wi-Fi briefly to bind immediately.
                ShizukuManager.exec("cmd wifi set-wifi-enabled disabled && cmd wifi set-wifi-enabled enabled")
                kotlinx.coroutines.delay(1500)
                // Re-trigger connect-network once Wi-Fi is re-enabled to ensure prompt association
                ShizukuManager.exec(connectCmd)
                awaitConnectionSettled(targetBssid = bssid, maxWaitMs = 12000L)
            } else {
                awaitConnectionSettled(targetBssid = bssid, maxWaitMs = 8000L)
            }

            val finalStatus = refreshStatus()
            scanRadios()

            // 3. Verify actual BSSID binding
            if (finalStatus.isConnected && finalStatus.bssid.equals(bssid, ignoreCase = true)) {
                LockResult.Success(bssid = finalStatus.bssid, band = finalStatus.band)
            } else if (!result.isSuccess) {
                LockResult.CommandFailed(result.stderr.ifEmpty { "Command failed with exit code ${result.exitCode}" })
            } else {
                LockResult.AssociationFailed(
                    targetBssid = bssid,
                    actualBssid = if (finalStatus.isConnected) finalStatus.bssid else null,
                    reason = if (finalStatus.isConnected) {
                        "Connected to ${finalStatus.bssid} instead of target $bssid"
                    } else {
                        "Connection timed out or failed to associate"
                    }
                )
            }
        } finally {
            _isOperating.value = false
        }
    }

    suspend fun unlockToAuto(
        ssid: String,
        passphrase: String? = null,
        securityType: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        if (!ShizukuManager.isReady()) return@withContext false

        _isOperating.value = true
        try {
            val pass = passphrase ?: prefs.getPassword(ssid).orEmpty()
            val sec = if (securityType.isNotEmpty()) securityType else detectSecurityType(ssid, "")
            val isOpen = sec == "open" || sec == "owe"

            prefs.lastTargetBand = "Auto"

            val escapedSsid = ShizukuManager.escapeShellArg(ssid)
            val escapedSec = ShizukuManager.escapeShellArg(sec)

            val result = if (isOpen) {
                ShizukuManager.exec("cmd wifi add-network $escapedSsid $escapedSec")
                ShizukuManager.exec("cmd wifi connect-network $escapedSsid $escapedSec")
            } else if (pass.isNotEmpty()) {
                val escapedPass = ShizukuManager.escapeShellArg(pass)
                ShizukuManager.exec("cmd wifi add-network $escapedSsid $escapedSec $escapedPass")
                ShizukuManager.exec("cmd wifi connect-network $escapedSsid $escapedSec $escapedPass")
            } else {
                ShizukuManager.exec("cmd wifi connect-network $escapedSsid $escapedSec")
            }

            awaitConnectionSettled(targetBssid = null, maxWaitMs = 5000L)
            refreshStatus()
            scanRadios()
            result.isSuccess
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

    suspend fun autoSelectAndLock5Ghz(ssid: String, passphrase: String): LockResult {
        if (!ShizukuManager.isReady()) {
            return LockResult.ShizukuNotReady
        }

        // 1. Force a fresh scan to avoid targeting stale APs
        val freshRadios = scanRadios()

        // 2. Filter candidates strictly to the requested SSID and 5 GHz / 6 GHz bands
        var candidates = freshRadios.filter {
            it.ssid.trim('"').equals(ssid.trim('"'), ignoreCase = true) &&
                (it.band == BandType.BAND_5_GHZ || it.band == BandType.BAND_6_GHZ)
        }

        // Fallback: If fresh scan produced no matches (e.g. temporary scan throttle), check recent cached radios for this SSID
        if (candidates.isEmpty()) {
            candidates = _radios.value.filter {
                it.ssid.trim('"').equals(ssid.trim('"'), ignoreCase = true) &&
                    (it.band == BandType.BAND_5_GHZ || it.band == BandType.BAND_6_GHZ)
            }
        }

        // If still no candidates matching this SSID on 5GHz/6GHz, return No5GhzRadioFound
        val best5G = candidates.maxByOrNull { it.rssi }
            ?: return LockResult.No5GhzRadioFound(ssid)

        val sec = detectSecurityFromFlags(best5G.flags)
        return lockToBssid(ssid, best5G.bssid, passphrase, sec)
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

    fun detectSecurityFromFlags(flags: String): String {
        val upper = flags.uppercase()
        return when {
            upper.contains("SAE") -> "wpa3"
            upper.contains("PSK") -> "wpa2"
            upper.contains("OWE") -> "owe"
            upper.contains("WEP") -> "wep"
            else -> "open"
        }
    }
}

