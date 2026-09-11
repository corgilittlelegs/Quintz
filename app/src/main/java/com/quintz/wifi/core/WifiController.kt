package com.quintz.wifi.core

import android.content.Context
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

    suspend fun refreshStatus(): WifiStatus = withContext(Dispatchers.IO) {
        if (!ShizukuManager.isReady()) {
            return@withContext WifiStatus()
        }

        val statusResult = ShizukuManager.exec("cmd wifi status")
        val status = WifiParser.parseStatus(statusResult.stdout)

        // Only query BSSID lock if currently connected to an SSID
        val finalStatus = if (status.isConnected && status.ssid.isNotEmpty()) {
            val grepPattern = ShizukuManager.escapeShellArg("ID: [0-9]+ SSID: \"${status.ssid}\"")
            // Targeted query: grep -m 1 returns < 200 bytes in ~40ms instead of dumping 1.7 MB
            val lockCheck = ShizukuManager.exec("dumpsys wifi 2>/dev/null | grep -m 1 -E $grepPattern")
            val (isLocked, lockedBssid) = WifiParser.parseLockedBssid(lockCheck.stdout)
            status.copy(isLockedToBssid = isLocked, lockedBssid = lockedBssid)
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

        _isOperating.value = true
        try {
            ShizukuManager.exec("cmd wifi start-scan")
            kotlinx.coroutines.delay(1200)
            val scanResult = ShizukuManager.exec("cmd wifi list-scan-results")
            
            val currentSsid = _status.value.ssid
            val currentBssid = _status.value.bssid
            
            val list = if (currentSsid.isNotEmpty()) {
                WifiParser.parseScanResults(scanResult.stdout, currentSsid, currentBssid)
            } else {
                emptyList()
            }
            _radios.value = list
            list
        } finally {
            _isOperating.value = false
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
            // Save password for subsequent auto-actions & watchdog
            prefs.savePassword(ssid, passphrase)
            prefs.lastTargetBand = "5GHz"

            val escapedSsid = ShizukuManager.escapeShellArg(ssid)
            val escapedPass = ShizukuManager.escapeShellArg(passphrase)
            val escapedBssid = ShizukuManager.escapeShellArg(bssid)
            val sec = if (securityType.isNotEmpty()) securityType else detectSecurityType(ssid, bssid)
            val escapedSec = ShizukuManager.escapeShellArg(sec)

            val cmd = "cmd wifi connect-network $escapedSsid $escapedSec $escapedPass -b $escapedBssid"
            val result = ShizukuManager.exec(cmd)

            // If already connected to this network on a different BSSID,
            // Android's WifiNetworkSelector skips re-association. Cycle Wi-Fi briefly to bind immediately.
            val currentStatus = _status.value
            if (currentStatus.isConnected && currentStatus.ssid.equals(ssid, ignoreCase = true) &&
                !currentStatus.bssid.equals(bssid, ignoreCase = true)
            ) {
                ShizukuManager.exec("cmd wifi set-wifi-enabled disabled && sleep 1 && cmd wifi set-wifi-enabled enabled")
                awaitConnectionSettled(targetBssid = bssid, maxWaitMs = 12000L)
            } else {
                awaitConnectionSettled(targetBssid = bssid, maxWaitMs = 4000L)
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
            val escapedSsid = ShizukuManager.escapeShellArg(ssid)
            val escapedPass = ShizukuManager.escapeShellArg(pass)
            val sec = if (securityType.isNotEmpty()) securityType else detectSecurityType(ssid, "")
            val escapedSec = ShizukuManager.escapeShellArg(sec)

            prefs.lastTargetBand = "Auto"

            // 1. Re-add network with NO BSSID so saved profile resets to null
            ShizukuManager.exec("cmd wifi add-network $escapedSsid $escapedSec $escapedPass")
            // 2. Connect to any BSSID
            val result = ShizukuManager.exec("cmd wifi connect-network $escapedSsid $escapedSec $escapedPass")

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

