package com.quintz.wifi.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.quintz.wifi.model.MacAddressPolicy
import com.quintz.wifi.model.SavedCredentialState

enum class WifiTargetMode { AUTO, PREFER_5_GHZ, PIN_BSSID }

class Preferences private constructor(context: Context) {

    companion object {
        @Volatile private var instance: Preferences? = null

        fun get(context: Context): Preferences = instance ?: synchronized(this) {
            instance ?: Preferences(context.applicationContext).also { instance = it }
        }
    }

    private val fallbackPrefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    private var settingsAvailable = true
    private var legacyPasswordCleanupSucceeded = true
    private val securePrefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        null
    }
    // Non-secret settings remain usable if Android Keystore is unavailable. Passwords do not.
    private val prefs: SharedPreferences = context.getSharedPreferences("settings_v2", Context.MODE_PRIVATE)

    init {
        // Settings have one stable store, independent of credential storage availability.
        // Preserve conflicting legacy values until the user chooses a source.
        if (!prefs.getBoolean("migration_v2_complete", false)) {
            val secureValues = runCatching { securePrefs?.all.orEmpty() }.getOrDefault(emptyMap())
            val legacy = fallbackPrefs.all
            val editor = prefs.edit()
            (secureValues.keys + legacy.keys).filterNot { it.startsWith("pwd_") }.forEach { key ->
                if (prefs.contains(key)) return@forEach
                val a = secureValues[key]; val b = legacy[key]
                if (a != null && b != null && a != b) {
                    putValue(editor, "conflict_secure_$key", a)
                    putValue(editor, "conflict_fallback_$key", b)
                } else putValue(editor, key, b ?: a)
            }
            if (securePrefs != null) editor.putBoolean("migration_v2_complete", true)
            settingsAvailable = editor.commit()
            // Only the credential store receives any legacy plaintext credentials.
            securePrefs?.let { secure ->
                val credentials = secure.edit()
                legacy.filterKeys { it.startsWith("pwd_") }.forEach { (key, value) ->
                    if (!secure.contains(key) && value is String) credentials.putString(key, value)
                }
                credentials.commit()
            }
        }
        // If Keystore or migration is unavailable, do not retain an older plaintext copy.
        // The user can enter the password again once secure storage works.
        val plaintextPasswordKeys = fallbackPrefs.all.keys.filter { it.startsWith("pwd_") }
        if (plaintextPasswordKeys.isNotEmpty()) {
            val cleanup = fallbackPrefs.edit()
            plaintextPasswordKeys.forEach { cleanup.remove(it) }
            legacyPasswordCleanupSucceeded = if (cleanup.commit()) true else {
                // Losing non-secret fallback settings is safer than retaining old plaintext passwords.
                context.deleteSharedPreferences("prefs")
            }
        }
        // This legacy setting was never used by the steering control path.
        if (prefs.contains("fallback_threshold")) prefs.edit().remove("fallback_threshold").apply()
        if (fallbackPrefs !== prefs && fallbackPrefs.contains("fallback_threshold")) {
            fallbackPrefs.edit().remove("fallback_threshold").apply()
        }
    }

    val isPasswordStorageAvailable: Boolean get() = securePrefs != null && legacyPasswordCleanupSucceeded

    fun savePassword(ssid: String, pass: String): Boolean = runCatching {
        isPasswordStorageAvailable && securePrefs?.edit()?.putString("pwd_$ssid", pass)?.commit() == true
    }.getOrDefault(false)

    fun getPassword(ssid: String): String? = runCatching {
        securePrefs?.takeIf { isPasswordStorageAvailable }?.getString("pwd_$ssid", null)
    }.getOrNull()

    fun savedCredentialState(ssid: String): SavedCredentialState {
        if (!isPasswordStorageAvailable) return SavedCredentialState.UNAVAILABLE
        return runCatching {
            if (securePrefs!!.getString("pwd_$ssid", null).isNullOrEmpty()) SavedCredentialState.NOT_SAVED
            else SavedCredentialState.SAVED
        }.getOrDefault(SavedCredentialState.UNAVAILABLE)
    }

    fun removePassword(ssid: String): Boolean = runCatching {
        securePrefs?.edit()?.remove("pwd_$ssid")?.commit() == true && fallbackPrefs.edit().remove("pwd_$ssid").commit()
    }.getOrDefault(false)

    val migrationConflicts: List<String>
        get() = prefs.all.keys.filter { it.startsWith("conflict_secure_") }.map { it.removePrefix("conflict_secure_") }.sorted()

    fun resolveMigrationConflicts(useFallback: Boolean): Boolean {
        val editor = prefs.edit()
        migrationConflicts.forEach { key ->
            putValue(editor, key, prefs.all[(if (useFallback) "conflict_fallback_" else "conflict_secure_") + key])
            editor.remove("conflict_secure_$key").remove("conflict_fallback_$key")
        }
        return editor.commit()
    }

    private fun putValue(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            null -> editor.remove(key)
        }
    }

    fun transitionSettings(ssid: String, bssid: String): android.os.Bundle = android.os.Bundle().apply {
        val keys = listOf("wifi_target_mode_$ssid", "wifi_pinned_bssid_$ssid", "mac_policy_$ssid", "pending_mac_policy_$ssid", "pending_mac_address_$ssid", "trusted_security_$ssid", "trusted_radio_${ssid}_${bssid.lowercase()}")
        keys.forEach { key -> putString(key, prefs.getString(key, null)) }
        putString("pwd_$ssid", getPassword(ssid))
    }

    fun restoreTransitionSettings(settings: android.os.Bundle): Boolean {
        val editor = prefs.edit()
        var secretOk = true
        settings.keySet().forEach { key ->
            val value = settings.getString(key)
            if (key.startsWith("pwd_")) {
                // An absent original credential requires removal, not a plaintext fallback.
                secretOk = if (value == null) securePrefs?.edit()?.remove(key)?.commit() ?: true
                    else securePrefs?.edit()?.putString(key, value)?.commit() == true
            } else if (value == null) editor.remove(key) else editor.putString(key, value)
        }
        return editor.commit() && secretOk
    }

    fun commitTransition(ssid: String, bssid: String?, security: String, password: String,
                         policy: MacAddressPolicy, mode: WifiTargetMode?, establishTrust: Boolean): Boolean {
        if (!settingsAvailable) return false
        if (password.isNotEmpty() && !savePassword(ssid, password)) return false
        val editor = prefs.edit().putString("mac_policy_$ssid", policy.name)
            .remove("pending_mac_policy_$ssid").remove("pending_mac_address_$ssid")
        if (mode != null) {
            editor.putString("wifi_target_mode_$ssid", mode.name)
            if (mode == WifiTargetMode.PIN_BSSID) editor.putString("wifi_pinned_bssid_$ssid", bssid?.lowercase())
            else editor.remove("wifi_pinned_bssid_$ssid")
        }
        if (establishTrust && bssid != null) editor.putString("trusted_radio_${ssid}_${bssid.lowercase()}", security)
        if (security in setOf("2", "4")) editor.putString("trusted_security_$ssid", security)
        return editor.commit()
    }

    fun rememberConnectedSecurity(ssid: String, securityType: String) {
        if (securityType == "2" || securityType == "4") {
            prefs.edit().putString("trusted_security_$ssid", securityType).apply()
        }
    }

    fun getTrustedSecurity(ssid: String): String? =
        prefs.getString("trusted_security_$ssid", null)?.takeIf { it == "2" || it == "4" }

    /** Only a verified, user-selected radio is eligible for later automatic steering. */
    fun trustSelectedRadio(ssid: String, bssid: String, securityType: String): Boolean {
        if (securityType !in setOf("0", "2", "4", "6")) return false
        val normalizedBssid = bssid.lowercase()
        if (!normalizedBssid.matches(Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}"))) return false
        return prefs.edit().putString("trusted_radio_${ssid}_$normalizedBssid", securityType).commit()
    }

    fun getTrustedRadioSecurity(ssid: String, bssid: String): String? =
        prefs.getString("trusted_radio_${ssid}_${bssid.lowercase()}", null)
            ?.takeIf { it in setOf("0", "2", "4", "6") }

    fun getMacPolicy(ssid: String): MacAddressPolicy? {
        val raw = prefs.getString("mac_policy_$ssid", null) ?: return null
        return runCatching { MacAddressPolicy.valueOf(raw) }.getOrNull()
    }

    fun setMacPolicy(ssid: String, policy: MacAddressPolicy) {
        prefs.edit().putString("mac_policy_$ssid", policy.name).apply()
    }

    /** MAC-only rollback never reads or writes Quintz's separate password copy. */
    fun macTransitionSettings(ssid: String): android.os.Bundle = android.os.Bundle().apply {
        listOf("mac_policy_$ssid", "pending_mac_policy_$ssid", "pending_mac_address_$ssid").forEach {
            putString(it, prefs.getString(it, null))
        }
    }

    fun commitMacPolicy(ssid: String, policy: MacAddressPolicy, pendingAddress: String?): Boolean {
        if (!settingsAvailable) return false
        return prefs.edit().putString("mac_policy_$ssid", policy.name)
            .putString("pending_mac_policy_$ssid", policy.name.takeIf { pendingAddress != null })
            .putString("pending_mac_address_$ssid", pendingAddress)
            .commit()
    }

    fun isMacPolicyPending(ssid: String, configured: MacAddressPolicy?, observedAddress: String?): Boolean {
        val expected = prefs.getString("pending_mac_address_$ssid", null) ?: return false
        return prefs.getString("pending_mac_policy_$ssid", null) == configured?.name &&
            !expected.equals(observedAddress, ignoreCase = true)
    }

    fun clearMacPolicy(ssid: String) {
        prefs.edit().remove("mac_policy_$ssid").apply()
    }

    var defaultMacPolicy: MacAddressPolicy
        get() {
            val raw = prefs.getString("default_mac_policy", MacAddressPolicy.DEVICE.name)
            return runCatching { MacAddressPolicy.valueOf(raw ?: MacAddressPolicy.DEVICE.name) }.getOrDefault(MacAddressPolicy.DEVICE)
        }
        set(value) = prefs.edit().putString("default_mac_policy", value.name).apply()

    var isDarkMode: Boolean?
        get() = if (prefs.contains("dark_mode")) prefs.getBoolean("dark_mode", true) else null
        set(value) {
            if (value == null) {
                prefs.edit().remove("dark_mode").apply()
            } else {
                prefs.edit().putBoolean("dark_mode", value).apply()
            }
        }

    var isWatchdogEnabled: Boolean
        get() = settingsAvailable && migrationConflicts.isEmpty() && prefs.getBoolean("watchdog_enabled", false)
        set(value) = prefs.edit().putBoolean("watchdog_enabled", value).apply()

    /** Conservatively retain the watchdog if saved targets cannot be read. */
    fun hasWatchdogTargets(): Boolean = runCatching { hasWatchdogTargets(prefs.all) }.getOrDefault(true)

    var batteryOptimizationPromptShown: Boolean
        get() = prefs.getBoolean("battery_optimization_prompt_shown", false)
        set(value) = prefs.edit().putBoolean("battery_optimization_prompt_shown", value).apply()

    var lastTargetBand: String
        get() = prefs.getString("last_target_band", "5GHz") ?: "5GHz"
        set(value) = prefs.edit().putString("last_target_band", value).apply()

    fun getWifiTargetMode(ssid: String): WifiTargetMode? =
        prefs.getString("wifi_target_mode_$ssid", null)?.let { runCatching { WifiTargetMode.valueOf(it) }.getOrNull() }

    fun setWifiTargetMode(ssid: String, mode: WifiTargetMode, pinnedBssid: String? = null) {
        prefs.edit()
            .putString("wifi_target_mode_$ssid", mode.name)
            .apply {
                if (mode == WifiTargetMode.PIN_BSSID && !pinnedBssid.isNullOrBlank()) {
                    putString("wifi_pinned_bssid_$ssid", pinnedBssid.lowercase())
                } else {
                    remove("wifi_pinned_bssid_$ssid")
                }
            }
            .apply()
    }

    fun getPinnedBssid(ssid: String): String? = prefs.getString("wifi_pinned_bssid_$ssid", null)

    /** Initializes per-network intent from the legacy global preference and observed profile. */
    fun getOrMigrateWifiTargetMode(ssid: String, profileBssid: String? = null): WifiTargetMode {
        getWifiTargetMode(ssid)?.let { return it }
        val isLegacyTargetNetwork = watchdogLastSsid == ssid
        val mode = when {
            !profileBssid.isNullOrBlank() -> WifiTargetMode.PIN_BSSID
            !prefs.contains("last_target_band") -> WifiTargetMode.AUTO
            !isLegacyTargetNetwork -> WifiTargetMode.AUTO
            lastTargetBand.equals("Auto", ignoreCase = true) -> WifiTargetMode.AUTO
            else -> WifiTargetMode.PREFER_5_GHZ
        }
        setWifiTargetMode(ssid, mode, profileBssid)
        return mode
    }

    var recoveryThresholdRssi: Int
        get() {
            val v = prefs.getInt("recovery_threshold", -72)
            if (v == -65 && !prefs.contains("recovery_threshold_user_modified")) {
                return -72
            }
            return if (v < -85) -85 else v
        }
        set(value) {
            prefs.edit()
                .putInt("recovery_threshold", value)
                .putBoolean("recovery_threshold_user_modified", true)
                .apply()
        }

    var isQuickTileAdded: Boolean
        get() = prefs.getBoolean("quick_tile_added", false)
        set(value) = prefs.edit().putBoolean("quick_tile_added", value).apply()

    var watchdogLastSsid: String?
        get() = prefs.getString("watchdog_last_ssid", null)
        set(value) = prefs.edit().putString("watchdog_last_ssid", value).apply()

    var isWatchdogFallbackActive: Boolean
        get() = prefs.getBoolean("watchdog_fallback_active", false)
        set(value) = prefs.edit().putBoolean("watchdog_fallback_active", value).apply()
}
