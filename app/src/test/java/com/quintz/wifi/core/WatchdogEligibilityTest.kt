package com.quintz.wifi.core
import com.quintz.wifi.data.WifiTargetMode
import com.quintz.wifi.model.*
import org.junit.Assert.*
import org.junit.Test
class WatchdogEligibilityTest {
    private val radio = AccessPointRadio("aa:bb:cc:dd:ee:ff", "Cafe", 5180, BandType.BAND_5_GHZ, 36, -65, "[ESS]", observedAtElapsedMillis = 10_000)
    @Test fun explicitTrustedOpenPinWorksButBandPreferenceDoesNot() {
        val current = WifiStatus(ssid = "Cafe", securityType = "0")
        assertNull(WatchdogEligibility.rejection(current, radio, WifiTargetMode.PIN_BSSID, radio.bssid, "0", -72, 11_000))
        assertNotNull(WatchdogEligibility.rejection(current, radio, WifiTargetMode.PREFER_5_GHZ, null, "0", -72, 11_000))
    }
    @Test fun elapsedFreshnessRejectsFutureAndExpiredObservations() {
        val current = WifiStatus(ssid = "Cafe", securityType = "0")
        for (now in listOf(9_999L, 18_001L)) assertNotNull(WatchdogEligibility.rejection(current, radio, WifiTargetMode.PIN_BSSID, radio.bssid, "0", -72, now))
    }
    @Test fun owePinRequiresExactBssidTrustAndMatchingSecurity() {
        val owe = radio.copy(flags = "[OWE][ESS]"); val current = WifiStatus(ssid = "Cafe", securityType = "6")
        assertNull(WatchdogEligibility.rejection(current, owe, WifiTargetMode.PIN_BSSID, owe.bssid, "6", -72, 11_000))
        assertNotNull(WatchdogEligibility.rejection(current, owe, WifiTargetMode.PIN_BSSID, "other", "6", -72, 11_000))
        assertNotNull(WatchdogEligibility.rejection(current, owe, WifiTargetMode.PIN_BSSID, owe.bssid, null, -72, 11_000))
    }
}
