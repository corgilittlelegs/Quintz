package com.quintz.wifi.core

import com.quintz.wifi.model.AccessPointRadio
import com.quintz.wifi.model.BandType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiScanPolicyTest {
    private fun radio(observedAt: Long) = AccessPointRadio(
        bssid = "30:DE:4B:31:55:2E", ssid = "Archer", frequency = 2442,
        band = BandType.BAND_2_4_GHZ, channel = 7, rssi = -51, flags = "[ESS]",
        observedAtMillis = observedAt
    )

    @Test
    fun rereadingCacheDoesNotProveScanCompletionEvenWithAgeRounding() {
        val baseline = mapOf("30:de:4b:31:55:2e" to 90_000L)
        assertFalse(hasNewScanObservation(listOf(radio(90_000L)), baseline, 100_000L))
        assertFalse(hasNewScanObservation(listOf(radio(90_999L)), baseline, 100_000L))
        assertTrue(hasNewScanObservation(listOf(radio(100_000L)), baseline, 100_000L))
    }

    @Test
    fun previouslyUnseenButOldRadioDoesNotProveRefresh() {
        assertFalse(hasNewScanObservation(listOf(radio(93_000L)), emptyMap(), 100_000L))
        assertFalse(hasNewScanObservation(listOf(radio(0L)), emptyMap(), 100_000L))
        assertTrue(hasNewScanObservation(listOf(radio(100_000L)), emptyMap(), 100_000L))
        assertFalse(hasNewScanObservation(emptyList(), emptyMap(), 100_000L))
    }

    @Test
    fun unrefreshedScansBackOffAndSuccessfulScansResumeFastPolling() {
        assertEquals(1_500L, foregroundScanDelayMillis(0))
        assertEquals(8_500L, foregroundScanDelayMillis(1))
        assertEquals(17_000L, foregroundScanDelayMillis(2))
        assertEquals(30_000L, foregroundScanDelayMillis(3))
        assertEquals(30_000L, foregroundScanDelayMillis(100))
    }
}
