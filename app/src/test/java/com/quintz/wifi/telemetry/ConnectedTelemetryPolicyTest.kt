package com.quintz.wifi.telemetry

import com.quintz.wifi.model.BandType
import com.quintz.wifi.model.WifiStatus
import org.junit.Assert.*
import org.junit.Test

class ConnectedTelemetryPolicyTest {
    private val verified = WifiStatus(isConnected = true, ssid = "test", bssid = "aa:bb:cc:dd:ee:01",
        frequency = 5180, band = BandType.BAND_5_GHZ, rssi = -60, networkId = 3, observedAtMillis = 10_000)
    private val redacted = verified.copy(ssid = "", bssid = "", rssi = -65, observedAtMillis = 0)

    @Test fun redactedReadingsRequireRecentMatchingConnection() {
        val reading = connectedTelemetryObservation(redacted, verified, 11_000)!!
        assertEquals(verified.bssid, reading.bssid)
        assertEquals(-65, reading.rssi)
        assertEquals(11_000L, reading.observedAtMillis)
        assertNull(connectedTelemetryObservation(redacted.copy(networkId = null), verified, 11_000))
        assertNull(connectedTelemetryObservation(redacted.copy(networkId = 4), verified, 11_000))
        assertNull(connectedTelemetryObservation(redacted.copy(frequency = 2412), verified, 11_000))
        assertNull(connectedTelemetryObservation(redacted, verified, 15_001))
        assertNull(connectedTelemetryObservation(redacted, verified, 9_999))
        assertNull(connectedTelemetryObservation(redacted.copy(isConnected = false), verified, 11_000))
        assertNull(connectedTelemetryObservation(redacted.copy(rssi = -127), verified, 11_000))
    }

    @Test fun completeNativeIdentityDoesNotReuseOldRadio() {
        val newRadio = verified.copy(bssid = "aa:bb:cc:dd:ee:02", frequency = 2412, band = BandType.BAND_2_4_GHZ)
        assertEquals(newRadio.bssid, connectedTelemetryObservation(newRadio, verified, 11_000)!!.bssid)
    }

    @Test fun graphBreaksAtSameBandRoamsAndMissingIntervals() {
        val previous = TelemetrySample(10_000, verified.bssid, -60, 300, BandType.BAND_5_GHZ)
        val next = previous.copy(timestamp = 11_000)
        assertFalse(startsNewTelemetrySegment(previous, next))
        assertFalse(startsNewTelemetrySegment(previous, next.copy(activeBssid = verified.bssid.uppercase())))
        assertTrue(startsNewTelemetrySegment(previous, next.copy(activeBssid = "aa:bb:cc:dd:ee:02")))
        assertTrue(startsNewTelemetrySegment(previous, next.copy(activeBand = BandType.BAND_2_4_GHZ)))
        assertTrue(startsNewTelemetrySegment(previous, next.copy(timestamp = 17_501)))
        assertTrue(startsNewTelemetrySegment(previous, previous))
    }
}
