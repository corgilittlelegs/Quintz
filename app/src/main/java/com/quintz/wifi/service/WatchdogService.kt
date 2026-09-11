package com.quintz.wifi.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.quintz.wifi.R
import com.quintz.wifi.core.WifiController
import com.quintz.wifi.data.Preferences
import com.quintz.wifi.model.BandType
import com.quintz.wifi.shizuku.ShizukuManager
import com.quintz.wifi.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WatchdogService : Service() {

    private val serviceJob = kotlinx.coroutines.SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var controller: WifiController
    private lateinit var prefs: Preferences

    override fun onCreate() {
        super.onCreate()
        controller = WifiController(this)
        prefs = Preferences(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Monitoring Wi-Fi band state..."))
        startWatchdogLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Monitoring Wi-Fi band state..."))
        return START_STICKY
    }

    private fun startWatchdogLoop() {
        scope.launch {
            while (isActive) {
                var loopDelay = 15000L
                if (prefs.isWatchdogEnabled && ShizukuManager.isReady()) {
                    try {
                        val status = controller.refreshStatus()
                        if (status.isConnected && status.ssid.isNotEmpty()) {
                            val savedPassword = prefs.getPassword(status.ssid)

                            if (status.isLockedToBssid && status.band == BandType.BAND_5_GHZ) {
                                // Passive RSSI check: no active radio scan needed when healthy on 5 GHz
                                if (status.rssi < prefs.fallbackThresholdRssi && status.rssi > -120) {
                                    // Fallback to auto to preserve internet
                                    updateNotification("Low 5 GHz signal (${status.rssi} dBm). Falling back to Auto...")
                                    controller.unlockToAuto(status.ssid, savedPassword)
                                    loopDelay = 8000L
                                } else {
                                    updateNotification("Locked to 5 GHz • ${status.ssid} (${status.rssi} dBm)")
                                }
                            } else if (!status.isLockedToBssid && prefs.lastTargetBand == "5GHz") {
                                // In fallback/Auto mode: perform scan to check if 5 GHz is back strong
                                loopDelay = 10000L
                                val radios = controller.scanRadios()
                                val strong5G = radios.firstOrNull {
                                    (it.band == BandType.BAND_5_GHZ || it.band == BandType.BAND_6_GHZ) &&
                                            it.rssi >= prefs.recoveryThresholdRssi
                                }
                                if (strong5G != null && !savedPassword.isNullOrEmpty()) {
                                    updateNotification("Strong 5 GHz found (${strong5G.rssi} dBm). Locking to 5 GHz...")
                                    controller.lockToBssid(status.ssid, strong5G.bssid, savedPassword)
                                } else {
                                    updateNotification("Auto-Roam (${status.band.displayName}) • Monitoring for 5 GHz")
                                }
                            } else {
                                updateNotification("Auto-Roam • ${status.ssid}")
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("Watchdog", "Watchdog loop error", e)
                    }
                }
                delay(loopDelay)
            }
        }
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Quintz Watchdog",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors 5 GHz Wi-Fi signal and manages seamless fallbacks"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Quintz Watchdog Active")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_wifi_5g)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "watchdog_channel"
        private const val NOTIFICATION_ID = 4001
    }
}
