package com.quintz.wifi.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchdogTargetPolicyTest {
    @Test
    fun autoOnlyNetworkDoesNotKeepWatchdogRunning() {
        assertFalse(hasWatchdogTargets(mapOf("wifi_target_mode_Archer" to "AUTO")))
    }

    @Test
    fun anotherSavedSteeringTargetKeepsWatchdogRunning() {
        val saved = mapOf(
            "wifi_target_mode_Archer" to "AUTO",
            "wifi_target_mode_Office" to "PREFER_5_GHZ"
        )
        assertTrue(hasWatchdogTargets(saved))
        assertTrue(hasWatchdogTargets(saved + ("wifi_target_mode_Office" to "PIN_BSSID")))
    }

    @Test
    fun unrelatedSettingsAndMalformedModesDoNotCountAsTargets() {
        assertFalse(hasWatchdogTargets(mapOf(
            "watchdog_enabled" to true,
            "wifi_target_mode_Archer" to "UNKNOWN",
            "wifi_target_mode_" to "PREFER_5_GHZ"
        )))
    }
}
