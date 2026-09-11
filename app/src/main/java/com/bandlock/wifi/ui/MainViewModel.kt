package com.bandlock.wifi.ui

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
import com.bandlock.wifi.core.WifiController
import com.bandlock.wifi.data.BandLockPreferences
import com.bandlock.wifi.model.AccessPointRadio
import com.bandlock.wifi.model.BandType
import com.bandlock.wifi.model.ShizukuState
import com.bandlock.wifi.model.WifiStatus
import com.bandlock.wifi.service.WatchdogService
import com.bandlock.wifi.shizuku.ShizukuManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val controller = WifiController(application)
    val prefs = BandLockPreferences(application)

    val shizukuState: StateFlow<ShizukuState> = ShizukuManager.state
    val wifiStatus: StateFlow<WifiStatus> = controller.status
    val radios: StateFlow<List<AccessPointRadio>> = controller.radios
    val isOperating: StateFlow<Boolean> = controller.isOperating

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _watchdogActive = MutableStateFlow(prefs.isWatchdogEnabled)
    val watchdogActive: StateFlow<Boolean> = _watchdogActive.asStateFlow()

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
            android.util.Log.e("BandLockVM", "Failed to register NetworkCallback", e)
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
                            android.util.Log.e("BandLockVM", "Failed to auto-start Watchdog", e)
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

    fun requestAddQuickTile() {
        val context = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val statusBarManager = context.getSystemService(android.app.StatusBarManager::class.java)
            statusBarManager?.requestAddTileService(
                android.content.ComponentName(context, com.bandlock.wifi.service.BandLockTileService::class.java),
                context.getString(com.bandlock.wifi.R.string.tile_name),
                android.graphics.drawable.Icon.createWithResource(context, com.bandlock.wifi.R.drawable.ic_wifi_5g),
                context.mainExecutor
            ) { result ->
                if (result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) {
                    _message.value = "BandLock tile added to Quick Settings!"
                } else if (result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                    _message.value = "Tile is already in your Quick Settings panel"
                }
            }
        } else {
            _message.value = "Swipe down twice and tap Edit (✎) to add BandLock tile"
        }
    }
}
