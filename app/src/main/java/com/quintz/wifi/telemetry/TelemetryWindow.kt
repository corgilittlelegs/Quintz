package com.quintz.wifi.telemetry

const val TELEMETRY_WINDOW_MS = 60_000L
const val CANDIDATE_LINE_MAX_GAP_MS = 20_000L
// Retain the segment crossing the left edge, including slower candidate observations.
const val TELEMETRY_HISTORY_RETENTION_MS = TELEMETRY_WINDOW_MS + CANDIDATE_LINE_MAX_GAP_MS

/** Sorted readings, plus one predecessor so the canvas can clip the crossing segment. */
fun <T> readingsWithWindowPredecessor(
    readings: List<T>,
    windowStart: Long,
    windowEnd: Long,
    timestamp: (T) -> Long
): List<T> {
    val visible = readings.filter { timestamp(it) in windowStart..windowEnd }
    if (visible.isEmpty()) return emptyList()
    val predecessor = readings.lastOrNull { timestamp(it) < windowStart }
    return if (predecessor == null) visible else listOf(predecessor) + visible
}
