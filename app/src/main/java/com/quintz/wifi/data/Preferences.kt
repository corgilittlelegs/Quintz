package com.quintz.wifi.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.quintz.wifi.model.MacAddressPolicy

class Preferences(context: Context) {

    private val prefs: SharedPreferences = try {
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
        // Fallback to standard private prefs if Keystore is temporarily unavailable
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    }

    fun savePassword(ssid: String, pass: String) {
        prefs.edit().putString("pwd_$ssid", pass).apply()
    }

    fun getPassword(ssid: String): String? {
        return prefs.getString("pwd_$ssid", null)
    }

    fun removePassword(ssid: String) {
        prefs.edit().remove("pwd_$ssid").apply()
    }

    fun getMacPolicy(ssid: String): MacAddressPolicy? {
        val raw = prefs.getString("mac_policy_$ssid", null) ?: return null
        return runCatching { MacAddressPolicy.valueOf(raw) }.getOrNull()
    }

    fun setMacPolicy(ssid: String, policy: MacAddressPolicy) {
        prefs.edit().putString("mac_policy_$ssid", policy.name).apply()
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

    var fallbackThresholdRssi: Int
        get() {
            val v = prefs.getInt("fallback_threshold", -75)
            return if (v < -75) -75 else v
        }
        set(value) = prefs.edit().putInt("fallback_threshold", value).apply()

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
