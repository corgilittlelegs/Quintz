package com.quintz.wifi.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLoggerTest {
    @Test fun quotedPasswordWithSpacesIsRedacted() {
        val sanitized = DiagnosticLogger.sanitize(
            "cmd wifi connect-network 'My Home' 'wpa2' 'long secret phrase' -b aa:bb:cc:dd:ee:ff"
        )
        assertTrue(sanitized.contains("wifi.connect-network [arguments redacted]"))
        assertFalse(sanitized.contains("long secret phrase"))
        assertFalse(sanitized.contains("My Home"))
    }

    @Test fun explicitPasswordFieldWithSpacesIsRedacted() {
        val sanitized = DiagnosticLogger.sanitize("retry password='long secret phrase' now")
        assertFalse(sanitized.contains("long secret phrase"))
    }
}
