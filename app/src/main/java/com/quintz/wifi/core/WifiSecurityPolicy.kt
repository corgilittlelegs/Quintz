package com.quintz.wifi.core

/** Security advertised by a scan result. Unknown flags are never treated as open. */
internal data class AdvertisedSecurity(
    val supportsPsk: Boolean = false,
    val supportsSae: Boolean = false,
    val isOwe: Boolean = false,
    val isOpen: Boolean = false
)

internal object WifiSecurityPolicy {
    fun fromFlags(flags: String): AdvertisedSecurity {
        val upper = flags.uppercase()
        return AdvertisedSecurity(
            supportsPsk = "PSK" in upper,
            supportsSae = "SAE" in upper,
            isOwe = "OWE" in upper,
            isOpen = "ESS" in upper && listOf("PSK", "SAE", "OWE", "WEP", "EAP", "WAPI", "DPP").none { it in upper }
        )
    }

    /** Automatic steering is limited to compatible WPA2/WPA3 Personal radios.
     * Open and OWE radios need a user-selected BSSID because SSID alone is not identity.
     */
    fun allowsAutomaticSwitch(currentSecurityType: String, currentFlags: String, candidateFlags: String): Boolean {
        val candidate = fromFlags(candidateFlags)
        return when (currentSecurityType.lowercase()) {
            "2", "wpa2", "psk" -> candidate.supportsPsk
            "4", "wpa3", "sae" -> candidate.supportsSae
            "0", "open", "6", "owe" -> false
            else -> {
                val current = fromFlags(currentFlags)
                when {
                    current.supportsSae -> candidate.supportsSae
                    current.supportsPsk -> candidate.supportsPsk
                    else -> false
                }
            }
        }
    }

    /** A saved security mode must still be advertised by the exact radio being restored. */
    fun matchesSecurityType(securityType: String?, flags: String): Boolean {
        val advertised = fromFlags(flags)
        return when (securityType) {
            "0" -> advertised.isOpen
            "2" -> advertised.supportsPsk
            "4" -> advertised.supportsSae
            "6" -> advertised.isOwe
            else -> false
        }
    }

    fun isSupported(flags: String): Boolean = fromFlags(flags).let {
        it.isOpen || it.isOwe || it.supportsPsk || it.supportsSae
    }

    fun allowsTrustedAutomaticSwitch(
        currentSecurityType: String,
        currentFlags: String,
        candidateFlags: String,
        trustedSecurityType: String?
    ): Boolean {
        val current = when (currentSecurityType.lowercase()) {
            "2", "wpa2", "psk" -> "2"
            "4", "wpa3", "sae" -> "4"
            else -> return false
        }
        return trustedSecurityType == current &&
            allowsAutomaticSwitch(currentSecurityType, currentFlags, candidateFlags) &&
            matchesSecurityType(current, candidateFlags)
    }

    /** A same-name profile cannot silently move to open or a different personal security mode. */
    fun allowsSameSsidSelection(currentSecurityType: String, targetSecurityType: String?): Boolean {
        if (targetSecurityType == null) return false
        if (currentSecurityType == targetSecurityType) return true
        return currentSecurityType in setOf("0", "6") && targetSecurityType in setOf("2", "4")
    }
}
