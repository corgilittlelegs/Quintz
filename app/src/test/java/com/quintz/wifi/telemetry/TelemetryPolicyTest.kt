package com.quintz.wifi.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryPolicyTest {
    @Test
    fun candidateFreshnessHasOneBoundaryForComparisonAndDisplay() {
        val now = 100_000L
        assertTrue(isFreshCandidate(now - CANDIDATE_FRESHNESS_MS, now))
        assertFalse(isFreshCandidate(now - CANDIDATE_FRESHNESS_MS - 1L, now))
        assertFalse(isFreshCandidate(now + 1L, now))
        assertFalse(isFreshCandidate(0L, now))
        assertTrue(isVisibleCandidate(now - CANDIDATE_VISIBLE_MS, now))
        assertFalse(isVisibleCandidate(now - CANDIDATE_VISIBLE_MS - 1L, now))
        assertTrue(isVisibleCandidate(now - CANDIDATE_FRESHNESS_MS - 1L, now))
    }

    @Test
    fun candidateColorDoesNotDependOnScanOrderOrCase() {
        val bssid = "30:DE:4B:31:55:2E"
        val before = candidateColorIndex(bssid)
        candidateColorIndex("54:46:17:11:22:33")
        assertEquals(before, candidateColorIndex(bssid.lowercase()))
        assertTrue(before in 0..4)
    }
}
