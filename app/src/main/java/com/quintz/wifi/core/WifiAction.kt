package com.quintz.wifi.core

import com.quintz.wifi.core.profile.RecoveryFailure
import com.quintz.wifi.core.profile.TransitionResult
import com.quintz.wifi.model.WifiStatus

/** Identity captured when the user opens a flow, rather than when a coroutine resumes. */
data class WifiActionContext(val connected: Boolean, val ssid: String, val security: String, val networkId: Int?) {
    fun matches(status: WifiStatus): Boolean = connected == status.isConnected &&
        (!connected || WifiParser.normalizeSsid(ssid).isNotEmpty() && security.isNotEmpty() && networkId != null &&
            status.ssid == ssid && status.securityType == security && status.networkId == networkId)

    /** An unknown identity during our own reconnect is not evidence of a different network. */
    internal fun conflictsAfterApply(status: WifiStatus, targetSsid: String): Boolean {
        val observedSsid = WifiParser.normalizeSsid(status.ssid)
        return status.isConnected && observedSsid.isNotEmpty() && observedSsid != ssid && observedSsid != targetSsid
    }

    companion object {
        fun capture(status: WifiStatus) = WifiActionContext(status.isConnected, status.ssid, status.securityType, status.networkId)
    }
}

/** Every caller receives its own outcome; later operations cannot replace its failure. */
data class WifiActionResult(val transition: TransitionResult, val recovery: RecoveryFailure? = null, val detail: String? = null) {
    val verified: Boolean get() = transition == TransitionResult.Verified
    val message: String get() = detail ?: when (transition) {
        TransitionResult.Verified -> "Wi-Fi change verified."
        TransitionResult.Busy -> "Another Wi-Fi change is in progress. Try again when it finishes."
        TransitionResult.Unsupported -> "Android could not expose and verify the complete saved profile. No profile was deleted."
        TransitionResult.RecoveryPending -> (recovery ?: RecoveryFailure.RESTORE_FAILED).message
        TransitionResult.StorageFailed -> "Connection settings could not be saved securely. The original profile is being restored."
        TransitionResult.NetworkChanged -> "The active network changed. Review the network and start this action again."
        TransitionResult.AccessUnavailable -> "Start Shizuku and grant Quintz permission, then retry."
        TransitionResult.NoCandidate -> "No fresh, trusted, compatible 5/6 GHz radio is available. Choose a radio in the app first."
        else -> "The requested connection was not verified. Check the network and password, then retry."
    }
}

fun interface MonotonicClock { fun nowMillis(): Long }
object AndroidMonotonicClock : MonotonicClock {
    override fun nowMillis(): Long = android.os.SystemClock.elapsedRealtime()
}
