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
import com.quintz.wifi.core.foregroundScanDelayMillis
import com.quintz.wifi.core.WifiSecurityPolicy
import com.quintz.wifi.data.Preferences
import com.quintz.wifi.data.WifiTargetMode
import com.quintz.wifi.model.AccessPointRadio
import com.quintz.wifi.model.BandType
import com.quintz.wifi.model.ShizukuState
import com.quintz.wifi.model.WifiStatus
import com.quintz.wifi.model.SavedCredentialState
import com.quintz.wifi.model.WifiOperationKind
import com.quintz.wifi.core.MacPolicyChangeResult
import com.quintz.wifi.core.WifiActionContext
import com.quintz.wifi.core.WifiActionResult
import com.quintz.wifi.core.CredentialEditor
import com.quintz.wifi.core.CredentialStore
import com.quintz.wifi.core.profile.ProfileBackupStore
import com.quintz.wifi.core.profile.ProfileRecoveryCoordinator
import com.quintz.wifi.core.profile.TransitionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import com.quintz.wifi.service.QuickTileSpec
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
    internal val wifiFlow = WifiFlowState()
    init { ProfileRecoveryCoordinator.initialize(application) }
    val recoveryState = ProfileRecoveryCoordinator.state
    val scanState = controller.scanState
    val settingsChanges = prefs.changes
    private val credentialEditor = CredentialEditor({ controller.refreshStatus(forceFresh = true) },
        { ProfileBackupStore(application).exists() }, object : CredentialStore {
            override val available get() = prefs.isPasswordStorageAvailable
            override fun save(ssid: String, password: String) = prefs.savePassword(ssid, password)
            override fun remove(ssid: String) = prefs.removePassword(ssid)
        })

    private suspend fun awaitRecovery(): Boolean {
        val ready = ProfileRecoveryCoordinator.ensureReady(getApplication())
        if (!ready) postMessage(recoveryState.value.message ?: "Profile recovery is pending.")
        return ready
    }

    fun retryProfileRecovery() {
        viewModelScope.launch {
            if (ProfileRecoveryCoordinator.ensureReady(getApplication(), retry = true)) {
                postMessage("Profile recovery verified.")
                refreshAll()
            } else postMessage(recoveryState.value.message ?: "Profile recovery is pending.")
        }
    }

    fun retrySecureStorage() {
        viewModelScope.launch {
            val available = withContext(Dispatchers.IO) { prefs.retrySecureStorage() }
            _savedCredentialState.value = prefs.savedCredentialState(wifiStatus.value.ssid)
            postMessage(if (available) "Secure storage is available. Enter any missing password again." else securePasswordError)
        }
    }

    fun resolveMigrationConflicts(useFallback: Boolean) {
        viewModelScope.launch {
            val resolved = withContext(Dispatchers.IO) { prefs.resolveMigrationConflicts(useFallback) }
            if (!resolved) postMessage("Saved settings could not be updated. Try again.")
            else { reloadSettings(); refreshAll() }
        }
    }

    private fun reloadSettings() {
        _isDarkMode.value = prefs.isDarkMode
        _isTileAdded.value = prefs.isQuickTileAdded
        _savedCredentialState.value = prefs.savedCredentialState(wifiStatus.value.ssid)
    }

    private val securePasswordError = "Cannot save the Wi-Fi password securely. Unlock the device, check Android's secure storage, then retry. No password was saved in plain text."

    private val _telemetryState = MutableStateFlow(TelemetryGraphState())
    val telemetryState: StateFlow<TelemetryGraphState> = _telemetryState.asStateFlow()
    private val _telemetryClock = MutableStateFlow(android.os.SystemClock.elapsedRealtime())
    val telemetryClock: StateFlow<Long> = _telemetryClock.asStateFlow()

    private val maxTelemetrySamples = 180
    private val telemetryBuffer = ArrayDeque<TelemetrySample>()
    private val candidateHistory = mutableMapOf<String, CandidateSample>()
    private var previousSsid = ""
    private var previousBssid = ""
    private var previousBand = BandType.UNKNOWN
    private var lastRecordedStatusObservationMillis = 0L
    private var telemetryJob: Job? = null
    private var telemetryRequested = false
    private var nativeTelemetryObservation: WifiStatus? = null

    val shizukuState: StateFlow<ShizukuState> = ShizukuManager.state
    val wifiStatus: StateFlow<WifiStatus> = controller.status
    val radios: StateFlow<List<AccessPointRadio>> = controller.radios
    val isOperating: StateFlow<Boolean> = controller.isOperating
    val currentOperation: StateFlow<WifiOperationKind> = controller.currentOperation
    val isScanning: StateFlow<Boolean> = controller.isScanning
    val isScanQueued: StateFlow<Boolean> = controller.isScanQueued
    private val _savedCredentialState = MutableStateFlow(prefs.savedCredentialState(""))
    val savedCredentialState: StateFlow<SavedCredentialState> = _savedCredentialState.asStateFlow()

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
                setTileAdded(added)
            }
        }

        checkTileStatus()

        viewModelScope.launch {
            wifiStatus.collect {
                _savedCredentialState.value = prefs.savedCredentialState(it.ssid)
                recordTelemetrySample()
            }
        }

        viewModelScope.launch {
            prefs.changes.collect {
                reloadSettings()
                if (isForeground && prefs.isWatchdogEnabled && ShizukuManager.isReady() &&
                    !WatchdogService.isRunning.value && awaitRecovery()) WatchdogControl.start(getApplication(), prefs)
            }
        }

        viewModelScope.launch {
            shizukuState.collectLatest { state ->
                if (state.isPermissionGranted) {
                    if (!awaitRecovery()) return@collectLatest
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
                } else controller.invalidateRadios()
            }
        }
    }

    fun startForegroundPolling() {
        isForeground = true
        if (pollingJob?.isActive != true) {
            pollingJob = viewModelScope.launch {
                while (isActive) {
                    if (!controller.isOperating.value && (!telemetryRequested || _telemetryState.value.isPaused || android.os.SystemClock.elapsedRealtime() - controller.status.value.profileObservedAtMillis > 60_000L)) {
                        controller.refreshStatus()
                    }
                    delay(2500)
                }
            }
        }
        if (scannerRequested) startScannerPolling()
        if (telemetryRequested && !_telemetryState.value.isPaused) startTelemetryPolling()
    }

    private fun startTelemetryPolling() {
        if (!isForeground || !telemetryRequested || _telemetryState.value.isPaused || telemetryJob?.isActive == true) return
        telemetryJob = viewModelScope.launch {
            while (isActive) {
                val cycleStarted = android.os.SystemClock.elapsedRealtime()
                if (isForeground) {
                    if (telemetryRequested && !_telemetryState.value.isPaused && !controller.isOperating.value) {
                        val native = withContext(Dispatchers.IO) { controller.getNativeWifiStatus() }
                        val observedAt = android.os.SystemClock.elapsedRealtime()
                        nativeTelemetryObservation = connectedTelemetryObservation(
                            native, controller.status.value, observedAt
                        )
                        nativeTelemetryObservation?.let { controller.publishConnectedObservation(it) }
                        // Devices without location access redact the identity. Read identity and RSSI
                        // together through the existing status path rather than attaching an unknown radio.
                        if (nativeTelemetryObservation == null) controller.refreshStatus()
                        if (com.quintz.wifi.BuildConfig.DEBUG) {
                            val reading = nativeTelemetryObservation ?: controller.status.value
                            android.util.Log.d("QuintzTelemetry", "reading at=${reading.observedAtMillis} source=${if (nativeTelemetryObservation != null) "native" else "status"} rssi=${reading.rssi} frequency=${reading.frequency}")
                        }
                    } else {
                        nativeTelemetryObservation = null
                    }
                    _telemetryClock.value = android.os.SystemClock.elapsedRealtime()
                    recordTelemetrySample()
                }
                delay((1_000L - (android.os.SystemClock.elapsedRealtime() - cycleStarted)).coerceAtLeast(100L))
            }
        }
    }

    private fun stopTelemetryPolling() {
        telemetryJob?.cancel()
        telemetryJob = null
        nativeTelemetryObservation = null
    }

    fun setTelemetryActive(active: Boolean) {
        telemetryRequested = active
        if (!active) stopTelemetryPolling() else if (isForeground && !_telemetryState.value.isPaused) startTelemetryPolling()
    }

    fun startScannerPolling() {
        if (scannerJob?.isActive == true) return
        scannerJob = viewModelScope.launch {
            var consecutiveFailures = 0
            while (isActive) {
                if (isForeground && ShizukuManager.isReady() && !controller.isOperating.value &&
                    !controller.isScanning.value && !controller.isScanQueued.value) {
                    controller.scanRadios()
                    consecutiveFailures = if (controller.lastScanSucceeded) 0 else (consecutiveFailures + 1).coerceAtMost(3)
                }
                delay(foregroundScanDelayMillis(consecutiveFailures))
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
            nowTimestampMillis = if (paused) android.os.SystemClock.elapsedRealtime() else _telemetryState.value.nowTimestampMillis
        )
        if (paused) stopTelemetryPolling() else { startTelemetryPolling(); recordTelemetrySample() }
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
        if (!isForeground || !telemetryRequested || _telemetryState.value.isPaused) return
        _telemetryState.value = _telemetryState.value.copy(
            selectedCandidateBssid = bssid
        )
    }

    fun recordTelemetrySample() {
        if (_telemetryState.value.isPaused) return
        val verified = controller.status.value
        val status = nativeTelemetryObservation?.takeIf {
            it.observedAtMillis > verified.observedAtMillis
        } ?: verified
        val now = android.os.SystemClock.elapsedRealtime()
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
                eligibilityReason = apList.firstOrNull { it.bssid.equals(cand.bssid, true) }?.let { radio ->
                    com.quintz.wifi.core.WatchdogEligibility.rejection(status, radio, prefs.getWifiTargetMode(status.ssid) ?: WifiTargetMode.AUTO,
                        prefs.getPinnedBssid(status.ssid), prefs.getTrustedRadioSecurity(status.ssid, radio.bssid), prefs.recoveryThresholdRssi,
                        android.os.SystemClock.elapsedRealtime())
                } ?: if (!candidatesMap.containsKey(cand.bssid.lowercase())) "Missing from the latest scan" else null,
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
                while (telemetryBuffer.isNotEmpty() && telemetryBuffer.first().timestamp < now - TELEMETRY_HISTORY_RETENTION_MS) {
                    telemetryBuffer.removeFirst()
                }
                telemetryBuffer.toList()
            }
            val rssiValues = samples.filter { it.timestamp in (now - TELEMETRY_WINDOW_MS)..now }
                .map { it.activeRssi }.filter { it in -120..-10 }
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
                (telemetryBuffer.firstOrNull()?.timestamp ?: now) < now - TELEMETRY_HISTORY_RETENTION_MS
            ) {
                telemetryBuffer.removeFirst()
            }
        }

        val sampleList = synchronized(telemetryBuffer) { telemetryBuffer.toList() }
        val rssiValues = sampleList.filter { it.timestamp in (now - TELEMETRY_WINDOW_MS)..now }
            .map { it.activeRssi }.filter { it in -120..-10 }
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
        wifiFlow.clearSecrets()
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
        correlationId: String = DiagnosticLogger.newCorrelationId(),
        actionContext: WifiActionContext = WifiActionContext.capture(wifiStatus.value)
    ) {
        DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId source=$requestSource action=set_mac_policy ssid='$ssid' policy=${policy.displayName}")
        viewModelScope.launch {
            if (!awaitRecovery()) return@launch
            val result = controller.changeMacPolicyResult(ssid, policy, actionContext)
            DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId source=$requestSource action=set_mac_policy result=$result")
            postMessage(when (result.outcome) {
                MacPolicyChangeResult.UNCHANGED -> "${policy.displayName} is already configured. No reconnect requested."
                MacPolicyChangeResult.SAVED -> "${policy.displayName} saved in Android. Applies on next connection; no reconnect requested."
                MacPolicyChangeResult.RECONNECTED -> "${policy.displayName} verified after reconnect. Steering mode kept."
                MacPolicyChangeResult.FAILED -> result.action.message
            })
            controller.refreshStatus(forceFresh = true)
        }
    }

    fun preparePrefer(source: String) {
        if (wifiFlow.isPreparingPrefer || isOperating.value) return
        val origin = WifiActionContext.capture(wifiStatus.value)
        if (wifiStatus.value.securityType !in setOf("2", "4")) {
            postMessage("Band preference needs a supported WPA2/WPA3 network. Select a specific radio for Open/OWE Wi-Fi.")
            return
        }
        wifiFlow.isPreparingPrefer = true
        viewModelScope.launch {
            try {
                if (!awaitRecovery()) return@launch
                val radio = findManualPreferCandidate()
                if (!origin.matches(wifiStatus.value)) postMessage(WifiActionResult(TransitionResult.NetworkChanged).message)
                else if (radio == null) postMessage("No fresh compatible 5/6 GHz radio found for this network.")
                else {
                    wifiFlow.pendingPreferRadio = radio
                    wifiFlow.pendingPreferSource = source
                    wifiFlow.pendingPreferContext = origin
                }
            } catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { postMessage("Could not find a radio. Check Shizuku and refresh.") }
            finally { wifiFlow.isPreparingPrefer = false }
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
        correlationId: String = DiagnosticLogger.newCorrelationId(),
        actionContext: WifiActionContext = WifiActionContext.capture(wifiStatus.value),
        expectedSsid: String = actionContext.ssid
    ) {
        DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId source=$requestSource action=prefer_5ghz_requested")
        viewModelScope.launch {
            if (!awaitRecovery()) return@launch
            val status = controller.refreshStatus(forceFresh = true)
            if (!actionContext.matches(status) || status.ssid != expectedSsid) {
                postMessage(WifiActionResult(TransitionResult.NetworkChanged).message); return@launch
            }
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
            val result = controller.autoSelectAndLock5GhzResult(
                status.ssid,
                password,
                policy,
                approvedBssid = approvedBssid,
                requestSource = requestSource,
                correlationId = correlationId, actionContext = actionContext
            )
            val success = result.verified
            DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId source=$requestSource result=${if (success) "success" else "failure"} action=prefer_5ghz")
            refreshAll()
            if (success) {
                val watchdogStarted = toggleWatchdog(true)
                postMessage("Preferred 5 GHz active; saved MAC setting: ${policy.displayName}" +
                    if (watchdogStarted) "; Watchdog active" else "; Watchdog could not start")
            } else {
                postMessage(result.message)
            }
        }
    }

    fun lockToSpecificRadio(
        radio: AccessPointRadio,
        password: String,
        macPolicy: com.quintz.wifi.model.MacAddressPolicy? = null,
        requestSource: String = "main_screen_radio_list",
        correlationId: String = DiagnosticLogger.newCorrelationId(),
        actionContext: WifiActionContext = WifiActionContext.capture(wifiStatus.value)
    ) {
        DiagnosticLogger.log(
            "WIFI_ACTION",
            "id=$correlationId source=$requestSource action=lock_specific_radio requestedSsid='${radio.ssid}' targetBssid=${radio.bssid} band=${radio.band.displayName} rssi=${radio.rssi}"
        )
        viewModelScope.launch {
            if (!awaitRecovery()) return@launch
            val status = wifiStatus.value
            val targetSsid = radio.ssid.ifEmpty { status.ssid }
            val policy = macPolicy ?: prefs.getMacPolicy(targetSsid) ?: prefs.defaultMacPolicy
            if (password.isNotEmpty() && !prefs.isPasswordStorageAvailable) {
                postMessage(securePasswordError)
                return@launch
            }
            val result = controller.lockToBssidResult(
                targetSsid,
                radio.bssid,
                password,
                macAddressPolicy = policy,
                unpinProfileForRoaming = false,
                requestSource = requestSource,
                correlationId = correlationId, actionContext = actionContext
            )
            val success = result.verified
            DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId source=$requestSource result=${if (success) "success" else "failure"} action=lock_specific_radio targetBssid=${radio.bssid}")
            refreshAll()
            if (success) {
                val verified = controller.status.value
                val trusted = prefs.getTrustedRadioSecurity(targetSsid, radio.bssid) != null
                postMessage("Locked to AP [${radio.bssid}] on ${radio.band.displayName}; saved MAC setting: ${policy.displayName}" +
                    (if (trusted) "; trusted for future automatic steering" else "; automatic recovery is unavailable until this radio can be trusted") +
                    (if (trusted && !prefs.isWatchdogEnabled) "; turn on Watchdog for automatic recovery" else ""))
            } else {
                postMessage(result.message)
            }
        }
    }

    fun unlockToAuto(
        requestSource: String = "main_screen_primary_button",
        correlationId: String = DiagnosticLogger.newCorrelationId(),
        actionContext: WifiActionContext = WifiActionContext.capture(wifiStatus.value)
    ) {
        DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId source=$requestSource action=unlock_to_auto_requested")
        viewModelScope.launch {
            if (!awaitRecovery()) return@launch
            val status = wifiStatus.value
            DiagnosticLogger.log(
                "WIFI_ACTION",
                "id=$correlationId source=$requestSource action=unlock_state_snapshot connected=${status.isConnected} ssid='${status.ssid}' bssid=${status.bssid} band=${status.band.displayName} rssi=${status.rssi}"
            )
            val result = controller.unlockToAutoResult(actionContext.ssid, requestSource = requestSource,
                correlationId = correlationId, actionContext = actionContext)
            val success = result.verified
            DiagnosticLogger.log("WIFI_ACTION", "id=$correlationId source=$requestSource result=${if (success) "success" else "failure"} action=unlock_to_auto")
            if (success) {
                val stopped = WatchdogControl.stopIfNoTargets(getApplication(), prefs)
                postMessage(when {
                    stopped -> "Auto-Roam active; Watchdog stopped"
                    prefs.isWatchdogEnabled -> "Auto-Roam active; Watchdog kept for other saved networks"
                    else -> "Auto-Roam active"
                })
            } else {
                postMessage(result.message)
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

    fun forgetPassword(target: WifiActionContext) = editCredential(target, null)
    fun forgetPassword(ssid: String) = editCredential(WifiActionContext.capture(wifiStatus.value), null)
    fun replacePassword(target: WifiActionContext, password: String) = editCredential(target, password)
    fun replacePassword(password: String) = editCredential(WifiActionContext.capture(wifiStatus.value), password)

    private fun editCredential(target: WifiActionContext, password: String?) {
        viewModelScope.launch {
            if (!awaitRecovery()) return@launch
            val result = withContext(Dispatchers.IO) { credentialEditor.edit(target, password) }
            postMessage(result.message)
            _savedCredentialState.value = prefs.savedCredentialState(wifiStatus.value.ssid)
        }
    }

    fun getSavedPassword(ssid: String): String = prefs.getPassword(ssid).orEmpty()

    private var tileStatusJob: Job? = null

    private fun setTileAdded(added: Boolean) {
        tileStatusJob?.cancel()
        prefs.isQuickTileAdded = added
        _isTileAdded.value = added
    }

    fun checkTileStatus() {
        tileStatusJob?.cancel()
        tileStatusJob = viewModelScope.launch(Dispatchers.IO) {
            val isAdded = queryIsTileAdded()
            withContext(Dispatchers.Main) {
                prefs.isQuickTileAdded = isAdded
                _isTileAdded.value = isAdded
            }
        }
    }

    private suspend fun queryIsTileAdded(): Boolean {
        // 1. Query via Shizuku: inspect sysui_qs_tiles in secure settings
        if (ShizukuManager.isReady()) {
            try {
                val res = ShizukuManager.exec("settings get secure sysui_qs_tiles")
                if (res.isSuccess && res.stdout.trim().let { it.isNotEmpty() && it != "null" }) {
                    val added = isOwnTileSpecPresent(res.stdout)
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
            if (tiles != null && tiles.trim() != "null") {
                val added = isOwnTileSpecPresent(tiles)
                return added
            }
        } catch (_: Exception) {}

        // 3. Fallback to cached preference
        return prefs.isQuickTileAdded
    }

    private fun isOwnTileSpecPresent(tiles: String): Boolean =
        tiles.split(",").any { isOwnTileSpec(it) }

    private fun isOwnTileSpec(spec: String): Boolean = QuickTileSpec.matches(
        spec, getApplication<Application>().packageName, TileService::class.java.name
    )

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
                    setTileAdded(true)
                    postMessage("Quintz tile added to Quick Settings!")
                } else if (result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                    setTileAdded(true)
                    postMessage("Tile is already in your Quick Settings panel")
                }
            }
        } else {
            postMessage("Swipe down twice and tap Edit (✎) to add Quintz tile")
        }
    }

    private var tileRemovalJob: Job? = null

    fun removeQuickTile() {
        if (tileRemovalJob?.isActive == true) return
        tileStatusJob?.cancel()
        tileRemovalJob = viewModelScope.launch {
            if (!ShizukuManager.isReady()) {
                postMessage("Shizuku is offline. Swipe down twice and tap Edit (✎) to remove Quintz tile")
                return@launch
            }
            val component = android.content.ComponentName(
                getApplication<Application>(), TileService::class.java
            ).flattenToString()
            val result = withContext(Dispatchers.IO) {
                ShizukuManager.exec("cmd statusbar remove-tile ${ShizukuManager.escapeShellArg(component)}")
            }
            if (!result.isSuccess) {
                postMessage("Could not remove tile. Swipe down twice and tap Edit (✎) to remove it")
                return@launch
            }
            // SystemUI handles the command asynchronously; an exit code alone is not proof.
            repeat(8) {
                delay(250)
                val removed = withContext(Dispatchers.IO) {
                    val tiles = ShizukuManager.exec("settings get secure sysui_qs_tiles")
                    tiles.isSuccess && tiles.stdout.trim() != "null" &&
                        !isOwnTileSpecPresent(tiles.stdout)
                }
                if (removed) {
                    setTileAdded(false)
                    postMessage("Quick Settings tile removed")
                    return@launch
                }
            }
            checkTileStatus()
            postMessage("Tile removal was not confirmed. Swipe down twice and tap Edit (✎) to remove it")
        }
    }
}
