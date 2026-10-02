package com.quintz.wifi.core

import com.quintz.wifi.model.AccessPointRadio

internal const val SCAN_REFRESH_TIMEOUT_MS = 12_000L
internal const val SCAN_RESULT_POLL_MS = 1_000L

/** Shell ages have whole-second precision; clock rounding alone is not a new observation. */
internal fun hasNewScanObservation(
    radios: List<AccessPointRadio>,
    previousObservedAt: Map<String, Long>,
    requestStartedAtMillis: Long
): Boolean = radios.any { radio ->
    val previous = previousObservedAt[radio.bssid.lowercase()]
    radio.observedAtMillis > 0L && if (previous != null) {
        radio.observedAtMillis - previous > 1_000L
    } else {
        radio.observedAtMillis >= requestStartedAtMillis
    }
}

/** Fast foreground acquisition after success, capped backoff when Android supplies no update. */
internal fun foregroundScanDelayMillis(consecutiveFailures: Int): Long = when {
    consecutiveFailures <= 0 -> 1_500L
    consecutiveFailures == 1 -> 8_500L
    consecutiveFailures == 2 -> 17_000L
    else -> 30_000L
}
