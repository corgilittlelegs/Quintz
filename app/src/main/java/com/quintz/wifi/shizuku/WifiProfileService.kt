package com.quintz.wifi.shizuku

import android.net.wifi.WifiConfiguration
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.os.*
import java.security.MessageDigest
import com.quintz.wifi.core.profile.ProfileRecoveryException
import com.quintz.wifi.core.profile.RecoveryFailure

/** Framework objects stay in the privileged process. No text dumps or guessed Binder codes. */
@Suppress("DEPRECATION")
class WifiProfileService : IWifiProfileService.Stub() {
    private val wifi by lazy {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "wifi") as IBinder
        Class.forName("android.net.wifi.IWifiManager\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
    }
    private val moduleIdentity by lazy {
        listOf("/apex/com.android.wifi/apex_manifest.pb", "/apex/com.android.wifi/javalib/framework-wifi.jar", "/apex/com.android.wifi/javalib/service-wifi.jar")
            .map { java.io.File(it) }.filter { it.isFile }.joinToString("|") { file ->
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
                file.name + ":" + digest.digest().joinToString("") { "%02x".format(it) }
            }
    }
    private val callerPackage = "com.android.shell"
    private fun extras() = Bundle().apply {
        if (Build.VERSION.SDK_INT >= 31) {
            val builder = Class.forName("android.content.AttributionSource\$Builder")
                .getConstructor(Int::class.javaPrimitiveType).newInstance(Process.myUid())
            builder.javaClass.getMethod("setPackageName", String::class.java).invoke(builder, callerPackage)
            putParcelable("EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE", builder.javaClass.getMethod("build").invoke(builder) as Parcelable)
        }
    }
    private fun invoke(name: String, vararg alternatives: Array<out Any?>): Any? {
        for (args in alternatives) {
            val matches = wifi.javaClass.methods.filter { method ->
                method.name == name && method.parameterTypes.size == args.size &&
                    method.parameterTypes.zip(args).all { (type, arg) ->
                        arg == null && !type.isPrimitive || arg != null &&
                            (type.isInstance(arg) || type == Int::class.javaPrimitiveType && arg is Int || type == Boolean::class.javaPrimitiveType && arg is Boolean)
                    }
            }
            if (matches.size == 1) return matches.single().invoke(wifi, *args)
        }
        error("Unsupported framework signature")
    }
    private fun configured(): List<WifiConfiguration> {
        val slice = invoke("getPrivilegedConfiguredNetworks", arrayOf(callerPackage, null, extras()), arrayOf(callerPackage, null), arrayOf(callerPackage))
            ?: error("Missing profiles")
        return unpackList(slice).map { it as WifiConfiguration }
    }
    private fun unpackList(value: Any): List<*> =
        if (value is List<*>) value else value.javaClass.getMethod("getList").invoke(value) as List<*>
    private fun security(config: WifiConfiguration, preferred: String? = null): String = when {
        preferred == "2" && config.allowedKeyManagement[1] -> "2"
        preferred == "4" && config.allowedKeyManagement[8] -> "4"
        config.allowedKeyManagement[8] -> "4"
        config.allowedKeyManagement[1] -> "2"
        config.allowedKeyManagement[9] -> "6"
        config.allowedKeyManagement.cardinality() <= 1 && config.allowedKeyManagement[0] && config.wepKeys.all { it == null } -> "0"
        else -> error("Unsupported security")
    }
    private fun mac(config: WifiConfiguration): Int = WifiConfiguration::class.java.getField("macRandomizationSetting").getInt(config)
    private fun setMac(config: WifiConfiguration, value: Int) { WifiConfiguration::class.java.getField("macRandomizationSetting").setInt(config, value) }

