package com.quintz.wifi.core

import com.quintz.wifi.core.profile.TransitionResult
import com.quintz.wifi.model.WifiStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CredentialEditorTest {
    private val original = WifiStatus(isConnected = true, ssid = "synthetic-validation",
        securityType = "2", networkId = 1, profileInspectionKnown = true)
    private class Store : CredentialStore {
        override var available = true
        var password: String? = "OriginalSynthetic42"
        var writes = 0
        var security: String? = null
        override fun save(ssid: String, password: String, securityType: String): Boolean {
            writes++; this.password = password; security = securityType; return true
        }
        override fun remove(ssid: String): Boolean { writes++; password = null; return true }
    }

    @Test fun invalidWpa2InputNeverOverwritesAnExistingCredential() = runBlocking {
        val store = Store()
        val editor = CredentialEditor({ original }, { false }, store)
        for (password in listOf("", "x", "z".repeat(64), "a".repeat(65))) {
            val result = editor.edit(WifiActionContext.capture(original), password)
            assertEquals(TransitionResult.Failed, result.transition)
            assertNotNull(result.detail)
            assertEquals("OriginalSynthetic42", store.password)
        }
        assertEquals(0, store.writes)
    }

    @Test fun validInputSavesWithTheVerifiedSecurityType() = runBlocking {
        val store = Store()
        val editor = CredentialEditor({ original }, { false }, store)
        assertTrue(editor.edit(WifiActionContext.capture(original), "Replacement42").verified)
        assertEquals("Replacement42", store.password)
        assertEquals("2", store.security)
        assertEquals(1, store.writes)
    }

    @Test fun shortSaeInputIsAllowedAndForgettingStillWorks() = runBlocking {
        val status = original.copy(securityType = "4")
        val store = Store()
        val editor = CredentialEditor({ status }, { false }, store)
        assertTrue(editor.edit(WifiActionContext.capture(status), "x").verified)
        assertEquals("4", store.security)
        assertTrue(editor.edit(WifiActionContext.capture(status), null).verified)
        assertNull(store.password)
    }

    @Test fun disconnectedUnknownOrChangedIdentityNeverWrites() = runBlocking {
        val store = Store()
        for (status in listOf(WifiStatus(), original.copy(ssid = ""),
            original.copy(ssid = "<unknown ssid>"), original.copy(networkId = 2), original.copy(securityType = "4"))) {
            val editor = CredentialEditor({ status }, { false }, store)
            assertFalse(editor.edit(WifiActionContext.capture(original), "Replacement42").verified)
        }
        assertEquals(0, store.writes)
        assertEquals("OriginalSynthetic42", store.password)
    }

    @Test fun recoveryAndActiveSteeringBlockCredentialWrites() = runBlocking {
        val store = Store()
        assertEquals(TransitionResult.RecoveryPending,
            CredentialEditor({ original }, { true }, store).edit(WifiActionContext.capture(original), "Replacement42").transition)
        val locked = original.copy(isLockedToBssid = true)
        assertFalse(CredentialEditor({ locked }, { false }, store)
            .edit(WifiActionContext.capture(locked), "Replacement42").verified)
        assertEquals(0, store.writes)
    }

    @Test fun unavailableStorageDoesNotChangeTheCredentialAndReleasesTheLock() = runBlocking {
        val store = Store().apply { available = false }
        val editor = CredentialEditor({ original }, { false }, store)
        assertEquals(TransitionResult.StorageFailed, editor.edit(WifiActionContext.capture(original), "Replacement42").transition)
        assertEquals(0, store.writes)
        assertFalse(WifiOperationCoordinator.isOperating.value)
    }
}
