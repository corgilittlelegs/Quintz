package com.quintz.wifi.model

const val HEALTHY_24G_THRESHOLD_RSSI = -75

enum class BandType(val displayName: String, val frequencyRange: String) {
    BAND_2_4_GHZ("2.4 GHz", "2412–2484 MHz"),
    BAND_5_GHZ("5 GHz", "5160–5885 MHz"),
    BAND_6_GHZ("6 GHz", "5925–7125 MHz"),
    UNKNOWN("Unknown", "N/A");

    companion object {
        fun fromFrequency(freq: Int): BandType = when (freq) {
            in 2400..2500 -> BAND_2_4_GHZ
            in 5000..5900 -> BAND_5_GHZ
            in 5925..7200 -> BAND_6_GHZ
            else -> UNKNOWN
        }
    }
}

/** The Wi-Fi MAC identity scheme to use for a network profile. */
enum class MacAddressPolicy(val shellFlagValue: String, val displayName: String) {
    DEVICE("none", "Device MAC"),
    RANDOMIZED("persistent", "Randomized MAC")
}

data class WifiStatus(
    val isConnected: Boolean = false,
    val ssid: String = "",
    val bssid: String = "",
    val frequency: Int = 0,
    val band: BandType = BandType.UNKNOWN,
    val rssi: Int = 0,
    val linkSpeedMbps: Int = 0,
    val standard: String = "",
    val ipAddress: String = "",
    val securityType: String = "",
    val isLockedToBssid: Boolean = false,
    val lockedBssid: String? = null,
    val isPreferred5GHz: Boolean = false,
    val isPreferred5GHzFallback: Boolean = false
) {
    val isSteeredOrLocked: Boolean
        get() = isLockedToBssid || isPreferred5GHz || isPreferred5GHzFallback
}

data class AccessPointRadio(
    val bssid: String,
    val ssid: String = "",
    val frequency: Int,
    val band: BandType,
    val channel: Int,
    val rssi: Int,
    val flags: String,
    val isCurrent: Boolean = false,
    val ageSeconds: Long = 0L
) {
    companion object {
        fun frequencyToChannel(freq: Int): Int = when (freq) {
            2484 -> 14
            in 2412..2472 -> (freq - 2407) / 5
            in 5160..5885 -> (freq - 5000) / 5
            in 5955..7115 -> (freq - 5950) / 5
            else -> 0
        }
    }
}

data class ShizukuState(
    val isInstalled: Boolean = false,
    val isRunning: Boolean = false,
    val isPermissionGranted: Boolean = false,
    val version: Int = 0
)
