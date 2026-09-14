package com.quintz.wifi.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.os.Build
import com.quintz.wifi.data.Preferences
import com.quintz.wifi.model.AccessPointRadio
import com.quintz.wifi.model.BandType
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

            val wifiInfo = caps.transportInfo as? WifiInfo ?: return WifiStatus(isConnected = true)
            val freq = wifiInfo.frequency
            val rssi = wifiInfo.rssi
            val speed = wifiInfo.linkSpeed
            val rawSsid = wifiInfo.ssid.orEmpty().trim('"')
            val ssid = if (rawSsid == "<unknown ssid>" || rawSsid == "<none>") "" else rawSsid
            val rawBssid = wifiInfo.bssid.orEmpty()
            val bssid = if (rawBssid == "02:00:00:00:00:00") "" else rawBssid

            val standard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
                rssi = rssi,
                linkSpeedMbps = speed,
                standard = standard
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
                linkSpeedMbps = if (nativeStatus.linkSpeedMbps > 0) nativeStatus.linkSpeedMbps else status.linkSpeedMbps
            )
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
    ): Boolean = withContext(Dispatchers.IO) {
        if (!ShizukuManager.isReady()) return@withContext false

        _isOperating.value = true
        try {
            val sec = if (securityType.isNotEmpty()) securityType else detectSecurityType(ssid, bssid)
            val isOpen = sec == "open" || sec == "owe"

            if (!isOpen && passphrase.isNotEmpty()) {
                prefs.savePassword(ssid, passphrase)
            }
            prefs.lastTargetBand = "5GHz"

            val escapedSsid = ShizukuManager.escapeShellArg(ssid)
            val escapedBssid = ShizukuManager.escapeShellArg(bssid)
            val escapedSec = ShizukuManager.escapeShellArg(sec)

            val cmd = if (isOpen) {
                "cmd wifi connect-network $escapedSsid $escapedSec -b $escapedBssid"
            } else {
                val escapedPass = ShizukuManager.escapeShellArg(passphrase)
                "cmd wifi connect-network $escapedSsid $escapedSec $escapedPass -b $escapedBssid"
            }
            val result = ShizukuManager.exec(cmd)

            val currentStatus = _status.value
            if (currentStatus.isConnected && currentStatus.ssid.equals(ssid, ignoreCase = true) &&
                !currentStatus.bssid.equals(bssid, ignoreCase = true)
            ) {
                // If already connected to this network on a different BSSID,
                // Android's WifiNetworkSelector skips re-association. Cycle Wi-Fi briefly to bind immediately.
                ShizukuManager.exec("cmd wifi set-wifi-enabled disabled && cmd wifi set-wifi-enabled enabled")
                awaitConnectionSettled(targetBssid = bssid, maxWaitMs = 12000L)
            } else {
                awaitConnectionSettled(targetBssid = bssid, maxWaitMs = 5000L)
            }

            refreshStatus()
            scanRadios()
            result.isSuccess
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

    suspend fun autoSelectAndLock5Ghz(ssid: String, passphrase: String): Boolean {
        var currentRadios = _radios.value
        if (currentRadios.isEmpty()) {
            val quickScan = ShizukuManager.exec("cmd wifi list-scan-results")
            val parsed = WifiParser.parseScanResults(quickScan.stdout, ssid, _status.value.bssid)
            if (parsed.isNotEmpty()) {
                currentRadios = parsed
                _radios.value = parsed
            } else {
                currentRadios = scanRadios()
            }
        }
        val best5G = currentRadios.firstOrNull { it.band == BandType.BAND_5_GHZ || it.band == BandType.BAND_6_GHZ }
            ?: return false
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

    private fun detectSecurityFromFlags(flags: String): String {
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

