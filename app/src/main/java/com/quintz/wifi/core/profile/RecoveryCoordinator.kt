package com.quintz.wifi.core.profile

import com.quintz.wifi.core.WifiActionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RecoveryPhase { READY, WAITING_FOR_ACCESS, RECOVERING, BLOCKED }
data class RecoveryState(val phase: RecoveryPhase = RecoveryPhase.READY, val message: String? = null) {
    val ready: Boolean get() = phase == RecoveryPhase.READY
}

/** Fault-injectable shared recovery barrier; blocked recovery requires a deliberate retry. */
internal class RecoveryCoordinator(
    private val pending: () -> Boolean,
    private val hasAccess: () -> Boolean,
    private val recover: suspend () -> WifiActionResult
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(RecoveryState())
    val state = mutableState.asStateFlow()

    suspend fun ensureReady(retry: Boolean = false): Boolean = mutex.withLock {
        if (!pending()) { mutableState.value = RecoveryState(); return@withLock true }
        if (!hasAccess()) {
            mutableState.value = RecoveryState(RecoveryPhase.WAITING_FOR_ACCESS, RecoveryFailure.ACCESS_UNAVAILABLE.message)
            return@withLock false
        }
        if (!retry && mutableState.value.phase == RecoveryPhase.BLOCKED) return@withLock false
        mutableState.value = RecoveryState(RecoveryPhase.RECOVERING, "Restoring and verifying the interrupted Wi-Fi change…")
        try {
            val result = recover()
            if (result.verified && !pending()) {
                mutableState.value = RecoveryState()
                true
            } else {
                mutableState.value = RecoveryState(RecoveryPhase.BLOCKED, result.message)
                false
            }
        } catch (failure: CancellationException) {
            mutableState.value = RecoveryState(RecoveryPhase.WAITING_FOR_ACCESS, "Recovery was interrupted. Its backup was kept.")
            throw failure
        } catch (_: Exception) {
            mutableState.value = RecoveryState(RecoveryPhase.BLOCKED, RecoveryFailure.RESTORE_FAILED.message)
            false
        }
    }
}
