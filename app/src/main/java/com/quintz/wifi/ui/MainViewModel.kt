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

    val shizukuState: StateFlow<ShizukuState> = ShizukuManager.state
    val wifiStatus: StateFlow<WifiStatus> = controller.status
    val radios: StateFlow<List<AccessPointRadio>> = controller.radios
    val isOperating: StateFlow<Boolean> = controller.isOperating

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _watchdogActive = MutableStateFlow(prefs.isWatchdogEnabled)
    val watchdogActive: StateFlow<Boolean> = _watchdogActive.asStateFlow()

    private val _isTileAdded = MutableStateFlow(prefs.isQuickTileAdded)
    val isTileAdded: StateFlow<Boolean> = _isTileAdded.asStateFlow()

    private val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var pollingJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            viewModelScope.launch {
                if (ShizukuManager.isReady() && !controller.isOperating.value) {
                    controller.refreshStatus()
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            viewModelScope.launch {
                if (ShizukuManager.isReady() && !controller.isOperating.value) {
                    controller.refreshStatus()
                }
            }
        }

        override fun onLost(network: Network) {
            viewModelScope.launch {
                if (ShizukuManager.isReady() && !controller.isOperating.value) {
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

        viewModelScope.launch {
            TileStateTracker.tileAddedFlow.collect { added ->
                _isTileAdded.value = added
            }
        }

        checkTileStatus()

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
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (isActive) {
                if (ShizukuManager.isReady() && !controller.isOperating.value) {
                    controller.refreshStatus()
                }
                delay(2500)
            }
        }
    }

    fun stopForegroundPolling() {
        pollingJob?.cancel()
        pollingJob = null
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
            controller.scanRadios()
            checkTileStatus()
        }
    }

    fun requestShizukuPermission() {
        ShizukuManager.requestPermission()
    }

    fun forceLock5Ghz(password: String) {
        viewModelScope.launch {
            val status = wifiStatus.value
            if (!status.isConnected || status.ssid.isEmpty()) {
                _message.value = "Device is not connected to any Wi-Fi network"
                return@launch
            }

            val success = controller.autoSelectAndLock5Ghz(status.ssid, password)
            refreshAll()
            if (success) {
                _message.value = "Successfully locked to 5 GHz AP"
            } else {
                _message.value = "Could not find a 5 GHz radio for \"${status.ssid}\""
            }
        }
    }

    fun lockToSpecificRadio(radio: AccessPointRadio, password: String) {
        viewModelScope.launch {
            val status = wifiStatus.value
            val success = controller.lockToBssid(status.ssid, radio.bssid, password)
            refreshAll()
            if (success) {
                _message.value = "Locked to AP [${radio.bssid}] on ${radio.band.displayName}"
            } else {
                _message.value = "Failed to lock to AP [${radio.bssid}]"
            }
        }
    }

    fun unlockToAuto() {
        viewModelScope.launch {
            val status = wifiStatus.value
            val success = controller.unlockToAuto(status.ssid)
            refreshAll()
            if (success) {
                _message.value = "Reverted to Auto-Roam mode"
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
                android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_wifi_5g),
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
