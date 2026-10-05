package com.quintz.wifi.shizuku

import android.net.wifi.WifiConfiguration
import android.os.Parcel
import android.os.Parcelable
import java.security.MessageDigest

/** Runs in the privileged process: ordinary app reflection hides some framework fields. */
@Suppress("DEPRECATION")
object WifiProfileFingerprint {
    const val CURRENT_VERSION = 2
    private val legacyBookkeeping = setOf("networkId", "status", "creatorUid", "creatorName", "lastUpdateUid", "lastUpdateName", "lastConnectUid", "numAssociation", "numRebootsSinceLastUse", "lastConnected", "lastDisconnected", "lastUpdated", "deletionPriority", "numScorerOverride", "numScorerOverrideAndSwitchedNetwork", "numNoInternetAccessReports", "hasEverConnected", "isMostRecentlyConnected")
    private val connectionBookkeeping = setOf("isCurrentlyConnected", "mIsUserSelected", "mHasPreSharedKeyChanged", "randomizedMacExpirationTimeMs", "randomizedMacLastModifiedTimeMs")
    private val selectionBookkeeping = setOf("mStatus", "mNetworkSelectionDisableReason", "mDisableTime", "mTemporarilyDisabledTimestamp", "mTemporarilyDisabledEndTime", "mNetworkSeclectionDisableCounter", "mCandidate", "mCandidateScore", "mCandidateSecurityParams", "mLastUsedSecurityParams", "mSeenInLastQualifiedNetworkSelection", "mHasEverConnected", "mHasNeverDetectedCaptivePortal", "mHasEverValidatedInternetAccess", "mNetworkSelectionBSSID")
    private val dppKeys = listOf("mDppPrivateEcKey", "mDppConnector", "mDppCSignKey", "mDppNetAccessKey")

    fun encode(config: WifiConfiguration): ByteArray = Parcel.obtain().let { parcel ->
        try { config.writeToParcel(parcel, 0); parcel.marshall() } finally { parcel.recycle() }
    }
    fun decode(bytes: ByteArray): WifiConfiguration = Parcel.obtain().let { parcel ->
        try {
            parcel.unmarshall(bytes, 0, bytes.size); parcel.setDataPosition(0)
            @Suppress("UNCHECKED_CAST")
            val creator = WifiConfiguration::class.java.getField("CREATOR").get(null) as Parcelable.Creator<WifiConfiguration>
            creator.createFromParcel(parcel).also { check(parcel.dataAvail() == 0) }
        } finally { parcel.recycle() }
    }
    private fun resetFields(target: Any, defaults: Any, names: Set<String>) {
        names.forEach { name ->
            // An absent field on an older framework contributes no parcel value. Access failures
            // on a present field propagate instead of silently weakening the comparison.
            val field = try { target.javaClass.getDeclaredField(name) } catch (_: NoSuchFieldException) { null }
            field?.let { it.isAccessible = true; it.set(target, it.get(defaults)) }
        }
    }
    private fun dppFlagHasNoSecurityMaterial(config: WifiConfiguration): Boolean = runCatching {
        val type = WifiConfiguration::class.java.getField("SECURITY_TYPE_DPP").getInt(null)
        val usesDpp = WifiConfiguration::class.java.getMethod("isSecurityType", Int::class.javaPrimitiveType).invoke(config, type) as Boolean
        usesDpp == false && dppKeys.all { name ->
            val field = WifiConfiguration::class.java.getDeclaredField(name).apply { isAccessible = true }
            (field.get(config) as ByteArray?)?.isEmpty() != false
        }
    }.getOrDefault(false)

    /** Only an app-recorded selection of an existing randomized profile may generate a new
     * address. The ordinary fingerprint continues to protect that address everywhere else. */
    // This method runs under Shizuku's shell UID, whose framework reflection is unrestricted.
    // Failures still propagate; ordinary app code never performs this comparison.
    @android.annotation.SuppressLint("SoonBlockedPrivateApi")
    fun matchesSelectedProfile(expected: WifiConfiguration, actual: WifiConfiguration,
                               selectionRecorded: Boolean, originalExists: Boolean): Boolean {
        if (fingerprint(expected).contentEquals(fingerprint(actual))) return true
        if (!selectionRecorded || !originalExists) return false
        val policy = WifiConfiguration::class.java.getField("macRandomizationSetting")
        if (policy.getInt(expected) != 1 || policy.getInt(actual) != 1) return false
        val address = WifiConfiguration::class.java.getMethod("getRandomizedMacAddress").invoke(actual)?.toString()
            ?: return false
        if (!address.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) ||
            address == "02:00:00:00:00:00" || (address.substringBefore(':').toInt(16) and 3) != 2) return false
        val aligned = decode(encode(actual))
        WifiConfiguration::class.java.getDeclaredField("mRandomizedMacAddress").apply { isAccessible = true }
            .let { it.set(aligned, it.get(expected)) }
        return fingerprint(expected).contentEquals(fingerprint(aligned))
    }

    fun fingerprint(config: WifiConfiguration, version: Int = CURRENT_VERSION): ByteArray {
        require(version in 1..CURRENT_VERSION)
        val copy = decode(encode(config)); val defaults = WifiConfiguration()
        resetFields(copy, defaults, legacyBookkeeping)
        val selection = WifiConfiguration::class.java.getMethod("getNetworkSelectionStatus").invoke(copy)!!
        val defaultSelection = WifiConfiguration::class.java.getMethod("getNetworkSelectionStatus").invoke(defaults)!!
        resetFields(selection, defaultSelection, selectionBookkeeping)
        if (version >= 2) {
            resetFields(copy, defaults, connectionBookkeeping)
            // Encrypted storage representations can be refreshed independently of the actual
            // unredacted password, which remains hashed. Never normalize a redacted credential.
            if (!copy.preSharedKey.isNullOrEmpty() && copy.preSharedKey != "*") {
                resetFields(copy, defaults, setOf("mEncryptedPreSharedKey", "mEncryptedPreSharedKeyIv"))
            }
            // A non-DPP profile with no DPP keys has no configurator capability to preserve.
            // Real DPP material, security parameters and the flag with material stay protected.
            if (dppFlagHasNoSecurityMaterial(copy)) resetFields(copy, defaults, setOf("mIsDppConfigurator"))
            if (copy.BSSID.equals("any", true)) copy.BSSID = null
        }
        return MessageDigest.getInstance("SHA-256").digest(encode(copy))
    }
}
