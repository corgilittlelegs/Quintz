package com.quintz.wifi.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.quintz.wifi.model.MacAddressPolicy

enum class WifiTargetMode { AUTO, PREFER_5_GHZ, PIN_BSSID }

class Preferences(context: Context) {

    private val fallbackPrefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
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
    private val prefs: SharedPreferences = securePrefs ?: fallbackPrefs

    init {
        // Older versions used ordinary preferences when Keystore failed. Move every supported
        // setting, including passwords, so restoring Keystore does not lose the user's choices.
        securePrefs?.let { secure ->
            runCatching {
                val legacyValues = fallbackPrefs.all
                if (legacyValues.isNotEmpty()) {
                    val secureEdit = secure.edit()
                    val migratedKeys = mutableListOf<String>()
                    legacyValues.forEach { (key, value) ->
                        if (secure.contains(key)) {
                            migratedKeys += key
                        } else {
                            when (value) {
                                is String -> secureEdit.putString(key, value)
                                is Boolean -> secureEdit.putBoolean(key, value)
                                is Int -> secureEdit.putInt(key, value)
                                is Long -> secureEdit.putLong(key, value)
                                is Float -> secureEdit.putFloat(key, value)
                                is Set<*> -> {
                                    val strings = value.filterIsInstance<String>()
                                    if (strings.size == value.size) {
                                        secureEdit.putStringSet(key, strings.toSet())
                                    } else return@forEach
                                }
                                else -> return@forEach
                            }
                            migratedKeys += key
                        }
                    }
                    if (secureEdit.commit()) {
                        val cleanup = fallbackPrefs.edit()
                        migratedKeys.forEach { cleanup.remove(it) }
                        if (!cleanup.commit() && migratedKeys.any { it.startsWith("pwd_") }) {
                            legacyPasswordCleanupSucceeded = context.deleteSharedPreferences("prefs")
                        }
                    }
                }
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
        prefs.edit().remove("fallback_threshold").apply()
        fallbackPrefs.edit().remove("fallback_threshold").apply()
    }

    val isPasswordStorageAvailable: Boolean get() = securePrefs != null && legacyPasswordCleanupSucceeded

    fun savePassword(ssid: String, pass: String): Boolean = runCatching {
        isPasswordStorageAvailable && securePrefs?.edit()?.putString("pwd_$ssid", pass)?.commit() == true
    }.getOrDefault(false)

    fun getPassword(ssid: String): String? = runCatching {
        securePrefs?.takeIf { isPasswordStorageAvailable }?.getString("pwd_$ssid", null)
    }.getOrNull()

    fun removePassword(ssid: String) {
        securePrefs?.edit()?.remove("pwd_$ssid")?.apply()
        fallbackPrefs.edit().remove("pwd_$ssid").apply()
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

    fun clearMacPolicy(ssid: String) {
        prefs.edit().remove("mac_policy_$ssid").apply()
    }

    var defaultMacPolicy: MacAddressPolicy
        get() {
            val raw = prefs.getString("default_mac_policy", MacAddressPolicy.DEVICE.name)
            return runCatching { MacAddressPolicy.valueOf(raw ?: MacAddressPolicy.DEVICE.name) }.getOrDefault(MacAddressPolicy.DEVICE)
        }
        set(value) = prefs.edit().putString("default_mac_policy", value.name).apply()

    var isWatchdogEnabled: Boolean
        get() = prefs.getBoolean("watchdog_enabled", false)
        set(value) = prefs.edit().putBoolean("watchdog_enabled", value).apply()

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
