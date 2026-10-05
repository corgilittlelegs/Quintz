package com.quintz.wifi

import android.net.LinkAddress
import android.net.MacAddress
import android.net.ProxyInfo
import android.net.wifi.WifiConfiguration
import android.os.Build
import android.os.Bundle
import com.quintz.wifi.shizuku.WifiProfileFingerprint
import com.quintz.wifi.shizuku.WifiProfileService
import java.net.InetAddress

/** Run through ADB app_process as shell, matching the production service's reflection visibility.
 * Uses only synthetic in-memory configurations. Never reads or modifies a saved network. */
@Suppress("DEPRECATION")
object ProfileFingerprintShellTest {
    @JvmStatic fun main(args: Array<String>) {
        var count = 0
        fun fixture() = WifiConfiguration().apply {
            SSID = "\"quintz-regression\""; preSharedKey = "\"synthetic-regression-password\""
            WifiConfiguration::class.java.getMethod("setSecurityParams", Int::class.javaPrimitiveType).invoke(this, 4)
        }
        fun clone(config: WifiConfiguration) = WifiProfileFingerprint.decode(WifiProfileFingerprint.encode(config))
        fun set(config: WifiConfiguration, name: String, value: Any?) {
            WifiConfiguration::class.java.getDeclaredField(name).apply { isAccessible = true }.set(config, value)
        }
        fun verify(name: String, condition: Boolean) { check(condition) { name }; count++; println("PASS $name") }
        fun changed(name: String, mutate: (WifiConfiguration) -> Unit, ignored: Boolean = false) {
            val original = fixture(); val next = clone(original); mutate(next)
            val equal = WifiProfileFingerprint.fingerprint(original).contentEquals(WifiProfileFingerprint.fingerprint(next))
            verify(name, equal == ignored)
        }
        changed("association and ID bookkeeping", { it.networkId = 91; set(it, "numAssociation", 27) }, true)
        changed("connection-selection bookkeeping", { set(it, "mIsUserSelected", true); set(it, "isCurrentlyConnected", true) }, true)
        changed("password-encryption marker", { set(it, "mHasPreSharedKeyChanged", true) }, true)
        changed("MAC timing bookkeeping", { set(it, "randomizedMacExpirationTimeMs", 100L); set(it, "randomizedMacLastModifiedTimeMs", 200L) }, true)
        changed("empty non-DPP configurator flag", { set(it, "mIsDppConfigurator", true) }, true)
        changed("encrypted representation with unredacted password", { set(it, "mEncryptedPreSharedKey", byteArrayOf(1)); set(it, "mEncryptedPreSharedKeyIv", byteArrayOf(2)) }, true)
        changed("actual password", { it.preSharedKey = "\"different-synthetic-password\"" })
        changed("actual BSSID pin", { it.BSSID = "02:11:22:33:44:55" })
        changed("SSID identity", { it.SSID = "\"other-regression-network\"" })
        changed("MAC policy", { config ->
            val field = WifiConfiguration::class.java.getDeclaredField("macRandomizationSetting").apply { isAccessible = true }
            field.setInt(config, if (field.getInt(config) == 0) 1 else 0)
        })
        changed("actual randomized MAC", { set(it, "mRandomizedMacAddress", MacAddress.fromString("02:aa:bb:cc:dd:ee")) })
        changed("security parameters", { WifiConfiguration::class.java.getMethod("setSecurityParams", Int::class.javaPrimitiveType).invoke(it, 2) })
        for (name in listOf("mDppPrivateEcKey", "mDppConnector", "mDppCSignKey", "mDppNetAccessKey")) {
            changed("protected $name", { set(it, name, byteArrayOf(1, 2, 3)) })
        }
        val dppMaterial = fixture().apply { set(this, "mDppPrivateEcKey", byteArrayOf(1)) }
        val flaggedMaterial = clone(dppMaterial).apply { set(this, "mIsDppConfigurator", true) }
        verify("configurator capability with key material", !WifiProfileFingerprint.fingerprint(dppMaterial).contentEquals(WifiProfileFingerprint.fingerprint(flaggedMaterial)))
        val dppNetwork = fixture().apply { WifiConfiguration::class.java.getMethod("setSecurityParams", Int::class.javaPrimitiveType).invoke(this, 13) }
        val flaggedDppNetwork = clone(dppNetwork).apply { set(this, "mIsDppConfigurator", true) }
        verify("configurator capability on DPP security", !WifiProfileFingerprint.fingerprint(dppNetwork).contentEquals(WifiProfileFingerprint.fingerprint(flaggedDppNetwork)))
        val redacted = fixture().apply { preSharedKey = "*" }
        val redactedChanged = clone(redacted).apply { set(this, "mEncryptedPreSharedKey", byteArrayOf(1)) }
        verify("redacted password cannot hide encrypted changes", !WifiProfileFingerprint.fingerprint(redacted).contentEquals(WifiProfileFingerprint.fingerprint(redactedChanged)))
        fun ip(config: WifiConfiguration, withProxy: Boolean) {
            val type = Class.forName("android.net.IpConfiguration")
            val value = type.getConstructor().newInstance()
            fun enum(name: String, choice: String) {
                val field = type.getField(name)
                field.set(value, field.type.enumConstants.single { (it as Enum<*>).name == choice })
            }
            if (withProxy) {
                enum("ipAssignment", "DHCP"); enum("proxySettings", "STATIC")
                type.getField("httpProxy").set(value, ProxyInfo.buildDirectProxy("proxy.example", 8080))
            } else {
                enum("ipAssignment", "STATIC"); enum("proxySettings", "NONE")
                val staticType = Class.forName("android.net.StaticIpConfiguration")
                val static = staticType.getConstructor().newInstance()
                staticType.getField("ipAddress").set(static, LinkAddress::class.java.getConstructor(String::class.java).newInstance("192.0.2.2/24"))
                staticType.getField("gateway").set(static, InetAddress.getByName("192.0.2.1"))
                type.getField("staticIpConfiguration").set(value, static)
            }
            set(config, "mIpConfiguration", value)
        }
        changed("static IP settings", { ip(it, false) })
        changed("proxy settings", { ip(it, true) })
        val automatic = fixture(); val any = clone(automatic).apply { BSSID = "any" }
        verify("automatic pin null and any are equivalent", WifiProfileFingerprint.fingerprint(automatic).contentEquals(WifiProfileFingerprint.fingerprint(any)))
        val legacy = clone(automatic).apply { set(this, "mIsUserSelected", true) }
        verify("legacy comparison preserved for migration", !WifiProfileFingerprint.fingerprint(automatic, 1).contentEquals(WifiProfileFingerprint.fingerprint(legacy, 1)))
        val pinned = fixture().apply { BSSID = "02:11:22:33:44:55"; ip(this, true) }
        val policyField = WifiConfiguration::class.java.getField("macRandomizationSetting")
        policyField.setInt(pinned, 0)
        val preview = WifiProfileService().call("preview", Bundle().apply {
            putByteArray("payload", WifiProfileFingerprint.encode(pinned)); putString("security", "4"); putInt("newMac", 1)
        })
        verify("MAC-only preview succeeds", preview.getBoolean("ok"))
        val updated = WifiProfileFingerprint.decode(preview.getByteArray("payload")!!)
        verify("MAC-only preview changes policy", policyField.getInt(updated) == 1)
        verify("MAC-only preview retains password and pin", updated.preSharedKey == pinned.preSharedKey && updated.BSSID == pinned.BSSID)
        policyField.setInt(updated, 0)
        verify("MAC-only preview retains all other protected fields", WifiProfileFingerprint.fingerprint(pinned).contentEquals(WifiProfileFingerprint.fingerprint(updated)))
        val macFixture = clone(pinned).apply { set(this, "mRandomizedMacAddress", MacAddress.fromString("02:aa:bb:cc:dd:ee")) }
        val service = WifiProfileService()
        fun macPreview(policy: Int) = service.call("preview", Bundle().apply {
            putByteArray("payload", WifiProfileFingerprint.encode(macFixture)); putString("security", "4")
            putInt("newMac", policy); putBoolean("macOnly", true)
        })
        val randomPreview = macPreview(1)
        verify("randomized MAC verification identity available", randomPreview.getBoolean("ok") && randomPreview.getString("expectedMac") == "02:aa:bb:cc:dd:ee")
        val devicePreview = macPreview(0)
        verify("factory MAC verification identity available to shell", devicePreview.getBoolean("ok") && !devicePreview.getString("expectedMac").isNullOrBlank())
        val randomBefore = clone(macFixture).apply { policyField.setInt(this, 1) }
        val regenerated = clone(randomBefore).apply { set(this, "mRandomizedMacAddress", MacAddress.fromString("66:24:45:89:cf:d6")) }
        fun accepts(actual: WifiConfiguration, selected: Boolean = true, existing: Boolean = true) =
            WifiProfileFingerprint.matchesSelectedProfile(randomBefore, actual, selected, existing)
        verify("recorded reconnect accepts only generated address change", accepts(regenerated))
        verify("ordinary fingerprint still protects generated address", !WifiProfileFingerprint.fingerprint(randomBefore).contentEquals(WifiProfileFingerprint.fingerprint(regenerated)))
        verify("save-only does not accept generated address change", !accepts(regenerated, selected = false))
        verify("unknown profile ownership does not accept generated address change", !accepts(regenerated, existing = false))
        fun protects(name: String, mutation: (WifiConfiguration) -> Unit) {
            val altered = clone(regenerated); mutation(altered)
            verify("selected reconnect protects $name", !accepts(altered))
        }
        protects("password", { it.preSharedKey = "\"different-synthetic-password\"" })
        protects("BSSID pin", { it.BSSID = "02:11:22:33:44:56" })
        protects("SSID", { it.SSID = "\"other-regression-network\"" })
        protects("MAC policy", { policyField.setInt(it, 0) })
        protects("security", { WifiConfiguration::class.java.getMethod("setSecurityParams", Int::class.javaPrimitiveType).invoke(it, 2) })
        protects("static IP", { ip(it, false) })
        protects("proxy", {
            val config = WifiConfiguration::class.java.getDeclaredField("mIpConfiguration").apply { isAccessible = true }.get(it)
            config.javaClass.getField("httpProxy").set(config, ProxyInfo.buildDirectProxy("other.proxy.example", 8081))
        })
        for (name in listOf("mDppPrivateEcKey", "mDppConnector", "mDppCSignKey", "mDppNetAccessKey")) {
            protects(name, { set(it, name, byteArrayOf(1, 2, 3)) })
        }
        protects("placeholder randomized address", { set(it, "mRandomizedMacAddress", MacAddress.fromString("02:00:00:00:00:00")) })
        protects("multicast address", { set(it, "mRandomizedMacAddress", MacAddress.fromString("67:24:45:89:cf:d6")) })
        protects("global address used as randomized", { set(it, "mRandomizedMacAddress", MacAddress.fromString("64:24:45:89:cf:d6")) })
        val deviceBefore = clone(randomBefore).apply { policyField.setInt(this, 0) }
        val deviceAddressChange = clone(regenerated).apply { policyField.setInt(this, 0) }
        verify("Device MAC profile has no generated address exception", !WifiProfileFingerprint.matchesSelectedProfile(deviceBefore, deviceAddressChange, true, true))
        println("PROFILE_FINGERPRINT_TESTS_OK tests=$count sdk=${Build.VERSION.SDK_INT}")
    }
}
