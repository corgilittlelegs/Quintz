package com.quintz.wifi

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quintz.wifi.core.profile.ProfileBackupStore
import com.quintz.wifi.data.Preferences
import com.quintz.wifi.model.MacAddressPolicy
import com.quintz.wifi.model.SavedCredentialState
import com.quintz.wifi.data.WifiTargetMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProfileStorageTest {
    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        val prefix = "test-${UUID.randomUUID()}-"
        var failSecure = false
        val names = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
            if (name == "secure_prefs" && failSecure) error("Injected secure storage failure")
            names += prefix + name
            return super.getSharedPreferences(prefix + name, mode)
        }
        override fun deleteSharedPreferences(name: String) = super.deleteSharedPreferences(prefix + name)
        override fun getNoBackupFilesDir(): File = File(super.getNoBackupFilesDir(), prefix).apply { mkdirs() }
        fun cleanup() { names.forEach { super.deleteSharedPreferences(it) }; noBackupFilesDir.deleteRecursively() }
    }
    private fun preferences(context: Context): Preferences = Preferences::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
    @Test fun credentialStatusDistinguishesMissingSavedAndUnavailable() {
        val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val prefs = preferences(context)
            assertEquals(SavedCredentialState.NOT_SAVED, prefs.savedCredentialState("test"))
            assertTrue(prefs.savePassword("test", "synthetic-secret"))
            assertEquals(SavedCredentialState.SAVED, prefs.savedCredentialState("test"))
            assertTrue(prefs.removePassword("test"))
            assertEquals(SavedCredentialState.NOT_SAVED, prefs.savedCredentialState("test"))
            context.failSecure = true
            assertEquals(SavedCredentialState.UNAVAILABLE, preferences(context).savedCredentialState("test"))
        } finally { context.cleanup() }
    }
    @Test fun macOnlyCommitAndRollbackPreservePasswordPinModeAndWatchdog() {
        val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val prefs = preferences(context)
            assertTrue(prefs.commitTransition("test", "02:11:22:33:44:55", "2", "synthetic-secret", MacAddressPolicy.DEVICE, WifiTargetMode.PIN_BSSID, true))
            prefs.isWatchdogEnabled = true
            val before = prefs.macTransitionSettings("test")
            assertFalse(before.keySet().any { it.startsWith("pwd_") })
            assertTrue(prefs.commitMacPolicy("test", MacAddressPolicy.RANDOMIZED, "02:aa:bb:cc:dd:ee"))
            assertTrue(prefs.isMacPolicyPending("test", MacAddressPolicy.RANDOMIZED, "02:11:22:33:44:66"))
            assertFalse(prefs.isMacPolicyPending("test", MacAddressPolicy.RANDOMIZED, "02:AA:BB:CC:DD:EE"))
            assertEquals("synthetic-secret", prefs.getPassword("test"))
            assertEquals(WifiTargetMode.PIN_BSSID, prefs.getWifiTargetMode("test"))
            assertEquals("02:11:22:33:44:55", prefs.getPinnedBssid("test"))
            assertTrue(prefs.isWatchdogEnabled)
            assertTrue(prefs.restoreTransitionSettings(before))
            assertEquals(MacAddressPolicy.DEVICE, prefs.getMacPolicy("test"))
            assertFalse(prefs.isMacPolicyPending("test", MacAddressPolicy.RANDOMIZED, null))
            assertEquals("synthetic-secret", prefs.getPassword("test"))
            assertEquals(WifiTargetMode.PIN_BSSID, prefs.getWifiTargetMode("test"))
            assertTrue(prefs.isWatchdogEnabled)
        } finally { context.cleanup() }
    }
    @Test fun macOnlySettingsDoNotRequireCredentialStorage() {
        val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            context.failSecure = true
            val prefs = preferences(context)
            assertTrue(prefs.commitMacPolicy("test", MacAddressPolicy.DEVICE, null))
            assertTrue(prefs.restoreTransitionSettings(prefs.macTransitionSettings("test")))
            assertEquals(SavedCredentialState.UNAVAILABLE, prefs.savedCredentialState("test"))
        } finally { context.cleanup() }
    }
    @Test fun authenticatedBackupRoundTripsAndCorruptionRetainsPendingFile() {
        val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val snapshot = Bundle().apply { putByteArray("payload", byteArrayOf(1, 2, 3)); putByteArray("fingerprint", ByteArray(32)); putString("platform", "test") }
            val record = Bundle().apply { putBundle("original", snapshot); putBundle("expected", Bundle(snapshot)) }
            val store = ProfileBackupStore(context); store.write(record)
            assertArrayEquals(snapshot.getByteArray("payload"), ProfileBackupStore(context).read()!!.getBundle("original")!!.getByteArray("payload"))
            val file = File(context.noBackupFilesDir, "pending-profile-v1"); val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
            assertTrue(runCatching { store.read() }.isFailure); assertTrue(store.exists())
        } finally { context.cleanup() }
    }
    @Test fun settingsWrittenDuringSecureStorageFailureSurviveRecovery() {
        val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val first = preferences(context); first.isWatchdogEnabled = true
            context.failSecure = true
            val fallback = preferences(context); fallback.isWatchdogEnabled = false
            fallback.setMacPolicy("  special \" network  ", MacAddressPolicy.RANDOMIZED)
            assertFalse(fallback.savePassword("test", "synthetic-secret"))
            context.failSecure = false
            val recovered = preferences(context)
            assertFalse(recovered.isWatchdogEnabled)
            assertEquals(MacAddressPolicy.RANDOMIZED, recovered.getMacPolicy("  special \" network  "))
            assertFalse(context.getSharedPreferences("prefs", 0).all.keys.any { it.startsWith("pwd_") })
        } finally { context.cleanup() }
    }
}
