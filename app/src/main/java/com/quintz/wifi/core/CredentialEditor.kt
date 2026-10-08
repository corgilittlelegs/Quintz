package com.quintz.wifi.core

import com.quintz.wifi.core.profile.TransitionResult
import com.quintz.wifi.model.WifiOperationKind
import com.quintz.wifi.model.WifiStatus

internal interface CredentialStore {
    val available: Boolean
    fun save(ssid: String, password: String): Boolean
    fun remove(ssid: String): Boolean
}

/** Real and fault-injected credential actions share identity checks and the operation lock. */
internal class CredentialEditor(private val status: suspend () -> WifiStatus,
    private val pendingRecovery: () -> Boolean, private val store: CredentialStore) {
    suspend fun edit(target: WifiActionContext, password: String?): WifiActionResult {
        if (!WifiOperationCoordinator.tryBegin(WifiOperationKind.CHANGE_CREDENTIALS))
            return WifiActionResult(TransitionResult.Busy)
        try {
            if (pendingRecovery()) return WifiActionResult(TransitionResult.RecoveryPending)
            val current = status()
            if (!target.matches(current)) return WifiActionResult(TransitionResult.NetworkChanged)
            if (!current.isConnected || current.securityType !in setOf("2", "4") || !current.profileInspectionKnown)
                return WifiActionResult(TransitionResult.Unsupported)
            if (current.isSteeredOrLocked) return WifiActionResult(TransitionResult.Failed,
                detail = "Unlock to auto-roam before changing saved credentials.")
            if (!store.available || password != null && password.isEmpty()) return WifiActionResult(TransitionResult.StorageFailed,
                detail = "Secure password storage is unavailable or the password is empty. Retry storage or enter the password again.")
            val saved = if (password == null) store.remove(target.ssid) else store.save(target.ssid, password)
            return WifiActionResult(if (saved) TransitionResult.Verified else TransitionResult.StorageFailed,
                detail = if (saved) {
                    if (password == null) "Password removed from Quintz. Android's saved network was kept." else "Password saved in Quintz."
                } else "The password could not be saved securely. Retry secure storage, then try again.")
        } finally { WifiOperationCoordinator.end() }
    }
}
