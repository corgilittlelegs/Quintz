package com.quintz.wifi.core

import com.quintz.wifi.data.WifiTargetMode
import com.quintz.wifi.model.MacAddressPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class MacPolicyChangeTest {
    @Test fun choosingConfiguredPolicyNeverReconnectsEvenWithAnActivePin() {
        for (policy in MacAddressPolicy.entries) for (mode in WifiTargetMode.entries) {
            assertEquals(MacPolicyChangePlan.UNCHANGED, macPolicyChangePlan(policy, policy, mode))
        }
    }
    @Test fun autoChangesOnlyTheSavedProfileInEitherDirection() {
        assertEquals(MacPolicyChangePlan.SAVE_ONLY, macPolicyChangePlan(MacAddressPolicy.DEVICE, MacAddressPolicy.RANDOMIZED, WifiTargetMode.AUTO))
        assertEquals(MacPolicyChangePlan.SAVE_ONLY, macPolicyChangePlan(MacAddressPolicy.RANDOMIZED, MacAddressPolicy.DEVICE, WifiTargetMode.AUTO))
    }
    @Test fun activeSteeringReconnectsInEitherDirection() {
        for (mode in listOf(WifiTargetMode.PIN_BSSID, WifiTargetMode.PREFER_5_GHZ)) {
            assertEquals(MacPolicyChangePlan.RECONNECT, macPolicyChangePlan(MacAddressPolicy.DEVICE, MacAddressPolicy.RANDOMIZED, mode))
            assertEquals(MacPolicyChangePlan.RECONNECT, macPolicyChangePlan(MacAddressPolicy.RANDOMIZED, MacAddressPolicy.DEVICE, mode))
        }
    }
}
