package com.bandlock.wifi.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class BandLockPreferences(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "bandlock_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback to standard private prefs if Keystore is temporarily unavailable
        context.getSharedPreferences("bandlock_prefs", Context.MODE_PRIVATE)
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

    var isWatchdogEnabled: Boolean
        get() = prefs.getBoolean("watchdog_enabled", false)
        set(value) = prefs.edit().putBoolean("watchdog_enabled", value).apply()

    var lastTargetBand: String
        get() = prefs.getString("last_target_band", "5GHz") ?: "5GHz"
        set(value) = prefs.edit().putString("last_target_band", value).apply()

    var fallbackThresholdRssi: Int
        get() = prefs.getInt("fallback_threshold", -82)
        set(value) = prefs.edit().putInt("fallback_threshold", value).apply()

    var recoveryThresholdRssi: Int
        get() = prefs.getInt("recovery_threshold", -70)
        set(value) = prefs.edit().putInt("recovery_threshold", value).apply()
}
