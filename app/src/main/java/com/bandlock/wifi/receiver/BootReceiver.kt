package com.bandlock.wifi.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.bandlock.wifi.data.BandLockPreferences
import com.bandlock.wifi.service.WatchdogService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefs = BandLockPreferences(context)
            if (prefs.isWatchdogEnabled) {
                val serviceIntent = Intent(context, WatchdogService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("BandLockBoot", "Failed to start WatchdogService on boot", e)
                }
            }
        }
    }
}
