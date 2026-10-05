package com.quintz.wifi

import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quintz.wifi.core.profile.ProfileAccess
import com.quintz.wifi.core.profile.ProfileBackupStore
import com.quintz.wifi.core.WifiController
import com.quintz.wifi.core.MacPolicyChangeResult
import com.quintz.wifi.data.Preferences
import com.quintz.wifi.data.WifiTargetMode
import com.quintz.wifi.model.MacAddressPolicy
import com.quintz.wifi.service.WatchdogControl
import com.quintz.wifi.shizuku.ShizukuManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Private-device probes never print credentials, parcels or protected fingerprints. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MacRecoveryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun requireDeviceOptIn() {
        assumeTrue("Requires explicit real-profile mutation opt-in", InstrumentationRegistry.getArguments().getString("profileMutation") == "true")
        ShizukuManager.updateState(context)
        withTimeout(10_000L) { ShizukuManager.state.first { it.isRunning && it.isPermissionGranted } }
        val status = WifiController(context).refreshStatus(forceFresh = true)
        assertTrue(status.isConnected)
        assertEquals(InstrumentationRegistry.getArguments().getString("ssid"), status.ssid)
    }
    @Test fun a_completeExistingMacTransactionWithoutDiscardingRecovery() = runBlocking {
        requireDeviceOptIn()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ProfileBackupStore(context)
        assumeTrue("Requires the existing pending MAC transaction", store.exists())
        val record = store.read()!!
        val expected = record.getBundle("expected")!!
        assertEquals("mac_policy", expected.getString("operation"))
        val prefs = Preferences.get(context)
        val ssid = expected.getString("ssid")!!.removeSurrounding("\"")
        val mode = prefs.getWifiTargetMode(ssid)
        val watchdog = prefs.isWatchdogEnabled
        val password = prefs.getPassword(ssid)
        val controller = WifiController(context)
        assertTrue(controller.actionFailureMessage, controller.recoverPendingProfile())
        assertFalse("Pending record remains", store.exists())
        assertEquals(mode, prefs.getWifiTargetMode(ssid))
        assertEquals(watchdog, prefs.isWatchdogEnabled)
        assertTrue("Separate saved credential changed", password == prefs.getPassword(ssid))
        val current = ProfileAccess.callChecked(context, "lookup", Bundle().apply {
            putString("ssid", expected.getString("ssid")); putString("security", expected.getString("security"))
        })
        assertEquals(expected.getInt("mac"), current.getInt("mac"))
        assertEquals(MacAddressPolicy.RANDOMIZED, prefs.getMacPolicy(ssid))
        Log.i("QuintzMacRecovery", "PENDING_MAC completed modePreserved=true watchdogPreserved=true credentialPreserved=true pending=false")
        Unit
    }
    @Test fun b_switchInPreferAndPinModesThenUnlock() = runBlocking {
        requireDeviceOptIn()
        val controller = WifiController(context); val prefs = Preferences.get(context)
        assertTrue(controller.recoverPendingProfile())
        var status = controller.refreshStatus(forceFresh = true)
        val ssid = status.ssid
        val password = prefs.getPassword(ssid)
        assertTrue("Requires the existing secure credential", !password.isNullOrEmpty())
        prefs.setWifiTargetMode(ssid, WifiTargetMode.PREFER_5_GHZ)
        for (mode in listOf(WifiTargetMode.PREFER_5_GHZ, WifiTargetMode.PIN_BSSID)) {
            if (mode == WifiTargetMode.PIN_BSSID) {
                status = controller.refreshStatus(forceFresh = true)
                assertTrue(controller.actionFailureMessage, controller.lockToBssid(ssid, status.bssid, password!!,
                    macAddressPolicy = MacAddressPolicy.RANDOMIZED, requestSource = "mac_recovery_test_pin"))
            }
            val pin = prefs.getPinnedBssid(ssid)
            val watchdog = prefs.isWatchdogEnabled
            for (policy in listOf(MacAddressPolicy.DEVICE, MacAddressPolicy.RANDOMIZED)) {
                val before = controller.refreshStatus(forceFresh = true)
                val original = ProfileAccess.callChecked(context, "lookup", ProfileAccess.identity(ssid, before.securityType!!, before.networkId))
                assertEquals(controller.macPolicyFailureMessage, MacPolicyChangeResult.RECONNECTED, controller.changeMacPolicy(ssid, policy))
                val after = controller.refreshStatus(forceFresh = true)
                assertTrue(after.isConnected); assertEquals(ssid, after.ssid)
                assertEquals(policy, after.configuredMacPolicy)
                assertEquals(policy, prefs.getMacPolicy(ssid)); assertEquals(mode, prefs.getWifiTargetMode(ssid))
                assertEquals(pin, prefs.getPinnedBssid(ssid)); assertEquals(watchdog, prefs.isWatchdogEnabled)
                assertTrue("Separate saved credential changed", password == prefs.getPassword(ssid))
                val expectedProfile = ProfileAccess.callChecked(context, "preview", Bundle(original).apply {
                    putInt("newMac", if (policy == MacAddressPolicy.DEVICE) 0 else 1)
                })
                val actual = ProfileAccess.callChecked(context, "selectedReadback", Bundle(expectedProfile).apply {
                    putBoolean("selectionRecorded", true); putBoolean("originalExists", true)
                })
                assertTrue("Observed MAC differs from saved profile", after.observedMacAddress.equals(actual.getString("expectedMac"), true))
                assertFalse("Pending record remains", ProfileBackupStore(context).exists())
                Log.i("QuintzMacRecovery", "MAC_SWITCH verified mode=$mode policy=$policy protectedSettingsPreserved=true pending=false")
            }
            assertTrue(controller.actionFailureMessage, controller.unlockToAuto(ssid, requestSource = "mac_recovery_test_unlock"))
            status = controller.refreshStatus(forceFresh = true)
            assertTrue(status.isConnected); assertEquals(WifiTargetMode.AUTO, prefs.getWifiTargetMode(ssid))
            val unlocked = ProfileAccess.callChecked(context, "inspect", ProfileAccess.identity(ssid, status.securityType!!, status.networkId))
            assertTrue("Saved pin remains", unlocked.getString("pin").isNullOrBlank())
            assertFalse(ProfileBackupStore(context).exists())
            Log.i("QuintzMacRecovery", "UNLOCK verified afterMode=$mode policy=RANDOMIZED pending=false")
        }
        WatchdogControl.stopIfNoTargets(context, prefs)
        assertFalse("Watchdog remains enabled with no targets", prefs.isWatchdogEnabled)
        Unit
    }
}
