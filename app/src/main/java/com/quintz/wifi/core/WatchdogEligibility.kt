package com.quintz.wifi.core

import com.quintz.wifi.data.WifiTargetMode
import com.quintz.wifi.model.*

/** One eligibility explanation for recovery and telemetry. Null means policy eligible. */
object WatchdogEligibility {
    fun rejection(status: WifiStatus, radio: AccessPointRadio, mode: WifiTargetMode,
                  pinnedBssid: String?, trustedSecurity: String?, threshold: Int, nowElapsed: Long): String? = when {
        radio.ssid != status.ssid -> "Different network"
        radio.observedAtElapsedMillis > 0 && nowElapsed - radio.observedAtElapsedMillis !in 0L..8_000L || radio.ageSeconds !in 0L..8L -> "Scan is stale"
        radio.rssi < threshold -> "Signal below recovery threshold"
        mode == WifiTargetMode.AUTO -> "Automatic steering is off for this network"
        mode == WifiTargetMode.PIN_BSSID && !radio.bssid.equals(pinnedBssid, true) -> "Different from the saved pin"
        mode == WifiTargetMode.PREFER_5_GHZ && radio.band !in setOf(BandType.BAND_5_GHZ, BandType.BAND_6_GHZ) -> "Outside the preferred band"
        trustedSecurity == null -> "Radio has not been trusted"
        !WifiSecurityPolicy.matchesSecurityType(trustedSecurity, radio.flags) -> "Security differs from the trusted radio"
        mode == WifiTargetMode.PIN_BSSID && status.securityType != trustedSecurity -> "Security differs from the current connection"
        mode == WifiTargetMode.PREFER_5_GHZ && (trustedSecurity !in setOf("2", "4") || status.securityType != trustedSecurity) -> "Security is ineligible for automatic band selection"
        else -> null
    }
}
