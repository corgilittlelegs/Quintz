package com.quintz.wifi.data

private const val TARGET_MODE_PREFIX = "wifi_target_mode_"

/** Only explicit per-network steering targets need the background watchdog. */
internal fun hasWatchdogTargets(values: Map<String, *>): Boolean = values.any { (key, value) ->
    key.startsWith(TARGET_MODE_PREFIX) && key.length > TARGET_MODE_PREFIX.length &&
        (value == WifiTargetMode.PREFER_5_GHZ.name || value == WifiTargetMode.PIN_BSSID.name)
}
