package com.quintz.wifi.core

/** Validates raw user input before storage or conversion to WifiConfiguration.preSharedKey. */
internal object WifiPasswordPolicy {
    fun validationError(password: String, securityType: String): String? {
        val minimum = when (securityType) {
            "2" -> 8
            "4" -> 1
            else -> return "Password management requires WPA2 or WPA3 Personal."
        }
        // Preserve the existing raw hexadecimal-key representation accepted by Android.
        if (isHexKey(password)) return null
        if (!Charsets.UTF_8.newEncoder().canEncode(password)) return "The password contains invalid text."
        // Android checks the quoted string length; the supplicant also limits encoded bytes.
        val asciiLength = password.toByteArray(Charsets.US_ASCII).size
        if (asciiLength !in minimum..63 || password.toByteArray(Charsets.UTF_8).size > 63) {
            return if (securityType == "2")
                "Use 8–63 characters (up to 63 UTF-8 bytes), or exactly 64 hexadecimal digits for WPA2."
            else "Use 1–63 characters (up to 63 UTF-8 bytes), or exactly 64 hexadecimal digits for WPA3."
        }
        return null
    }

    fun preSharedKey(password: String, securityType: String): String? =
        if (validationError(password, securityType) != null) null
        else if (isHexKey(password)) password else "\"$password\""

    private fun isHexKey(password: String) = password.length == 64 &&
        password.all { it in "0123456789abcdefABCDEF" }
}
