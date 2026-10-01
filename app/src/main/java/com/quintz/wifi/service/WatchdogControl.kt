package com.quintz.wifi.service

import android.content.Context
import android.content.Intent
import android.os.Build
import com.quintz.wifi.core.DiagnosticLogger
import com.quintz.wifi.data.Preferences

/** Keeps the saved watchdog setting and foreground service in sync. */
object WatchdogControl {
    fun start(context: Context, prefs: Preferences): Boolean {
        val wasEnabled = prefs.isWatchdogEnabled
        prefs.isWatchdogEnabled = true
        val intent = Intent(context, WatchdogService::class.java)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (e: Exception) {
            prefs.isWatchdogEnabled = wasEnabled
            DiagnosticLogger.log("WATCHDOG", "result=start_failed reason=${e.javaClass.simpleName}")
            false
        }
    }

    fun stop(context: Context, prefs: Preferences) {
        prefs.isWatchdogEnabled = false
        context.stopService(Intent(context, WatchdogService::class.java))
    }

    /** Call only after a user-requested unlock has been verified. */
    fun stopIfNoTargets(context: Context, prefs: Preferences): Boolean {
        if (prefs.hasWatchdogTargets()) return false
        val wasEnabledOrRunning = prefs.isWatchdogEnabled || WatchdogService.isRunning.value
        stop(context, prefs)
        if (wasEnabledOrRunning) {
            DiagnosticLogger.log("WATCHDOG", "result=stopped reason=no_saved_steering_targets")
        }
        return wasEnabledOrRunning
    }
}
