package com.quintz.wifi.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.widget.Toast
import com.quintz.wifi.R
import com.quintz.wifi.core.DiagnosticLogger
import com.quintz.wifi.core.WifiController
import com.quintz.wifi.data.Preferences
import com.quintz.wifi.model.BandType
import com.quintz.wifi.shizuku.ShizukuManager
import com.quintz.wifi.ui.MainActivity
import kotlinx.coroutines.*

class TileService : android.service.quicksettings.TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var controller: WifiController
    private lateinit var prefs: Preferences

    override fun onCreate() {
        super.onCreate()
        controller = WifiController(this)
        prefs = Preferences(this)
    }

    override fun onTileAdded() {
        super.onTileAdded()
        prefs.isQuickTileAdded = true
        TileStateTracker.notifyTileState(true)
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        prefs.isQuickTileAdded = false
        TileStateTracker.notifyTileState(false)
    }

    override fun onStartListening() {
        super.onStartListening()
        prefs.isQuickTileAdded = true
        TileStateTracker.notifyTileState(true)
        updateTileState()
    }

    private var isClickHandling = false

    override fun onClick() {
        super.onClick()
        val correlationId = DiagnosticLogger.newCorrelationId()
        val tile = qsTile ?: return
        if (isClickHandling) {
            DiagnosticLogger.log("USER_ACTION", "id=$correlationId source=quick_settings_tile callback=onClick result=ignored reason=already_handling")
            return
        }
        isClickHandling = true
        DiagnosticLogger.log(
            "USER_ACTION",
            "id=$correlationId source=quick_settings_tile callback=onClick tileState=${tile.state} subtitle='${tile.subtitle}'"
        )

        serviceScope.launch {
            try {
                if (!ShizukuManager.isReady()) {
                    DiagnosticLogger.log("USER_ACTION", "id=$correlationId source=quick_settings_tile result=aborted reason=shizuku_not_ready")
                    withContext(Dispatchers.Main) {
                        tile.state = Tile.STATE_UNAVAILABLE
                        tile.label = "Quintz"
                        tile.subtitle = "Shizuku offline"
                        tile.updateTile()
                        Toast.makeText(this@TileService, "Quintz: Shizuku service offline", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val current = controller.refreshStatus()
                DiagnosticLogger.log(
                    "USER_ACTION",
                    "id=$correlationId source=quick_settings_tile state_snapshot connected=${current.isConnected} ssid='${current.ssid}' bssid=${current.bssid} band=${current.band.displayName} rssi=${current.rssi} locked=${current.isLockedToBssid} preferred5G=${current.isPreferred5GHz}"
                )
                if (!current.isConnected || current.ssid.isEmpty()) {
                    DiagnosticLogger.log("USER_ACTION", "id=$correlationId source=quick_settings_tile result=aborted reason=not_connected")
                    withContext(Dispatchers.Main) {
                        tile.state = Tile.STATE_INACTIVE
                        tile.label = "Quintz"
                        tile.subtitle = "Disconnected"
                        tile.updateTile()
                        Toast.makeText(this@TileService, "Quintz: Wi-Fi not connected", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val password = prefs.getPassword(current.ssid)

                if (current.isSteeredOrLocked) {
                    // Currently steered or locked -> Unlock to Auto
                    withContext(Dispatchers.Main) {
                        tile.state = Tile.STATE_INACTIVE
                        tile.label = "Quintz"
                        tile.subtitle = "Auto-Roam"
                        tile.updateTile()
                        Toast.makeText(this@TileService, "Quintz: Unlocking to Auto-Roam...", Toast.LENGTH_SHORT).show()
                    }
                    val policy = prefs.getMacPolicy(current.ssid) ?: prefs.defaultMacPolicy
                    val success = controller.unlockToAuto(
                        current.ssid,
                        password,
                        macAddressPolicy = policy,
                        requestSource = "quick_settings_tile",
                        correlationId = correlationId
                    )
                    DiagnosticLogger.log("USER_ACTION", "id=$correlationId source=quick_settings_tile result=${if (success) "success" else "failure"} action=unlock_to_auto")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@TileService, "Quintz: Switched to Auto-Roam", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Auto mode -> Prefer 5 GHz
                    val policy = prefs.getMacPolicy(current.ssid) ?: prefs.defaultMacPolicy
                    if (!password.isNullOrEmpty()) {
                        withContext(Dispatchers.Main) {
                            tile.state = Tile.STATE_ACTIVE
                            tile.label = "Quintz"
                            tile.subtitle = "Steering to 5 GHz..."
                            tile.updateTile()
                            Toast.makeText(this@TileService, "Quintz: Preferring 5 GHz (${policy.displayName})...", Toast.LENGTH_SHORT).show()
                        }
                        val success = controller.autoSelectAndLock5Ghz(
                            current.ssid,
                            password,
                            policy,
                            requestSource = "quick_settings_tile",
                            correlationId = correlationId
                        )
                        DiagnosticLogger.log("USER_ACTION", "id=$correlationId source=quick_settings_tile result=${if (success) "success" else "failure"} action=prefer_5ghz")
                        withContext(Dispatchers.Main) {
                            if (success) {
                                Toast.makeText(this@TileService, "Quintz: Preferred 5 GHz active (Roam Allowed)", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@TileService, "Quintz: No 5 GHz radio found", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        // Password not saved! Open app so user can input it
                        withContext(Dispatchers.Main) {
                            tile.subtitle = "Password needed"
                            tile.updateTile()
                            Toast.makeText(this@TileService, "Quintz: Open app to save Wi-Fi password first", Toast.LENGTH_LONG).show()
                        }
                        val appIntent = Intent(this@TileService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        if (Build.VERSION.SDK_INT >= 34) {
                            val pendingIntent = PendingIntent.getActivity(
                                this@TileService, 0, appIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                            startActivityAndCollapse(pendingIntent)
                        } else {
                            @Suppress("DEPRECATION")
                            startActivityAndCollapse(appIntent)
                        }
                    }
                }

                updateTileState()
            } catch (e: Exception) {
                DiagnosticLogger.log("USER_ACTION", "id=$correlationId source=quick_settings_tile result=exception type=${e.javaClass.simpleName} message=${e.message}")
                android.util.Log.e("TileService", "Error handling tile click", e)
            } finally {
                isClickHandling = false
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return

        serviceScope.launch {
            val isReady = ShizukuManager.isReady()
            val status = if (isReady) controller.refreshStatus() else null

            withContext(Dispatchers.Main) {
                tile.icon = Icon.createWithResource(
                    this@TileService,
                    R.drawable.ic_qs_tile
                )
                tile.label = "Quintz"

                if (!isReady) {
                    tile.state = Tile.STATE_UNAVAILABLE
                    tile.subtitle = "Shizuku offline"
                } else if (status == null || !status.isConnected) {
                    tile.state = Tile.STATE_INACTIVE
                    tile.subtitle = "Disconnected"
                } else if (status.isLockedToBssid) {
                    tile.state = Tile.STATE_ACTIVE
                    val ch = com.quintz.wifi.model.AccessPointRadio.frequencyToChannel(status.frequency)
                    tile.subtitle = "Locked Ch $ch (${status.band.displayName})"
                } else if (status.isPreferred5GHz) {
                    tile.state = Tile.STATE_ACTIVE
                    val ch = com.quintz.wifi.model.AccessPointRadio.frequencyToChannel(status.frequency)
                    tile.subtitle = "Preferred 5 GHz (Ch $ch)"
                } else if (status.isPreferred5GHzFallback) {
                    tile.state = Tile.STATE_ACTIVE
                    tile.subtitle = "5G Fallback (${status.band.displayName})"
                } else {
                    tile.state = Tile.STATE_INACTIVE
                    tile.subtitle = "Auto-Roam (${status.band.displayName})"
                }
                tile.updateTile()
            }
        }
    }
}
