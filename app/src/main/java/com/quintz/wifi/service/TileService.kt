package com.quintz.wifi.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.widget.Toast
import com.quintz.wifi.R
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

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    private var isClickHandling = false

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        if (isClickHandling) return
        isClickHandling = true

        serviceScope.launch {
            try {
                if (!ShizukuManager.isReady()) {
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
                if (!current.isConnected || current.ssid.isEmpty()) {
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

                if (current.isLockedToBssid && current.band == BandType.BAND_5_GHZ) {
                    // Currently locked -> Unlock to Auto
                    withContext(Dispatchers.Main) {
                        tile.state = Tile.STATE_INACTIVE
                        tile.label = "Quintz"
                        tile.subtitle = "Auto-Roam"
                        tile.updateTile()
                        Toast.makeText(this@TileService, "Quintz: Unlocking to Auto-Roam...", Toast.LENGTH_SHORT).show()
                    }
                    controller.unlockToAuto(current.ssid, password)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@TileService, "Quintz: Switched to Auto-Roam", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Auto/2.4G -> Lock to 5 GHz
                    if (!password.isNullOrEmpty()) {
                        withContext(Dispatchers.Main) {
                            tile.state = Tile.STATE_ACTIVE
                            tile.label = "Quintz"
                            tile.subtitle = "Locking to 5 GHz..."
                            tile.updateTile()
                            Toast.makeText(this@TileService, "Quintz: Locking to 5 GHz...", Toast.LENGTH_SHORT).show()
                        }
                        val success = controller.autoSelectAndLock5Ghz(current.ssid, password)
                        withContext(Dispatchers.Main) {
                            if (success) {
                                Toast.makeText(this@TileService, "Quintz: Successfully locked to 5 GHz", Toast.LENGTH_SHORT).show()
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
                android.util.Log.e("TileService", "Error handling tile click", e)
            } finally {
                isClickHandling = false
            }
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return

        serviceScope.launch {
            val isReady = ShizukuManager.isReady()
            val status = if (isReady) controller.refreshStatus() else null

            withContext(Dispatchers.Main) {
                tile.icon = Icon.createWithResource(
                    this@TileService,
                    R.drawable.ic_wifi_5g
                )
                tile.label = "Quintz"

                if (!isReady) {
                    tile.state = Tile.STATE_UNAVAILABLE
                    tile.subtitle = "Shizuku offline"
                } else if (status == null || !status.isConnected) {
                    tile.state = Tile.STATE_INACTIVE
                    tile.subtitle = "Disconnected"
                } else if (status.isLockedToBssid && status.band == BandType.BAND_5_GHZ) {
                    tile.state = Tile.STATE_ACTIVE
                    tile.subtitle = "Locked (${status.frequency} MHz)"
                } else {
                    tile.state = Tile.STATE_INACTIVE
                    tile.subtitle = "Auto-Roam (${status.band.displayName})"
                }
                tile.updateTile()
            }
        }
    }
}
