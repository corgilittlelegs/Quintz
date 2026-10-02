package com.quintz.wifi.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** A notification is a wake-up hint; scan ages are still checked before using a target. */
internal class WifiScanCompletion(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val events = Channel<Boolean>(Channel.CONFLATED)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                events.trySend(intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
            }
        }
    }
    private val registered = try {
        // Android 10+ sends this system broadcast for scans by the platform and other apps.
        // ContextCompat applies the appropriate receiver flags on Android 13+ as well.
        ContextCompat.registerReceiver(
            appContext, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_EXPORTED
        )
        true
    } catch (_: RuntimeException) {
        false // OEM/permission restrictions: bounded scan-result polling remains available.
    }

    suspend fun await(timeoutMillis: Long): Boolean? =
        withTimeoutOrNull(timeoutMillis) { events.receive() }

    override fun close() {
        if (registered) {
            try {
                appContext.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered by the platform.
            }
        }
        events.close()
    }
}
