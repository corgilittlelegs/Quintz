package com.quintz.wifi.telemetry

/** A scan observation older than this is unsuitable for a live AP comparison. */
const val CANDIDATE_FRESHNESS_MS = 8_000L
const val CANDIDATE_VISIBLE_MS = 60_000L

fun isFreshCandidate(observedAtMillis: Long, nowMillis: Long): Boolean =
    observedAtMillis > 0L && nowMillis - observedAtMillis in 0L..CANDIDATE_FRESHNESS_MS

fun isVisibleCandidate(observedAtMillis: Long, nowMillis: Long): Boolean =
    observedAtMillis > 0L && nowMillis - observedAtMillis in 0L..CANDIDATE_VISIBLE_MS

/** Stable across scans, sorting, and a candidate leaving or entering the list. */
fun candidateColorIndex(bssid: String, paletteSize: Int = 5): Int =
    (bssid.lowercase().hashCode().toLong() and 0x7fffffffL).rem(paletteSize).toInt()
