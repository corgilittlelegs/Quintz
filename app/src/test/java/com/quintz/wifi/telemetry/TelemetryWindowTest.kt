package com.quintz.wifi.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryWindowTest {
    @Test
    fun crossingSegmentSurvivesAsItsFirstPointLeavesTheWindow() {
        val readings = listOf(39_000L, 40_000L, 41_000L, 42_000L)
        val before = readingsWithWindowPredecessor(readings, 39_999L, 42_000L) { it }
        val after = readingsWithWindowPredecessor(readings, 40_001L, 42_000L) { it }
        assertTrue(before.containsAll(listOf(40_000L, 41_000L)))
        assertEquals(listOf(40_000L, 41_000L, 42_000L), after)
        // The same measured segment now crosses x=0; it must be clipped, not removed.
        assertTrue(after.first() < 40_001L && after[1] > 40_001L)
    }

    @Test
    fun sparseCandidateHistoryKeepsItsNearestPredecessor() {
        val observations = listOf(21_000L, 30_000L, 39_000L, 48_000L, 57_000L)
        assertEquals(
            listOf(39_000L, 48_000L, 57_000L),
            readingsWithWindowPredecessor(observations, 40_000L, 60_000L) { it }
        )
    }

    @Test
    fun windowDoesNotInventStartOrEndReadings() {
        assertEquals(listOf(48_000L, 57_000L), readingsWithWindowPredecessor(
            listOf(48_000L, 57_000L, 61_000L), 40_000L, 60_000L
        ) { it })
        assertTrue(readingsWithWindowPredecessor(listOf(30_000L), 40_000L, 60_000L) { it }.isEmpty())
    }
}
