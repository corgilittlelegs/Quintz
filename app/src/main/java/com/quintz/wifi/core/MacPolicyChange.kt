package com.quintz.wifi.core

import com.quintz.wifi.data.WifiTargetMode
import com.quintz.wifi.model.MacAddressPolicy

enum class MacPolicyChangePlan { UNCHANGED, SAVE_ONLY, RECONNECT }
enum class MacPolicyChangeResult { UNCHANGED, SAVED, RECONNECTED, FAILED }

/** A MAC selection follows this SSID's intent, never the global watchdog state. */
fun macPolicyChangePlan(current: MacAddressPolicy, selected: MacAddressPolicy, mode: WifiTargetMode): MacPolicyChangePlan =
    when {
        current == selected -> MacPolicyChangePlan.UNCHANGED
        mode == WifiTargetMode.AUTO -> MacPolicyChangePlan.SAVE_ONLY
        else -> MacPolicyChangePlan.RECONNECT
    }
