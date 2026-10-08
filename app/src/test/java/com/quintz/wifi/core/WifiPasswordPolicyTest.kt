package com.quintz.wifi.core

import org.junit.Assert.*
import org.junit.Test

class WifiPasswordPolicyTest {
    @Test fun wpa2RejectsTheInvalidInputsFoundOnTheTablet() {
        for (password in listOf("", "x", "a".repeat(7), "z".repeat(64), "a".repeat(65))) {
            assertNotNull(WifiPasswordPolicy.validationError(password, "2"))
            assertNull(WifiPasswordPolicy.preSharedKey(password, "2"))
        }
    }

    @Test fun wpa2PassphraseBoundariesAreQuotedWithoutChangingUserInput() {
        for (password in listOf("a".repeat(8), "a".repeat(63), "  secret  ", "quote\"and\\slash")) {
            assertNull(WifiPasswordPolicy.validationError(password, "2"))
            assertEquals("\"$password\"", WifiPasswordPolicy.preSharedKey(password, "2"))
        }
    }

    @Test fun aRawHexKeyIsNotMistakenForAQuotedPassphrase() {
        val key = "0123456789abcdefABCDEF".repeat(3).take(64)
        for (security in listOf("2", "4")) {
            assertNull(WifiPasswordPolicy.validationError(key, security))
            assertEquals(key, WifiPasswordPolicy.preSharedKey(key, security))
        }
        assertEquals("\"${key.take(63)}\"", WifiPasswordPolicy.preSharedKey(key.take(63), "2"))
    }

    @Test fun saeUsesItsOwnMinimumRatherThanTheWpa2Minimum() {
        assertNull(WifiPasswordPolicy.validationError("x", "4"))
        assertNotNull(WifiPasswordPolicy.validationError("x", "2"))
        assertNotNull(WifiPasswordPolicy.validationError("", "4"))
        assertNull(WifiPasswordPolicy.validationError("a".repeat(63), "4"))
        assertNotNull(WifiPasswordPolicy.validationError("z".repeat(64), "4"))
    }

    @Test fun malformedTextAndEncodedOverflowAreRejected() {
        assertNotNull(WifiPasswordPolicy.validationError("abcdefgh\uD800", "2"))
        assertNotNull(WifiPasswordPolicy.validationError("é".repeat(32), "4"))
        assertNull(WifiPasswordPolicy.validationError("é".repeat(31), "4"))
        assertNull(WifiPasswordPolicy.validationError("pässword", "2"))
    }

    @Test fun unknownAndUnsupportedSecurityCannotSavePersonalCredentials() {
        for (security in listOf("", "0", "6", "3"))
            assertNotNull(WifiPasswordPolicy.validationError("SyntheticSecret42", security))
    }
}