    private fun expectedMac(config: WifiConfiguration): String {
        val address = if (mac(config) == 1) {
            WifiConfiguration::class.java.getMethod("getRandomizedMacAddress").invoke(config)?.toString()
                ?: error("Randomized MAC unavailable")
        } else {
            val addresses = invoke("getFactoryMacAddresses", emptyArray()) as? Array<*>
            addresses?.singleOrNull() as? String ?: error("Factory MAC unavailable or ambiguous")
        }
        check(address.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) &&
            address != "02:00:00:00:00:00" && address != "00:00:00:00:00:00") { "MAC identity unavailable" }
        return address.lowercase()
    }
    private fun exact(config: WifiConfiguration, request: Bundle): Boolean =
        config.SSID == request.getString("ssid") && security(config, request.getString("security")) == request.getString("security")
    private fun encode(config: WifiConfiguration) = WifiProfileFingerprint.encode(config)
    private fun decode(bytes: ByteArray) = WifiProfileFingerprint.decode(bytes)
    private fun fingerprint(config: WifiConfiguration) = WifiProfileFingerprint.fingerprint(config)
    private fun reject(reason: RecoveryFailure): Nothing = throw ProfileRecoveryException(reason)
    private fun validate(request: Bundle): WifiConfiguration {
        val version = request.getInt("fingerprintVersion", 1)
        if (version !in 1..WifiProfileFingerprint.CURRENT_VERSION) reject(RecoveryFailure.BACKUP_INVALID)
        val config = try { decode(request.getByteArray("payload") ?: reject(RecoveryFailure.BACKUP_INVALID)) }
            catch (failure: ProfileRecoveryException) { throw failure }
            catch (_: Exception) { reject(RecoveryFailure.BACKUP_INVALID) }
        val restored = snapshot(config, request.getString("security"), version)
        if (restored.getString("platform") != request.getString("platform")) reject(RecoveryFailure.PLATFORM_CHANGED)
        if (restored.getByteArray("fingerprint")?.contentEquals(request.getByteArray("fingerprint") ?: byteArrayOf()) != true)
            reject(RecoveryFailure.BACKUP_INVALID)
        return config
    }
    private fun snapshot(config: WifiConfiguration, preferred: String? = null, version: Int = WifiProfileFingerprint.CURRENT_VERSION) = Bundle().apply {
        val bytes = encode(config)
        check(encode(decode(bytes)).contentEquals(bytes)) { "Incomplete parcel round trip" }
        val sec = security(config, preferred)
        check(sec !in setOf("2", "4") || !config.preSharedKey.isNullOrEmpty() && config.preSharedKey != "*") { "Credentials redacted" }
        check(config.BSSID == null || config.BSSID.equals("any", true) || config.BSSID.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))) { "Unknown pin state" }
        check(config.FQDN.isNullOrEmpty()) { "Passpoint unsupported" }
        putBoolean("ok", true); putInt("id", config.networkId); putString("ssid", config.SSID)
        putString("security", sec); putString("pin", config.BSSID?.takeUnless { it.equals("any", true) })
        putInt("mac", mac(config))
        putByteArray("payload", bytes); putByteArray("fingerprint", WifiProfileFingerprint.fingerprint(config, version))
        putInt("fingerprintVersion", version)
        putString("platform", "${Build.FINGERPRINT}:${Build.VERSION.SDK_INT}:$moduleIdentity:" + WifiConfiguration::class.java.declaredFields.map { "${it.name}:${it.type.name}" }.sorted().joinToString("|"))
    }
    @Synchronized override fun call(operation: String, request: Bundle): Bundle = try {
        check(operation in setOf("scan", "status", "inspect") || Build.VERSION.SDK_INT in 29..36) { "Unqualified platform" }
        when (operation) {
            "selectedReadback" -> {
                val expected = validate(request)
                val matches = configured().filter { exact(it, request) }
                if (matches.size > 1) reject(RecoveryFailure.AMBIGUOUS_PROFILE)
                val current = matches.singleOrNull() ?: reject(RecoveryFailure.PROFILE_CHANGED)
                if (current.networkId != expected.networkId && !request.getBoolean("allowReassignedId"))
                    reject(RecoveryFailure.PROFILE_CHANGED)
                if (!WifiProfileFingerprint.matchesSelectedProfile(expected, current,
                        request.getBoolean("selectionRecorded"), request.getBoolean("originalExists")))
                    reject(RecoveryFailure.PROFILE_CHANGED)
                snapshot(current, request.getString("security")).apply { putString("expectedMac", expectedMac(current)) }
            }
            "status" -> Bundle().apply {
                val info = invoke("getConnectionInfo", arrayOf(callerPackage, null), arrayOf(callerPackage)) as WifiInfo
                putParcelable("info", info); putBoolean("ok", true)
            }
            "scan" -> Bundle().apply {
                val results = unpackList(invoke("getScanResults", arrayOf(callerPackage, null), arrayOf(callerPackage)) ?: error("Missing scans"))
                putParcelableArrayList("radios", ArrayList(results.map { it as ScanResult })); putBoolean("ok", true)
            }
            "inspect" -> {
                val config = configured().single { it.networkId == request.getInt("id") && exact(it, request) }
                check(config.BSSID == null || config.BSSID.equals("any", true) || config.BSSID.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")))
                Bundle().apply {
                    putBoolean("ok", true); putInt("id", config.networkId)
                    putString("pin", config.BSSID?.takeUnless { it.equals("any", true) })
                    putInt("mac", mac(config))
                }
            }
            "capture" -> snapshot(configured().single { it.networkId == request.getInt("id") && exact(it, request) }, request.getString("security"))
            "lookup" -> {
                val matches = configured().filter { exact(it, request) && (request.getInt("id", -1) < 0 || it.networkId == request.getInt("id")) }
                if (matches.isEmpty()) Bundle().apply { putBoolean("ok", true); putBoolean("absent", true) }
                else { if (matches.size > 1) reject(RecoveryFailure.AMBIGUOUS_PROFILE); snapshot(matches.single(), request.getString("security")) }
            }
            "preview" -> {
                val config = if (request.containsKey("payload")) decode(request.getByteArray("payload")!!) else WifiConfiguration().apply {
                    SSID = request.getString("ssid")
                    // Use the framework's security-parameter constructor, never guess WPA2.
                    WifiConfiguration::class.java.getMethod("setSecurityParams", Int::class.javaPrimitiveType)
                        .invoke(this, request.getString("security")!!.toInt())
                }
                if (request.containsKey("newPin")) config.BSSID = request.getString("newPin")
                if (request.containsKey("newMac")) setMac(config, request.getInt("newMac"))
                if (request.containsKey("newPassword")) config.preSharedKey = request.getString("newPassword")
                snapshot(config, request.getString("security")).also {
                    if (request.getBoolean("macOnly")) it.putString("expectedMac", expectedMac(config))
                }
            }
            "validate" -> snapshot(validate(request), request.getString("security"), request.getInt("fingerprintVersion", 1))
            "upgrade" -> snapshot(validate(request), request.getString("security"))
            "put" -> {
                if (request.getInt("fingerprintVersion", 1) != WifiProfileFingerprint.CURRENT_VERSION) reject(RecoveryFailure.BACKUP_INVALID)
                val original = validate(request)
                check(exact(original, request))
                val current = configured().filter { exact(it, request) }
                val expected = request.getByteArray("expected")
                if (current.size > 1) reject(RecoveryFailure.AMBIGUOUS_PROFILE)
                if (current.isNotEmpty() && (expected == null || !fingerprint(current.single()).contentEquals(expected))) reject(RecoveryFailure.PROFILE_CHANGED)
                original.networkId = current.singleOrNull()?.networkId ?: -1
                if (request.containsKey("newPin")) original.BSSID = request.getString("newPin")
                if (request.containsKey("newMac")) setMac(original, request.getInt("newMac"))
                if (request.containsKey("newPassword")) original.preSharedKey = request.getString("newPassword")
                // "any" explicitly clears a saved pin on frameworks that merge a null BSSID.
                if (original.BSSID == null || original.BSSID.equals("any", true)) original.BSSID = "any"
                val expectedAfter = fingerprint(original)
                val id = invoke("addOrUpdateNetwork", arrayOf(original, callerPackage, extras()), arrayOf(original, callerPackage)) as Int
                check(id >= 0)
                snapshot(configured().single { it.networkId == id && exact(it, request) }, request.getString("security")).also {
                    check(it.getByteArray("fingerprint")!!.contentEquals(expectedAfter)) { "Restore readback differs" }
                }
            }
            "delete" -> {
                val current = configured().single { it.networkId == request.getInt("id") && exact(it, request) }
                check(fingerprint(current).contentEquals(request.getByteArray("fingerprint")!!)) { "Profile changed before deletion" }
                check(invoke("removeNetwork", arrayOf(current.networkId, callerPackage)) == true)
                Bundle().apply { putBoolean("ok", true) }
            }
            "select" -> {
                val current = configured().single { it.networkId == request.getInt("id") && exact(it, request) }
                check(fingerprint(current).contentEquals(request.getByteArray("fingerprint")!!))
                // Reassociate the affected saved network; never toggle the Wi-Fi radio.
                check(invoke("disableNetwork", arrayOf(current.networkId, callerPackage)) == true)
                check(invoke("enableNetwork", arrayOf(current.networkId, true, callerPackage)) == true)
                Bundle().apply { putBoolean("ok", true) }
            }
            else -> error("Unknown operation")
        }
    } catch (failure: Exception) {
        // Never return exception text: vendor exceptions can contain profile credentials.
        Bundle().apply {
            putBoolean("ok", false); putString("error", failure.javaClass.simpleName)
            putString("reason", (failure as? ProfileRecoveryException)?.reason?.name ?:
                if (operation in setOf("put", "select", "delete")) RecoveryFailure.RESTORE_FAILED.name else RecoveryFailure.ACCESS_UNAVAILABLE.name)
        }
    }
    override fun destroy() { kotlin.system.exitProcess(0) }
}
