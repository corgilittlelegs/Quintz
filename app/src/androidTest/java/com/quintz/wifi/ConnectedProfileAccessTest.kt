package com.quintz.wifi

import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quintz.wifi.core.profile.ProfileAccess
import com.quintz.wifi.core.WifiController
import com.quintz.wifi.shizuku.ShizukuManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reads and previews the active profile; never writes/selects/deletes a saved network. */
@RunWith(AndroidJUnit4::class)
class ConnectedProfileAccessTest {
    @Test fun controllerReadsLiveIdentityAndFreshScannerRows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ShizukuManager.updateState(context)
        val ready = withTimeoutOrNull(5_000L) {
            ShizukuManager.state.first { it.isRunning && it.isPermissionGranted }
        }
        assumeTrue("Requires authorized Shizuku on a connected device", ready != null)
        val controller = WifiController(context)
        val status = controller.refreshStatus(forceFresh = true)
        assertTrue("Connection is not reported", status.isConnected)
        assertTrue("Exact profile ID unavailable", (status.networkId ?: -1) >= 0)
        assertTrue("Exact profile inspection unavailable", status.profileInspectionKnown)
        assertTrue("IP address unavailable", status.ipAddress.isNotEmpty() && status.ipAddress != "0.0.0.0")
        val radios = controller.scanRadios()
        assertTrue("No fresh scanner observations", radios.isNotEmpty())
        assertTrue("Connected SSID missing from fresh observations", radios.any { it.ssid == status.ssid })
        assertTrue("Invalid scan evidence admitted", radios.all {
            it.ageSeconds in 0L..15L && it.rssi in -126..-1 && it.bssid.isNotEmpty()
        })
        Unit
    }
    @Test fun activeProfileCanBeCapturedValidatedAndPreviewedWithoutMutation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ShizukuManager.updateState(context)
        val ready = withTimeoutOrNull(5_000L) {
            ShizukuManager.state.first { it.isRunning && it.isPermissionGranted }
        }
        // Ordinary CI emulators do not have Shizuku or a real saved Wi-Fi profile.
        assumeTrue("Requires authorized Shizuku and Android 12+", ready != null && Build.VERSION.SDK_INT >= 31)
        val status = ProfileAccess.call(context, "status", Bundle())
        assertNotNull("Structured privileged status unavailable", status)
        @Suppress("DEPRECATION") val info = status!!.getParcelable<WifiInfo>("info")!!
        assertTrue("Active profile ID unavailable", info.networkId >= 0)
        val ssid = info.ssid.removeSurrounding("\"")
        assertTrue("Active identity unavailable", ssid.isNotEmpty() && ssid != "<unknown ssid>")
        val security = info.currentSecurityType.toString()
        assumeTrue("Requires a supported Open/WPA2/WPA3/OWE profile", security in setOf("0", "2", "4", "6"))
        val identity = ProfileAccess.identity(ssid, security, info.networkId)
        val original = ProfileAccess.call(context, "lookup", identity)
        assertNotNull("Complete active profile unavailable", original)
        assertFalse("Active saved profile absent", original!!.getBoolean("absent"))
        assertEquals(info.networkId, original.getInt("id"))
        assertEquals("\"$ssid\"", original.getString("ssid"))
        assertTrue("Missing full parcel", original.getByteArray("payload")!!.isNotEmpty())
        assertEquals(32, original.getByteArray("fingerprint")!!.size)
        assertNotNull("Full parcel validation failed", ProfileAccess.call(context, "validate", original))
        val inspection = ProfileAccess.call(context, "inspect", identity)
        assertNotNull("Exact profile inspection failed", inspection)
        assertEquals(original.getInt("mac"), inspection!!.getInt("mac"))
        val preview = ProfileAccess.call(context, "preview", Bundle(original).apply {
            putString("newPin", original.getString("pin"))
            putInt("newMac", original.getInt("mac"))
        })
        assertNotNull("Read-only profile preview failed", preview)
        assertArrayEquals(original.getByteArray("fingerprint"), preview!!.getByteArray("fingerprint"))
        val after = ProfileAccess.call(context, "lookup", identity)
        assertNotNull("Final readback unavailable", after)
        assertArrayEquals(original.getByteArray("fingerprint"), after!!.getByteArray("fingerprint"))
        Unit
    }
}
