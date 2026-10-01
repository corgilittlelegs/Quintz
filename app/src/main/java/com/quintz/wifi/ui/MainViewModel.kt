package com.quintz.wifi.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.quintz.wifi.R
import com.quintz.wifi.core.DiagnosticLogger
import com.quintz.wifi.core.WifiController
import com.quintz.wifi.core.WifiSecurityPolicy
import com.quintz.wifi.data.Preferences
import com.quintz.wifi.data.WifiTargetMode
import com.quintz.wifi.model.AccessPointRadio
import com.quintz.wifi.model.BandType
import com.quintz.wifi.model.ShizukuState
import com.quintz.wifi.model.WifiStatus
import com.quintz.wifi.service.TileService
import com.quintz.wifi.service.TileStateTracker
import com.quintz.wifi.service.WatchdogControl
import com.quintz.wifi.service.WatchdogService
import com.quintz.wifi.shizuku.ShizukuManager
import com.quintz.wifi.telemetry.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val controller = WifiController(application)
    val prefs = Preferences.get(application)

    private val securePasswordError = "Cannot save the Wi-Fi password securely. Unlock the device, check Android's secure storage, then retry. No password was saved in plain text."

    private val _telemetryState = MutableStateFlow(TelemetryGraphState())
    val telemetryState: StateFlow<TelemetryGraphState> = _telemetryState.asStateFlow()
    private val _telemetryClock = MutableStateFlow(System.currentTimeMillis())
    val telemetryClock: StateFlow<Long> = _telemetryClock.asStateFlow()

    private val maxTelemetrySamples = 60
    private val telemetryBuffer = ArrayDeque<TelemetrySample>()
    private val candidateHistory = mutableMapOf<String, CandidateSample>()
    private var previousSsid = ""
    private var previousBssid = ""
    private var previousBand = BandType.UNKNOWN
    private var lastRecordedStatusObservationMillis = 0L
    private var telemetryJob: Job? = null

    val shizukuState: StateFlow<ShizukuState> = ShizukuManager.state
    val wifiStatus: StateFlow<WifiStatus> = controller.status
    val radios: StateFlow<List<AccessPointRadio>> = controller.radios
    val isOperating: StateFlow<Boolean> = controller.isOperating
    val isScanning: StateFlow<Boolean> = controller.isScanning
    val isScanQueued: StateFlow<Boolean> = controller.isScanQueued

    private val messageChannel = Channel<String>(Channel.UNLIMITED)
    val messages = messageChannel.receiveAsFlow()

    private fun postMessage(message: String) {
        messageChannel.trySend(message)
    }

    val watchdogActive: StateFlow<Boolean> = WatchdogService.isRunning

    private val _batteryOptimizationExempt = MutableStateFlow<Boolean?>(null)
    val batteryOptimizationExempt: StateFlow<Boolean?> = _batteryOptimizationExempt.asStateFlow()
    private val _showBatteryOptimizationPrompt = MutableStateFlow(false)
    val showBatteryOptimizationPrompt: StateFlow<Boolean> = _showBatteryOptimizationPrompt.asStateFlow()

    private val _isTileAdded = MutableStateFlow(prefs.isQuickTileAdded)
    val isTileAdded: StateFlow<Boolean> = _isTileAdded.asStateFlow()

    private val _isDarkMode = MutableStateFlow(prefs.isDarkMode)
    val isDarkMode: StateFlow<Boolean?> = _isDarkMode.asStateFlow()

    fun toggleTheme(currentIsDark: Boolean) {
        val nextMode = !currentIsDark
        _isDarkMode.value = nextMode
        prefs.isDarkMode = nextMode
    }

    fun setDarkMode(dark: Boolean?) {
        _isDarkMode.value = dark
        prefs.isDarkMode = dark
    }

    private val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var pollingJob: Job? = null
    private var scannerJob: Job? = null
    private var isForeground = false
    private var scannerRequested = false

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
            var initialConnectedStatusHandled = false
            wifiStatus.collect { status ->
                recordTelemetrySample()
                if (status.isConnected && !initialConnectedStatusHandled) {
                    initialConnectedStatusHandled = true
                    if ((status.isPreferred5GHz || status.isPreferred5GHzFallback) && !prefs.isWatchdogEnabled) {
                        toggleWatchdog(true)
                    }
                }
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
        if (scannerRequested) startScannerPolling()
        startTelemetryPolling()
    }

    private fun startTelemetryPolling() {
        if (telemetryJob?.isActive == true) return
        telemetryJob = viewModelScope.launch {
            while (isActive) {
                if (isForeground) {
                    _telemetryClock.value = System.currentTimeMillis()
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
                if (isForeground && ShizukuManager.isReady() && !controller.isOperating.value &&
                    !controller.isScanning.value && !controller.isScanQueued.value) {
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
        scannerRequested = active
        if (active) {
            if (isForeground) startScannerPolling()
        } else {
            stopScannerPolling()
        }
    }

    fun togglePauseTelemetry() {
        val paused = !_telemetryState.value.isPaused
        _telemetryState.value = _telemetryState.value.copy(
            isPaused = paused,
            nowTimestampMillis = if (paused) System.currentTimeMillis() else _telemetryState.value.nowTimestampMillis
        )
        if (!paused) recordTelemetrySample()
    }

    fun clearTelemetryHistory() {
        synchronized(telemetryBuffer) {
            telemetryBuffer.clear()
        }
        candidateHistory.clear()
        lastRecordedStatusObservationMillis = 0L
        _telemetryState.value = _telemetryState.value.copy(
            samples = emptyList(),
            minRssi = _telemetryState.value.activeRssi,
            maxRssi = _telemetryState.value.activeRssi,
            candidates = emptyList(),
            bestCandidateBssid = null,
            roamAdvantageDbm = 0,
            selectedCandidateBssid = null
        )
    }

    fun selectCandidateBssid(bssid: String?) {
        if (_telemetryState.value.isPaused) return
        _telemetryState.value = _telemetryState.value.copy(
            selectedCandidateBssid = bssid
        )
    }

    fun recordTelemetrySample() {
        if (_telemetryState.value.isPaused) return
        val status = controller.status.value
        val now = System.currentTimeMillis()
        val scanTimestamp = controller.lastScanCompletedTimestamp
        val baseState = _telemetryState.value.copy(
            isConnected = status.isConnected,
            nowTimestampMillis = now,
            lastStatusObservedAtMillis = status.observedAtMillis,
            lastScanAttemptedAtMillis = controller.lastScanAttemptTimestamp,
            lastScanCompletedAtMillis = scanTimestamp,
            lastScanSucceeded = if (controller.lastScanAttemptTimestamp > 0L) controller.lastScanSucceeded else null,
            lastScanDurationMillis = if (controller.lastScanAttemptTimestamp > 0L) controller.lastScanDurationMillis else null,
            lastScanWasCoalesced = controller.lastScanWasCoalesced
        )
        if (!status.isConnected) {
            synchronized(telemetryBuffer) { telemetryBuffer.clear() }
            candidateHistory.clear()
            lastRecordedStatusObservationMillis = 0L
            previousSsid = ""
            previousBssid = ""
            previousBand = BandType.UNKNOWN
            _telemetryState.value = baseState.copy(
                samples = emptyList(),
                activeBssid = "",
                activeSsid = "",
                activeRssi = 0,
                activeLinkSpeedMbps = 0,
                activeBand = BandType.UNKNOWN,
                minRssi = 0,
                maxRssi = 0,
                candidates = emptyList(),
                bestCandidateBssid = null,
                roamAdvantageDbm = 0,
                selectedCandidateBssid = null
            )
            return
        }
        val networkChanged = previousSsid.isNotEmpty() && status.ssid.isNotEmpty() && previousSsid != status.ssid
        if (networkChanged) {
            synchronized(telemetryBuffer) { telemetryBuffer.clear() }
            candidateHistory.clear()
            lastRecordedStatusObservationMillis = 0L
            previousBssid = ""
            previousBand = BandType.UNKNOWN
        }
        if (status.ssid.isNotEmpty()) previousSsid = status.ssid
        if (status.rssi == 0 || status.rssi <= -127) {
            if (status.bssid.isNotEmpty()) {
                previousBssid = status.bssid
                previousBand = status.band
            }
            _telemetryState.value = if (baseState.activeBssid.isNotEmpty() || networkChanged) {
                baseState.copy(
                    samples = if (networkChanged) emptyList() else baseState.samples,
                    activeBssid = "",
                    activeSsid = "",
                    activeRssi = 0,
                    activeLinkSpeedMbps = 0,
                    activeBand = BandType.UNKNOWN,
                    minRssi = 0,
                    maxRssi = 0,
                    candidates = emptyList(),
                    bestCandidateBssid = null,
                    roamAdvantageDbm = 0,
                    selectedCandidateBssid = null
                )
            } else baseState
            return
        }

        val currentSsid = status.ssid
        val currentBssid = status.bssid.lowercase()
        val apList = controller.radios.value
        val candidatesMap = mutableMapOf<String, CandidateSample>()
        apList.filter {
            currentBssid.isNotEmpty() && it.bssid.lowercase() != currentBssid &&
                currentSsid.isNotEmpty() && it.ssid == currentSsid &&
                isVisibleCandidate(it.observedAtMillis, now)
        }.forEach { ap ->
            val candidate = CandidateSample(
                bssid = ap.bssid,
                ssid = ap.ssid,
                rssi = ap.rssi,
                channel = ap.channel,
                band = ap.band,
                observedAtMillis = ap.observedAtMillis
            )
            val key = ap.bssid.lowercase()
            candidatesMap[key] = candidate
            if (candidate.observedAtMillis >= (candidateHistory[key]?.observedAtMillis ?: 0L)) {
                candidateHistory[key] = candidate
            }
        }
        candidateHistory.entries.removeAll { (bssid, candidate) ->
            bssid == currentBssid || !isVisibleCandidate(candidate.observedAtMillis, now)
        }
        val candidateMetas = candidateHistory.values.sortedBy { it.bssid }.map { cand ->
            CandidateMeta(
                bssid = cand.bssid,
                ssid = cand.ssid,
                channel = cand.channel,
                band = cand.band,
                latestRssi = cand.rssi,
                colorIndex = candidateColorIndex(cand.bssid),
                isInLatestScan = candidatesMap.containsKey(cand.bssid.lowercase()),
                observedAtMillis = cand.observedAtMillis
            )
        }.sortedByDescending { it.latestRssi }
        val bestCandidate = candidateMetas.filter { it.isInLatestScan && isFreshCandidate(it.observedAtMillis, now) }
            .maxByOrNull { it.latestRssi }
        val roamAdvantage = if (bestCandidate != null && bestCandidate.latestRssi > status.rssi) {
            bestCandidate.latestRssi - status.rssi
        } else 0
        val selectedCandidate = baseState.selectedCandidateBssid?.takeIf { selected ->
            candidateMetas.any { it.bssid.equals(selected, ignoreCase = true) }
        }
        if (status.observedAtMillis <= 0L ||
            status.observedAtMillis <= lastRecordedStatusObservationMillis
        ) {
            val samples = synchronized(telemetryBuffer) {
                while (telemetryBuffer.isNotEmpty() && telemetryBuffer.first().timestamp < now - 60_000L) {
                    telemetryBuffer.removeFirst()
                }
                telemetryBuffer.toList()
            }
            val rssiValues = samples.map { it.activeRssi }.filter { it in -120..-10 }
            _telemetryState.value = baseState.copy(
                samples = samples,
                minRssi = rssiValues.minOrNull() ?: status.rssi,
                maxRssi = rssiValues.maxOrNull() ?: status.rssi,
                candidates = candidateMetas,
                selectedCandidateBssid = selectedCandidate,
                bestCandidateBssid = if (roamAdvantage > 0) bestCandidate?.bssid else null,
                roamAdvantageDbm = roamAdvantage
            )
            return
        }
        lastRecordedStatusObservationMillis = status.observedAtMillis

        // Detect Roam Event
        var roamEvent: RoamEvent? = null
        if (previousBssid.isNotEmpty() && status.bssid.isNotEmpty() &&
            (previousBssid.lowercase() != status.bssid.lowercase() || previousBand != status.band)) {
            roamEvent = RoamEvent(
                timestamp = status.observedAtMillis,
                fromBssid = previousBssid,
                toBssid = status.bssid,
                fromBand = previousBand,
                toBand = status.band
            )
        }
        previousBssid = status.bssid
        previousBand = status.band

        val sample = TelemetrySample(
            timestamp = status.observedAtMillis,
            activeBssid = status.bssid,
            activeRssi = status.rssi,
            activeLinkSpeedMbps = status.linkSpeedMbps,
            activeBand = status.band,
            candidates = candidatesMap,
            roamEvent = roamEvent
        )

        synchronized(telemetryBuffer) {
            telemetryBuffer.addLast(sample)
            while (telemetryBuffer.size > maxTelemetrySamples ||
                (telemetryBuffer.firstOrNull()?.timestamp ?: now) < now - 60_000L
            ) {
                telemetryBuffer.removeFirst()
            }
        }

        val sampleList = synchronized(telemetryBuffer) { telemetryBuffer.toList() }
        val rssiValues = sampleList.map { it.activeRssi }.filter { it in -120..-10 }
        val minRssi = rssiValues.minOrNull() ?: status.rssi
        val maxRssi = rssiValues.maxOrNull() ?: status.rssi

        _telemetryState.value = baseState.copy(
            samples = sampleList,
            activeBssid = status.bssid,
            activeSsid = status.ssid,
            activeRssi = status.rssi,
            activeLinkSpeedMbps = status.linkSpeedMbps,
            activeBand = status.band,
            minRssi = minRssi,
            maxRssi = maxRssi,
            candidates = candidateMetas,
            selectedCandidateBssid = selectedCandidate,
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
        viewModelScope.launch {
            val status = controller.refreshStatus()
            if (status.isConnected && status.ssid == ssid) {
                val pass = prefs.getPassword(ssid).orEmpty()
                val isSecured = status.securityType != "0" && status.securityType != "6"
                val shellSecurity = when (status.securityType) {
                    "0" -> "open"
                    "6" -> "owe"
                    "4" -> "wpa3"
                    else -> "wpa2"
                }
                if (isSecured && pass.isEmpty()) {
                    postMessage(if (prefs.isPasswordStorageAvailable) "Save this network's password before applying a MAC change" else securePasswordError)
                    return@launch
                }
                val previousPolicy = prefs.getMacPolicy(ssid)
                val targetMode = prefs.getOrMigrateWifiTargetMode(ssid, status.lockedBssid)
                val success = when (targetMode) {
                    WifiTargetMode.PIN_BSSID -> {
                        val pinnedBssid = prefs.getPinnedBssid(ssid) ?: status.lockedBssid
                        if (pinnedBssid.isNullOrBlank()) {
                            postMessage("No pinned radio is saved for this network; choose one in the app first")
                            return@launch
                        }
                        controller.lockToBssid(
                            ssid, pinnedBssid, pass, securityType = shellSecurity, macAddressPolicy = policy,
                            requestSource = requestSource, correlationId = correlationId
                        )
                    }
                    WifiTargetMode.PREFER_5_GHZ -> controller.autoSelectAndLock5Ghz(
                        ssid, pass, policy, requestSource = requestSource, correlationId = correlationId
                    )
                    WifiTargetMode.AUTO -> controller.unlockToAuto(
                        ssid, pass, securityType = shellSecurity, macAddressPolicy = policy,
                        requestSource = requestSource, correlationId = correlationId
                    )
                }
                if (success) {
                    prefs.setMacPolicy(ssid, policy)
                    postMessage("MAC mode changed to ${policy.displayName}; ${targetMode.name.replace('_', ' ')} kept")
                } else {
                    if (previousPolicy == null) prefs.clearMacPolicy(ssid) else prefs.setMacPolicy(ssid, previousPolicy)
                    postMessage(when {
                        controller.lastPasswordStorageFailure -> securePasswordError
                        targetMode == WifiTargetMode.PREFER_5_GHZ -> "MAC mode unchanged: choose and connect to a trusted 5 GHz radio in the app, then retry"
                        else -> "MAC mode change could not be verified. Previous preference kept; check the active connection"
                    })
                }
                refreshAll()
            } else {
                prefs.setMacPolicy(ssid, policy)
                postMessage("MAC mode saved for the next Quintz action on this network")
            }
        }
    }

    suspend fun findManualPreferCandidate(): AccessPointRadio? {
        val status = controller.refreshStatus(forceFresh = true)
        if (!status.isConnected || status.ssid.isEmpty()) return null
        val scanned = controller.scanRadios(freshForSsid = status.ssid)
        val currentFlags = scanned.firstOrNull {
            it.ssid == status.ssid && it.bssid.equals(status.bssid, ignoreCase = true)
        }?.flags.orEmpty()
        return scanned.filter {
            it.ssid == status.ssid &&
                (it.band == BandType.BAND_5_GHZ || it.band == BandType.BAND_6_GHZ) &&
                it.rssi >= -80 && it.ageSeconds <= 8L &&
                WifiSecurityPolicy.allowsAutomaticSwitch(status.securityType, currentFlags, it.flags) &&
                WifiSecurityPolicy.matchesSecurityType(status.securityType, it.flags)
        }.maxByOrNull { it.rssi }
    }

    fun forceLock5Ghz(
        password: String,
        macPolicy: com.quintz.wifi.model.MacAddressPolicy? = null,
        approvedBssid: String? = null,
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
                postMessage("Device is not connected to any Wi-Fi network")
                return@launch
            }

            if (password.isNotEmpty() && !prefs.isPasswordStorageAvailable) {
                postMessage(securePasswordError)
                return@launch
            }

            val policy = macPolicy ?: prefs.getMacPolicy(status.ssid) ?: prefs.defaultMacPolicy
            val success = controller.autoSelectAndLock5Ghz(
                status.ssid,
                password,
                policy,
                approvedBssid = approvedBssid,
                requestSource = requestSource,
                correlationId = correlationId
            )
            DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId source=$requestSource result=${if (success) "success" else "failure"} action=prefer_5ghz")
            refreshAll()
            if (success) {
                if (approvedBssid != null) {
                    val verified = controller.status.value
                    if (verified.isConnected && verified.ssid == status.ssid &&
                        verified.bssid.equals(approvedBssid, ignoreCase = true)) {
                        prefs.trustSelectedRadio(status.ssid, approvedBssid, verified.securityType)
                    }
                }
                val watchdogStarted = toggleWatchdog(true)
                postMessage("Preferred 5 GHz active with roaming allowed (${policy.displayName})" +
                    if (watchdogStarted) "; Watchdog active" else "; Watchdog could not start")
            } else {
                postMessage(when {
                    controller.lastPasswordStorageFailure -> securePasswordError
                    approvedBssid != null -> "Could not verify the selected 5 GHz radio. Rescan and retry."
                    else -> "Choose and connect to a 5 GHz radio in the app once to trust it, then retry. The radio must also be fresh and compatible."
                })
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
            if (password.isNotEmpty() && !prefs.isPasswordStorageAvailable) {
                postMessage(securePasswordError)
                return@launch
            }
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
                val verified = controller.status.value
                val trusted = if (verified.isConnected && verified.ssid == targetSsid &&
                    verified.bssid.equals(radio.bssid, ignoreCase = true)) {
                    prefs.trustSelectedRadio(targetSsid, radio.bssid, verified.securityType)
                } else false
                postMessage("Locked to AP [${radio.bssid}] on ${radio.band.displayName} (${policy.displayName})" +
                    (if (trusted) "; trusted for future automatic steering" else "; automatic recovery is unavailable until this radio can be trusted") +
                    (if (trusted && !prefs.isWatchdogEnabled) "; turn on Watchdog for automatic recovery" else ""))
            } else {
                postMessage(when {
                    controller.lastPasswordStorageFailure -> securePasswordError
                    !WifiSecurityPolicy.isSupported(radio.flags) -> "This radio's security could not be verified; no connection was changed"
                    else -> "Could not confirm lock to [${radio.bssid}]. Check signal strength."
                })
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
            if (success) {
                val stopped = WatchdogControl.stopIfNoTargets(getApplication(), prefs)
                postMessage(when {
                    stopped -> "Auto-Roam active; Watchdog stopped"
                    prefs.isWatchdogEnabled -> "Auto-Roam active; Watchdog kept for other saved networks"
                    else -> "Auto-Roam active"
                })
            } else {
                postMessage("Failed to unlock to Auto")
            }
            refreshAll()
        }
    }

    fun toggleWatchdog(enabled: Boolean): Boolean {
        val context = getApplication<Application>()
        if (enabled) {
            val started = WatchdogControl.start(context, prefs)
            if (started) {
                refreshBatteryOptimizationStatus()
                DiagnosticLogger.log("WATCHDOG", "result=start_requested source=preferred_5ghz")
                postMessage("Watchdog starting; each network follows its saved mode")
            } else {
                postMessage("Watchdog could not start")
            }
            return started
        } else {
            WatchdogControl.stop(context, prefs)
            _showBatteryOptimizationPrompt.value = false
            postMessage("Watchdog disabled")
            return true
        }
    }

    fun refreshBatteryOptimizationStatus() {
        val context = getApplication<Application>()
        val exempt = runCatching {
            context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrNull()
        _batteryOptimizationExempt.value = exempt
        if (exempt == true) {
            _showBatteryOptimizationPrompt.value = false
        } else if (exempt == false && prefs.isWatchdogEnabled && !prefs.batteryOptimizationPromptShown) {
            prefs.batteryOptimizationPromptShown = true
            _showBatteryOptimizationPrompt.value = true
        }
    }

    fun showBatteryOptimizationExplanation() {
        _showBatteryOptimizationPrompt.value = true
    }

    fun dismissBatteryOptimizationExplanation() {
        _showBatteryOptimizationPrompt.value = false
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
                    postMessage("Quintz tile added to Quick Settings!")
                } else if (result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                    _isTileAdded.value = true
                    prefs.isQuickTileAdded = true
                    postMessage("Tile is already in your Quick Settings panel")
                }
            }
        } else {
            postMessage("Swipe down twice and tap Edit (✎) to add Quintz tile")
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
                    val writeRes = ShizukuManager.exec("settings put secure sysui_qs_tiles ${ShizukuManager.escapeShellArg(newTiles)}")
                    if (writeRes.isSuccess) {
                        prefs.isQuickTileAdded = false
                        withContext(Dispatchers.Main) {
                            _isTileAdded.value = false
                            postMessage("Quick Settings tile removed")
                        }
                        return@launch
                    }
                }
            }
            withContext(Dispatchers.Main) {
                postMessage("Swipe down twice and tap Edit (✎) to remove Quintz tile")
            }
        }
    }
}
