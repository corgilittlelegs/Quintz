package com.quintz.wifi.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiSecurityPolicyTest {
    @Test fun wpa2AcceptsPskButRejectsOpenAndUnknown() {
        assertTrue(WifiSecurityPolicy.allowsAutomaticSwitch("2", "", "[WPA2-PSK-CCMP][ESS]"))
        assertFalse(WifiSecurityPolicy.allowsAutomaticSwitch("2", "", "[ESS]"))
        assertFalse(WifiSecurityPolicy.allowsAutomaticSwitch("2", "", ""))
    }

    @Test fun wpa3RequiresSae() {
        assertTrue(WifiSecurityPolicy.allowsAutomaticSwitch("4", "", "[RSN-SAE-CCMP][ESS]"))
        assertFalse(WifiSecurityPolicy.allowsAutomaticSwitch("4", "", "[WPA2-PSK-CCMP][ESS]"))
    }

    @Test fun unknownCurrentSecurityUsesCurrentRadioEvidence() {
        assertTrue(WifiSecurityPolicy.allowsAutomaticSwitch("", "[WPA2-PSK-CCMP][ESS]", "[WPA2-PSK-CCMP][ESS]"))
        assertFalse(WifiSecurityPolicy.allowsAutomaticSwitch("", "", "[WPA2-PSK-CCMP][ESS]"))
        assertFalse(WifiSecurityPolicy.allowsAutomaticSwitch("0", "[ESS]", "[ESS]"))
    }

    @Test fun pinnedRecoveryRequiresPreviouslySelectedSecurity() {
        val open = "[ESS]"
        val psk = "[WPA2-PSK-CCMP][ESS]"
        val sae = "[RSN-SAE-CCMP][ESS]"
        val transition = "[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]"
        assertFalse(WifiSecurityPolicy.matchesSecurityType(null, open))
        assertFalse(WifiSecurityPolicy.matchesSecurityType("2", open))
        assertFalse(WifiSecurityPolicy.matchesSecurityType("4", psk))
        assertTrue(WifiSecurityPolicy.matchesSecurityType("0", open))
        assertTrue(WifiSecurityPolicy.matchesSecurityType("2", psk))
        assertTrue(WifiSecurityPolicy.matchesSecurityType("4", sae))
        assertTrue(WifiSecurityPolicy.matchesSecurityType("2", transition))
        assertTrue(WifiSecurityPolicy.matchesSecurityType("4", transition))
    }

    @Test fun automaticSwitchRequiresTrustedRadioAndMatchingSecurity() {
        val psk = "[WPA2-PSK-CCMP][ESS]"
        val sae = "[RSN-SAE-CCMP][ESS]"
        assertFalse(WifiSecurityPolicy.allowsTrustedAutomaticSwitch("2", psk, psk, null))
        assertFalse(WifiSecurityPolicy.allowsTrustedAutomaticSwitch("2", psk, psk, "4"))
        assertFalse(WifiSecurityPolicy.allowsTrustedAutomaticSwitch("4", sae, psk, "4"))
        assertFalse(WifiSecurityPolicy.allowsTrustedAutomaticSwitch("0", "[ESS]", "[ESS]", "0"))
        assertTrue(WifiSecurityPolicy.allowsTrustedAutomaticSwitch("2", psk, psk, "2"))
        assertTrue(WifiSecurityPolicy.allowsTrustedAutomaticSwitch("4", sae, sae, "4"))
    }

    @Test fun manualSelectionCannotDowngradeAnActiveSameNameNetwork() {
        assertFalse(WifiSecurityPolicy.allowsSameSsidSelection("2", "0"))
        assertFalse(WifiSecurityPolicy.allowsSameSsidSelection("4", "2"))
        assertFalse(WifiSecurityPolicy.allowsSameSsidSelection("", "0"))
        assertTrue(WifiSecurityPolicy.allowsSameSsidSelection("2", "2"))
        assertTrue(WifiSecurityPolicy.allowsSameSsidSelection("0", "2"))
    }
}
