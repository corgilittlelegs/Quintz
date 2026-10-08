package com.quintz.wifi.core

import com.quintz.wifi.model.WifiStatus
import org.junit.Assert.*
import org.junit.Test

class WifiActionContextTest {
    private val original = WifiStatus(isConnected = true, ssid = "Archer", securityType = "4", networkId = 17)
    private val action = WifiActionContext.capture(original)

    @Test fun preMutationChecksRemainStrictDuringAnUnknownIdentity() {
        for (ssid in listOf("", "<unknown ssid>", "<NONE>")) {
            val unknown = original.copy(ssid = ssid)
            assertFalse(action.matches(unknown))
            assertFalse(WifiActionContext.capture(unknown).matches(unknown))
        }
        assertFalse(action.matches(original.copy(securityType = "2")))
        assertFalse(action.matches(original.copy(networkId = 18)))
    }

    @Test fun ownReconnectCanPassThroughUnknownIdentityAndFinishOnTheTarget() {
        val observations = listOf(WifiStatus(), original.copy(ssid = "<unknown ssid>"),
            original.copy(ssid = ""), original)
        assertTrue(observations.none { action.conflictsAfterApply(it, "Archer") })
        // Unknown is tolerated for waiting, but cannot meet final identity verification.
        assertTrue(observations.dropLast(1).none { action.matches(it) })
        assertTrue(action.matches(observations.last()))
    }

    @Test fun aPositivelyIdentifiedDifferentNetworkStillAbortsAfterApply() {
        assertTrue(action.conflictsAfterApply(original.copy(ssid = "Other"), "Archer"))
        assertTrue(action.conflictsAfterApply(original.copy(ssid = "archer"), "Archer"))
        assertFalse(action.conflictsAfterApply(original.copy(ssid = "Approved target"), "Approved target"))
    }
}
