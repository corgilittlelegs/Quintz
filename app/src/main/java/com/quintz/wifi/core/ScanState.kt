package com.quintz.wifi.core

enum class ScanPhase { IDLE, QUEUED, SCANNING, READY, FAILED, ACCESS_UNAVAILABLE }
data class ScanState(val phase: ScanPhase = ScanPhase.IDLE, val message: String? = null)

fun scannerEmptyMessage(state: ScanState, hasAnyRadios: Boolean): String = when (state.phase) {
    ScanPhase.ACCESS_UNAVAILABLE -> "Authorize Shizuku to scan nearby radios."
    ScanPhase.QUEUED -> "Waiting for the current scan to finish…"
    ScanPhase.SCANNING -> "Scanning nearby radios…"
    ScanPhase.FAILED -> state.message ?: "Scan failed. Refresh to try again."
    ScanPhase.READY -> if (hasAnyRadios) "No access points match this filter." else "Scan completed. No nearby access points found."
    ScanPhase.IDLE -> "Refresh to scan nearby radios."
}
