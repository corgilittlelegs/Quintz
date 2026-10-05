package com.quintz.wifi.telemetry

import com.quintz.wifi.model.WifiStatus

/** Attach redacted Android readings only to a recently verified, matching connection. */
fun connectedTelemetryObservation(native: WifiStatus, verified: WifiStatus, now: Long): WifiStatus? {
    if (!native.isConnected) return null
    if (native.rssi !in -110..-20) return null
    if (native.ssid.isNotEmpty() && native.bssid.isNotEmpty()) {
        return native.copy(observedAtMillis = now)
    }
    if (!verified.isConnected || verified.ssid.isEmpty() || verified.bssid.isEmpty() ||
        now - (verified.identityObservedAtMillis.takeIf { it > 0L } ?: verified.observedAtMillis) !in 0L..5_000L ||
        native.networkId == null || native.networkId != verified.networkId ||
        native.frequency <= 0 || native.frequency != verified.frequency) return null
    return native.copy(ssid = verified.ssid, bssid = verified.bssid, observedAtMillis = now)
}

fun startsNewTelemetrySegment(previous: TelemetrySample?, current: TelemetrySample): Boolean =
    previous == null || current.timestamp - previous.timestamp !in 1L..7_500L ||
        !current.activeBssid.equals(previous.activeBssid, ignoreCase = true) ||
        current.activeBand != previous.activeBand
