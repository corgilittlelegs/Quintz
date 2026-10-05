package com.quintz.wifi

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quintz.wifi.core.WifiController
import com.quintz.wifi.core.profile.*
import com.quintz.wifi.data.Preferences
import com.quintz.wifi.data.WifiTargetMode
import com.quintz.wifi.model.BandType
import com.quintz.wifi.shizuku.ShizukuManager
import com.quintz.wifi.shizuku.WifiProfileFingerprint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ProfileRecoveryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun requireAccess() {
        ShizukuManager.updateState(context)
        assumeTrue("Requires authorized Shizuku", withTimeoutOrNull(10_000L) {
            ShizukuManager.state.first { it.isRunning && it.isPermissionGranted }
        } != null)
    }
    @Test fun a_legacyBackupMigrationPreservesPayloadSettingsAndSelection() = runBlocking {
        requireAccess()
        val actual = ProfileBackupStore(context)
        assumeTrue("Requires the existing legacy pending record", actual.exists())
        val legacy = actual.read()!!
        assumeTrue("Requires legacy fingerprint format", legacy.getBundle("expected")!!.getInt("fingerprintVersion", 1) == 1)
        val folder = File(context.noBackupFilesDir, "recovery-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder }
        try {
            val backup = ProfileBackupStore(isolated); backup.write(legacy)
            val migrated = ProfileBackupMigrator(context).upgrade(backup)
            for (key in listOf("original", "expected")) {
                val before = legacy.getBundle(key)!!; val after = migrated.getBundle(key)!!
                assertArrayEquals("Original parcel changed", before.getByteArray("payload"), after.getByteArray("payload"))
                assertEquals(2, after.getInt("fingerprintVersion"))
                assertEquals(before.getInt("id"), after.getInt("id"))
                assertNotNull(ProfileAccess.call(context, "validate", after))
            }
            assertEquals(legacy.getBoolean("selectionStarted"), migrated.getBoolean("selectionStarted"))
            val beforeSettings = legacy.getBundle("expected")!!.getBundle("appSettings")!!
            val afterSettings = migrated.getBundle("expected")!!.getBundle("appSettings")!!
            assertEquals(beforeSettings.keySet(), afterSettings.keySet())
            for (key in beforeSettings.keySet()) assertEquals(beforeSettings.getString(key), afterSettings.getString(key))
            val expected = migrated.getBundle("expected")!!
            val current = ProfileAccess.callChecked(context, "lookup", Bundle().apply {
                putString("ssid", expected.getString("ssid")); putString("security", expected.getString("security"))
            })
            assertArrayEquals("Protected configuration differs after migration", expected.getByteArray("fingerprint"), current.getByteArray("fingerprint"))
            assertTrue("Real pending record was touched", actual.read()!!.getBundle("expected")!!.getInt("fingerprintVersion", 1) == 1)
            // Invalid legacy fingerprints never replace the durable record.
            val invalid = Bundle(legacy).apply { putBundle("expected", Bundle(legacy.getBundle("expected")!!).apply { putByteArray("fingerprint", ByteArray(32)) }) }
            backup.write(invalid)
            val failure = runCatching { ProfileBackupMigrator(context).upgrade(backup) }.exceptionOrNull()
            assertEquals(RecoveryFailure.BACKUP_INVALID, (failure as? ProfileRecoveryException)?.reason)
            assertTrue(backup.exists()); assertArrayEquals(ByteArray(32), backup.read()!!.getBundle("expected")!!.getByteArray("fingerprint"))
        } finally { folder.deleteRecursively() }
        Unit
    }
    /** Explicit instrumentation opt-in is required; ordinary CI never steers a real network. */
    @Test fun b_recoverThenPinBindAndUnlockBothBands() = runBlocking {
        assumeTrue("Requires explicit real-profile mutation opt-in", InstrumentationRegistry.getArguments().getString("profileMutation") == "true")
        requireAccess()
        val controller = WifiController(context); val prefs = Preferences.get(context)
        assertTrue(controller.actionFailureMessage, controller.recoverPendingProfile())
        assertFalse("Recovery record remains", ProfileBackupStore(context).exists())
        val initial = controller.refreshStatus(forceFresh = true)
        assertTrue(initial.isConnected); assertTrue(initial.profileInspectionKnown)
        val ssid = initial.ssid
        val original = ProfileAccess.callChecked(context, "lookup", ProfileAccess.identity(ssid, initial.securityType!!, initial.networkId))
        // Reuse Android's existing credential entirely inside the app; never print/export it.
        @Suppress("DEPRECATION") val password = WifiProfileFingerprint.decode(original.getByteArray("payload")!!).preSharedKey?.removeSurrounding("\"")
        assumeTrue("Requires a complete credential for this secured network", !password.isNullOrEmpty() && password != "*")
        val policy = prefs.getMacPolicy(ssid) ?: prefs.defaultMacPolicy
        val radios = controller.scanRadios().filter { it.ssid == ssid && it.ageSeconds <= 8L }
        val targets = listOf(BandType.BAND_2_4_GHZ, BandType.BAND_5_GHZ).map { band ->
            radios.filter { it.band == band }.maxByOrNull { it.rssi } ?: error("Required test band unavailable: $band")
        }
        for (radio in targets) {
            assertTrue(controller.actionFailureMessage, controller.lockToBssid(ssid, radio.bssid, password!!,
                macAddressPolicy = policy, requestSource = "device_test_pin_bind"))
            val pinned = controller.refreshStatus(forceFresh = true)
            assertEquals(radio.bssid.lowercase(), pinned.bssid.lowercase())
            assertEquals(WifiTargetMode.PIN_BSSID, prefs.getWifiTargetMode(ssid))
            val captured = ProfileAccess.callChecked(context, "lookup", ProfileAccess.identity(ssid, pinned.securityType!!, pinned.networkId))
            assertTrue("Saved pin was not applied", captured.getString("pin").equals(radio.bssid, true))
            assertFalse(ProfileBackupStore(context).exists())
            Log.i("QuintzRecoveryTest", "PIN_BIND verified band=${radio.band.name} pending=false")
            assertTrue(controller.actionFailureMessage, controller.unlockToAuto(ssid, requestSource = "device_test_unlock"))
            val automatic = controller.refreshStatus(forceFresh = true)
            assertTrue(automatic.isConnected); assertEquals(ssid, automatic.ssid)
            val unpinned = ProfileAccess.callChecked(context, "lookup", ProfileAccess.identity(ssid, automatic.securityType!!, automatic.networkId))
            assertTrue("Saved pin remains", unpinned.getString("pin").isNullOrEmpty())
            assertEquals(WifiTargetMode.AUTO, prefs.getWifiTargetMode(ssid))
            assertFalse(ProfileBackupStore(context).exists())
            Log.i("QuintzRecoveryTest", "UNLOCK verified band=${radio.band.name} pending=false")
        }
        Unit
    }
}
