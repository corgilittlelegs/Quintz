package com.bandlock.wifi.service

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.bandlock.wifi.R
import com.bandlock.wifi.core.WifiController
import com.bandlock.wifi.data.BandLockPreferences
import com.bandlock.wifi.model.BandType
import com.bandlock.wifi.shizuku.ShizukuManager
import com.bandlock.wifi.ui.MainActivity
import kotlinx.coroutines.*

class BandLockTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var controller: WifiController
    private lateinit var prefs: BandLockPreferences

    override fun onCreate() {
        super.onCreate()
        controller = WifiController(this)
        prefs = BandLockPreferences(this)
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
                        tile.label = "5 GHz Lock"
                        tile.subtitle = "Shizuku offline"
                        tile.updateTile()
                        Toast.makeText(this@BandLockTileService, "BandLock: Shizuku service offline", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val current = controller.refreshStatus()
                if (!current.isConnected || current.ssid.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        tile.state = Tile.STATE_INACTIVE
                        tile.label = "5 GHz Lock"
                        tile.subtitle = "Disconnected"
                        tile.updateTile()
                        Toast.makeText(this@BandLockTileService, "BandLock: Wi-Fi not connected", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val password = prefs.getPassword(current.ssid)

                if (current.isLockedToBssid && current.band == BandType.BAND_5_GHZ) {
                    // Currently locked -> Unlock to Auto
                    withContext(Dispatchers.Main) {
                        tile.state = Tile.STATE_INACTIVE
                        tile.label = "5 GHz Lock"
                        tile.subtitle = "Auto-Roam"
                        tile.updateTile()
                        Toast.makeText(this@BandLockTileService, "BandLock: Unlocking to Auto-Roam...", Toast.LENGTH_SHORT).show()
                    }
                    controller.unlockToAuto(current.ssid, password)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@BandLockTileService, "BandLock: Switched to Auto-Roam", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Auto/2.4G -> Lock to 5 GHz
                    if (!password.isNullOrEmpty()) {
                        withContext(Dispatchers.Main) {
                            tile.state = Tile.STATE_ACTIVE
                            tile.label = "5 GHz Lock"
                            tile.subtitle = "Locking to 5 GHz..."
                            tile.updateTile()
                            Toast.makeText(this@BandLockTileService, "BandLock: Locking to 5 GHz...", Toast.LENGTH_SHORT).show()
                        }
                        val success = controller.autoSelectAndLock5Ghz(current.ssid, password)
                        withContext(Dispatchers.Main) {
                            if (success) {
                                Toast.makeText(this@BandLockTileService, "BandLock: Successfully locked to 5 GHz", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@BandLockTileService, "BandLock: No 5 GHz radio found", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        // Password not saved! Open app so user can input it
                        withContext(Dispatchers.Main) {
                            tile.subtitle = "Password needed"
                            tile.updateTile()
                            Toast.makeText(this@BandLockTileService, "BandLock: Open app to save Wi-Fi password first", Toast.LENGTH_LONG).show()
                        }
                        val appIntent = Intent(this@BandLockTileService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        if (Build.VERSION.SDK_INT >= 34) {
                            val pendingIntent = android.app.PendingIntent.getActivity(
                                this@BandLockTileService, 0, appIntent,
                                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            )
                            startActivityAndCollapse(pendingIntent)
                        } else {
                            @Suppress("DEPRECATION")
                            startActivityAndCollapse(appIntent)
                        }
                        return@launch
                    }
                }

                updateTileState()
            } catch (e: Exception) {
                android.util.Log.e("BandLockTile", "Error handling tile click", e)
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
                    this@BandLockTileService,
                    R.drawable.ic_wifi_5g
                )
                tile.label = "5 GHz Lock"

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
