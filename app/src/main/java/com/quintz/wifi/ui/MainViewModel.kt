package com.quintz.wifi.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.quintz.wifi.R
import com.quintz.wifi.core.DiagnosticLogger
import com.quintz.wifi.core.WifiController
import com.quintz.wifi.data.Preferences
import com.quintz.wifi.model.AccessPointRadio
import com.quintz.wifi.model.BandType
import com.quintz.wifi.model.ShizukuState
import com.quintz.wifi.model.WifiStatus
import com.quintz.wifi.service.TileService
import com.quintz.wifi.service.TileStateTracker
import com.quintz.wifi.service.WatchdogService
import com.quintz.wifi.shizuku.ShizukuManager
import com.quintz.wifi.telemetry.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val controller = WifiController(application)
    val prefs = Preferences(application)

    private val _telemetryState = MutableStateFlow(TelemetryGraphState())
    val telemetryState: StateFlow<TelemetryGraphState> = _telemetryState.asStateFlow()

    private val maxTelemetrySamples = 60
    private val telemetryBuffer = ArrayDeque<TelemetrySample>()
    private var previousBssid = ""
    private var previousBand = BandType.UNKNOWN
    private var telemetryJob: Job? = null

    val shizukuState: StateFlow<ShizukuState> = ShizukuManager.state
    val wifiStatus: StateFlow<WifiStatus> = controller.status
    val radios: StateFlow<List<AccessPointRadio>> = controller.radios
    val isOperating: StateFlow<Boolean> = controller.isOperating
    val isScanning: StateFlow<Boolean> = controller.isScanning

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _watchdogActive = MutableStateFlow(prefs.isWatchdogEnabled)
    val watchdogActive: StateFlow<Boolean> = _watchdogActive.asStateFlow()

    private val _isTileAdded = MutableStateFlow(prefs.isQuickTileAdded)
    val isTileAdded: StateFlow<Boolean> = _isTileAdded.asStateFlow()

    private val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var pollingJob: Job? = null
    private var scannerJob: Job? = null
    private var isForeground = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val status = controller.status.value
            DiagnosticLogger.log(
                "NETWORK_EVENT",
                "callback=onAvailable network=$network foreground=$isForeground operating=${controller.isOperating.value} appConnected=${status.isConnected} ssid='${status.ssid}' bssid=${status.bssid} band=${status.band.displayName} rssi=${status.rssi}"
            )
            if (!isForeground) return
            viewModelScope.launch {
                if (ShizukuManager.isReady() && !controller.isOperating.value) {
                    controller.refreshStatus()
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val wifiInfo = networkCapabilities.transportInfo as? android.net.wifi.WifiInfo ?: return
            val currentStatus = controller.status.value
            // Instant detection for intra-SSID band roaming (5 GHz <-> 2.4 GHz) or AP change
            val freqChanged = wifiInfo.frequency != 0 && wifiInfo.frequency != currentStatus.frequency
            val bssid = wifiInfo.bssid
            val bssidChanged = !bssid.isNullOrEmpty() && bssid != "02:00:00:00:00:00" && !bssid.equals(currentStatus.bssid, ignoreCase = true)

            if (freqChanged || bssidChanged || !currentStatus.isConnected) {
                DiagnosticLogger.log(
                    "NETWORK_EVENT",
                    "callback=onCapabilitiesChanged network=$network reason=${listOfNotNull(if (freqChanged) "frequency_changed" else null, if (bssidChanged) "bssid_changed" else null, if (!currentStatus.isConnected) "app_status_disconnected" else null).joinToString(",")} observedSsid='${wifiInfo.ssid}' observedBssid=$bssid observedFrequencyMhz=${wifiInfo.frequency} observedRssi=${wifiInfo.rssi} appBssid=${currentStatus.bssid} appFrequencyMhz=${currentStatus.frequency} appRssi=${currentStatus.rssi} operating=${controller.isOperating.value}"
                )
                if (!isForeground) return
                viewModelScope.launch {
                    if (!controller.isOperating.value) {
                        controller.refreshStatus()
                    }
                }
            }
        }

        override fun onLost(network: Network) {
            val status = controller.status.value
            DiagnosticLogger.log(
                "NETWORK_EVENT",
                "callback=onLost network=$network foreground=$isForeground operating=${controller.isOperating.value} appConnected=${status.isConnected} ssid='${status.ssid}' bssid=${status.bssid} band=${status.band.displayName} rssi=${status.rssi}"
            )
            if (!isForeground) return
            viewModelScope.launch {
                if (!controller.isOperating.value) {
                    controller.refreshStatus()
                }
            }
        }
    }

    init {
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            android.util.Log.e("MainVM", "Failed to register NetworkCallback", e)
        }

        // Immediately perform initial native Wi-Fi refresh on launch
        viewModelScope.launch {
            controller.refreshStatus()
        }

        viewModelScope.launch {
            TileStateTracker.tileAddedFlow.collect { added ->
                _isTileAdded.value = added
            }
        }

        checkTileStatus()

        viewModelScope.launch {
            wifiStatus.collect { status ->
                recordTelemetrySample()
            }
        }

        viewModelScope.launch {
            shizukuState.collect { state ->
                if (state.isPermissionGranted) {
                    refreshAll()
                    if (prefs.isWatchdogEnabled) {
                        try {
                            val context = getApplication<Application>()
                            val intent = Intent(context, WatchdogService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("MainVM", "Failed to auto-start Watchdog", e)
                        }
                    }
                }
            }
        }
    }

    fun startForegroundPolling() {
        isForeground = true
        if (pollingJob?.isActive != true) {
            pollingJob = viewModelScope.launch {
                while (isActive) {
                    if (!controller.isOperating.value) {
                        controller.refreshStatus()
                    }
                    delay(2500)
                }
            }
        }
        startScannerPolling()
        startTelemetryPolling()
    }

    private fun startTelemetryPolling() {
        if (telemetryJob?.isActive == true) return
        telemetryJob = viewModelScope.launch {
            while (isActive) {
                if (isForeground) {
                    recordTelemetrySample()
                }
                delay(1000)
            }
        }
    }

    private fun stopTelemetryPolling() {
        telemetryJob?.cancel()
        telemetryJob = null
    }

    fun startScannerPolling() {
        if (scannerJob?.isActive == true) return
        scannerJob = viewModelScope.launch {
            while (isActive) {
                if (isForeground && ShizukuManager.isReady() && !controller.isOperating.value && !controller.isScanning.value) {
                    controller.scanRadios()
                }
                delay(8500) // ~10s total cycle time (1.5s scan + 8.5s rest)
            }
        }
    }

    fun stopScannerPolling() {
        scannerJob?.cancel()
        scannerJob = null
    }

    fun setScannerActive(active: Boolean) {
        if (active) {
            startScannerPolling()
        } else {
            stopScannerPolling()
        }
    }

    fun togglePauseTelemetry() {
        _telemetryState.value = _telemetryState.value.copy(
            isPaused = !_telemetryState.value.isPaused
        )
    }

    fun clearTelemetryHistory() {
        synchronized(telemetryBuffer) {
            telemetryBuffer.clear()
        }
        _telemetryState.value = _telemetryState.value.copy(
            samples = emptyList(),
            minRssi = _telemetryState.value.activeRssi,
            maxRssi = _telemetryState.value.activeRssi
        )
    }

    fun selectCandidateBssid(bssid: String?) {
        _telemetryState.value = _telemetryState.value.copy(
            selectedCandidateBssid = bssid
        )
    }

    fun recordTelemetrySample() {
        val status = controller.status.value
        val now = System.currentTimeMillis()
        if (!status.isConnected || status.rssi == 0 || status.rssi <= -127) {
            if (!status.isConnected && _telemetryState.value.activeBssid.isNotEmpty()) {
                _telemetryState.value = _telemetryState.value.copy(
                    activeBssid = "",
                    activeSsid = "",
                    activeRssi = 0,
                    activeLinkSpeedMbps = 0,
                    activeBand = BandType.UNKNOWN
                )
            }
            return
        }
        if (_telemetryState.value.isPaused) return

        // Detect Roam Event
        var roamEvent: RoamEvent? = null
        if (previousBssid.isNotEmpty() && status.bssid.isNotEmpty() && previousBssid.lowercase() != status.bssid.lowercase()) {
            roamEvent = RoamEvent(
                timestamp = now,
                fromBssid = previousBssid,
                toBssid = status.bssid,
                fromBand = previousBand,
                toBand = status.band
            )
        }
        previousBssid = status.bssid
        previousBand = status.band

        // Extract candidate APs (same SSID or alternative BSSIDs)
        val currentSsid = status.ssid
        val currentBssid = status.bssid.lowercase()
        val apList = controller.radios.value

        val candidatesMap = mutableMapOf<String, CandidateSample>()
        apList.filter { it.bssid.lowercase() != currentBssid && (it.ssid.isEmpty() || it.ssid == currentSsid || currentSsid.isEmpty()) }
            .forEach { ap ->
                candidatesMap[ap.bssid.lowercase()] = CandidateSample(
                    bssid = ap.bssid,
                    ssid = ap.ssid,
                    rssi = ap.rssi,
                    channel = ap.channel,
                    band = ap.band
                )
            }

        val sample = TelemetrySample(
            timestamp = now,
            activeBssid = status.bssid,
            activeRssi = status.rssi,
            activeLinkSpeedMbps = status.linkSpeedMbps,
            activeBand = status.band,
            candidates = candidatesMap,
            roamEvent = roamEvent
        )

        synchronized(telemetryBuffer) {
            telemetryBuffer.addLast(sample)
            while (telemetryBuffer.size > maxTelemetrySamples) {
                telemetryBuffer.removeFirst()
            }
        }

        val sampleList = synchronized(telemetryBuffer) { telemetryBuffer.toList() }
        val rssiValues = sampleList.map { it.activeRssi }.filter { it in -120..-10 }
        val minRssi = rssiValues.minOrNull() ?: status.rssi
        val maxRssi = rssiValues.maxOrNull() ?: status.rssi

        val candidateMetas = candidatesMap.values.mapIndexed { idx, cand ->
            CandidateMeta(
                bssid = cand.bssid,
                ssid = cand.ssid,
                channel = cand.channel,
                band = cand.band,
                latestRssi = cand.rssi,
                colorIndex = idx
            )
        }.sortedByDescending { it.latestRssi }

        val bestCandidate = candidateMetas.firstOrNull()
        val roamAdvantage = if (bestCandidate != null && bestCandidate.latestRssi > status.rssi) {
            bestCandidate.latestRssi - status.rssi
        } else 0

        _telemetryState.value = _telemetryState.value.copy(
            samples = sampleList,
            activeBssid = status.bssid,
            activeSsid = status.ssid,
            activeRssi = status.rssi,
            activeLinkSpeedMbps = status.linkSpeedMbps,
            activeBand = status.band,
            minRssi = minRssi,
            maxRssi = maxRssi,
            candidates = candidateMetas,
            bestCandidateBssid = if (roamAdvantage > 0) bestCandidate?.bssid else null,
            roamAdvantageDbm = roamAdvantage
        )
    }

    fun stopForegroundPolling() {
        isForeground = false
        pollingJob?.cancel()
        pollingJob = null
        stopScannerPolling()
        stopTelemetryPolling()
    }

    override fun onCleared() {
        super.onCleared()
        stopForegroundPolling()
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }

    fun refreshAll() {
        viewModelScope.launch {
            controller.refreshStatus()
            if (ShizukuManager.isReady()) {
                controller.scanRadios()
            }
            checkTileStatus()
        }
    }

    fun requestShizukuPermission() {
        ShizukuManager.requestPermission()
    }

    fun getMacPolicy(ssid: String): com.quintz.wifi.model.MacAddressPolicy? = prefs.getMacPolicy(ssid)

    fun setMacPolicy(
        ssid: String,
        policy: com.quintz.wifi.model.MacAddressPolicy,
        requestSource: String = "main_screen_mac_policy",
        correlationId: String = DiagnosticLogger.newCorrelationId()
    ) {
        DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId source=$requestSource action=set_mac_policy ssid='$ssid' policy=${policy.displayName}")
        prefs.setMacPolicy(ssid, policy)
        viewModelScope.launch {
            val status = wifiStatus.value
            if (status.isConnected && status.ssid.equals(ssid, ignoreCase = true)) {
                val pass = prefs.getPassword(ssid).orEmpty()
                val isSecured = status.securityType != "0" && status.securityType != "open" && status.securityType != "owe"

                // Only re-apply lock/network command if we have the password or it's an open network
                if (!isSecured || pass.isNotEmpty()) {
                    if (status.isLockedToBssid && status.bssid.isNotEmpty()) {
                        controller.lockToBssid(
                            ssid,
                            status.bssid,
                            pass,
                            macAddressPolicy = policy,
                            requestSource = requestSource,
                            correlationId = correlationId
                        )
                    } else {
                        controller.unlockToAuto(
                            ssid,
                            pass,
                            macAddressPolicy = policy,
                            requestSource = requestSource,
                            correlationId = correlationId
                        )
                    }
                    refreshAll()
                }
                _message.value = "Updated MAC mode to ${policy.displayName}"
            } else {
                _message.value = "Updated MAC mode to ${policy.displayName}"
            }
        }
    }

    fun forceLock5Ghz(
        password: String,
        macPolicy: com.quintz.wifi.model.MacAddressPolicy? = null,
        requestSource: String = "main_screen_primary_button",
        correlationId: String = DiagnosticLogger.newCorrelationId()
    ) {
        DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId source=$requestSource action=prefer_5ghz_requested")
        viewModelScope.launch {
            val status = wifiStatus.value
            DiagnosticLogger.log(
                "WIFI_ACTION",
                "id=$correlationId source=$requestSource action=prefer_5ghz_state_snapshot connected=${status.isConnected} ssid='${status.ssid}' bssid=${status.bssid} band=${status.band.displayName} rssi=${status.rssi} locked=${status.isLockedToBssid} preferred5G=${status.isPreferred5GHz}"
            )
            if (!status.isConnected || status.ssid.isEmpty()) {
                DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId source=$requestSource result=aborted reason=not_connected")
                _message.value = "Device is not connected to any Wi-Fi network"
                return@launch
            }

            val policy = macPolicy ?: prefs.getMacPolicy(status.ssid) ?: prefs.defaultMacPolicy
            val success = controller.autoSelectAndLock5Ghz(
                status.ssid,
                password,
                policy,
                requestSource = requestSource,
                correlationId = correlationId
            )
            DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId source=$requestSource result=${if (success) "success" else "failure"} action=prefer_5ghz")
            refreshAll()
            if (success) {
                _message.value = "Preferred 5 GHz active with roaming allowed (${policy.displayName})"
                if (!prefs.isWatchdogEnabled) {
                    toggleWatchdog(true)
                }
            } else {
                _message.value = "Failed to lock to 5 GHz on \"${status.ssid}\". Check 5 GHz signal strength."
            }
        }
    }

    fun lockToSpecificRadio(
        radio: AccessPointRadio,
        password: String,
        macPolicy: com.quintz.wifi.model.MacAddressPolicy? = null,
        requestSource: String = "main_screen_radio_list",
        correlationId: String = DiagnosticLogger.newCorrelationId()
    ) {
        DiagnosticLogger.log(
            "WIFI_ACTION",
            "id=$correlationId source=$requestSource action=lock_specific_radio requestedSsid='${radio.ssid}' targetBssid=${radio.bssid} band=${radio.band.displayName} rssi=${radio.rssi}"
        )
        viewModelScope.launch {
            val status = wifiStatus.value
            val targetSsid = radio.ssid.ifEmpty { status.ssid }
            val policy = macPolicy ?: prefs.getMacPolicy(targetSsid) ?: prefs.defaultMacPolicy
            val success = controller.lockToBssid(
                targetSsid,
                radio.bssid,
                password,
                macAddressPolicy = policy,
                unpinProfileForRoaming = false,
                requestSource = requestSource,
                correlationId = correlationId
            )
            DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId source=$requestSource result=${if (success) "success" else "failure"} action=lock_specific_radio targetBssid=${radio.bssid}")
            refreshAll()
            if (success) {
                _message.value = "Locked to AP [${radio.bssid}] on ${radio.band.displayName} (${policy.displayName})"
                if (!prefs.isWatchdogEnabled) {
                    toggleWatchdog(true)
                }
            } else {
                _message.value = "Could not confirm lock to [${radio.bssid}]. Check signal strength."
            }
        }
    }

    fun unlockToAuto(
        requestSource: String = "main_screen_primary_button",
        correlationId: String = DiagnosticLogger.newCorrelationId()
    ) {
        DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId source=$requestSource action=unlock_to_auto_requested")
        viewModelScope.launch {
            val status = wifiStatus.value
            DiagnosticLogger.log(
                "WIFI_ACTION",
                "id=$correlationId source=$requestSource action=unlock_state_snapshot connected=${status.isConnected} ssid='${status.ssid}' bssid=${status.bssid} band=${status.band.displayName} rssi=${status.rssi}"
            )
            val success = controller.unlockToAuto(status.ssid, requestSource = requestSource, correlationId = correlationId)
            DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId source=$requestSource result=${if (success) "success" else "failure"} action=unlock_to_auto")
            refreshAll()
            if (success) {
                _message.value = "Reverted to Auto-Roam mode"
                if (prefs.isWatchdogEnabled) {
                    toggleWatchdog(false)
                }
            } else {
                _message.value = "Failed to unlock to Auto"
            }
        }
    }

    fun toggleWatchdog(enabled: Boolean) {
        prefs.isWatchdogEnabled = enabled
        _watchdogActive.value = enabled
        val context = getApplication<Application>()
        val intent = Intent(context, WatchdogService::class.java)
        if (enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            _message.value = "Watchdog active: Auto-fallback enabled"
        } else {
            context.stopService(intent)
            _message.value = "Watchdog disabled"
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun getSavedPassword(ssid: String): String = prefs.getPassword(ssid).orEmpty()

    fun checkTileStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val isAdded = queryIsTileAdded()
            withContext(Dispatchers.Main) {
                _isTileAdded.value = isAdded
            }
        }
    }

    private fun queryIsTileAdded(): Boolean {
        // 1. Query via Shizuku: inspect sysui_qs_tiles in secure settings
        if (ShizukuManager.isReady()) {
            try {
                val res = ShizukuManager.exec("settings get secure sysui_qs_tiles")
                if (res.isSuccess && res.stdout.isNotBlank()) {
                    val added = res.stdout.contains("com.quintz.wifi/.service.TileService")
                    prefs.isQuickTileAdded = added
                    return added
                }
            } catch (e: Exception) {
                android.util.Log.w("MainVM", "Failed checking sysui_qs_tiles via Shizuku", e)
            }
        }

        // 2. Direct read of Settings.Secure fallback
        try {
            val tiles = android.provider.Settings.Secure.getString(
                getApplication<Application>().contentResolver,
                "sysui_qs_tiles"
            )
            if (tiles != null) {
                val added = tiles.contains("com.quintz.wifi/.service.TileService")
                prefs.isQuickTileAdded = added
                return added
            }
        } catch (_: Exception) {}

        // 3. Fallback to cached preference
        return prefs.isQuickTileAdded
    }

    fun requestAddQuickTile() {
        val context = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val statusBarManager = context.getSystemService(android.app.StatusBarManager::class.java)
            statusBarManager?.requestAddTileService(
                android.content.ComponentName(context, TileService::class.java),
                context.getString(R.string.tile_name),
                android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_qs_tile),
                context.mainExecutor
            ) { result ->
                if (result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) {
                    _isTileAdded.value = true
                    prefs.isQuickTileAdded = true
                    _message.value = "Quintz tile added to Quick Settings!"
                } else if (result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                    _isTileAdded.value = true
                    prefs.isQuickTileAdded = true
                    _message.value = "Tile is already in your Quick Settings panel"
                }
            }
        } else {
            _message.value = "Swipe down twice and tap Edit (✎) to add Quintz tile"
        }
    }

    fun removeQuickTile() {
        viewModelScope.launch(Dispatchers.IO) {
            if (ShizukuManager.isReady()) {
                val res = ShizukuManager.exec("settings get secure sysui_qs_tiles")
                if (res.isSuccess) {
                    val currentTiles = res.stdout.trim()
                    val newTiles = currentTiles.split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.contains("com.quintz.wifi/.service.TileService") }
                        .joinToString(",")
                    val writeRes = ShizukuManager.exec("settings put secure sysui_qs_tiles '$newTiles'")
                    if (writeRes.isSuccess) {
                        prefs.isQuickTileAdded = false
                        withContext(Dispatchers.Main) {
                            _isTileAdded.value = false
                            _message.value = "Quick Settings tile removed"
                        }
                        return@launch
                    }
                }
            }
            withContext(Dispatchers.Main) {
                _message.value = "Swipe down twice and tap Edit (✎) to remove Quintz tile"
            }
        }
    }
}
