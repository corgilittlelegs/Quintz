package com.quintz.wifi.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** Shared by the activity, Quick Settings tile, and watchdog in this app process. */
internal object WifiOperationCoordinator {
    private val profileChange = Mutex()
    private val stateGuard = Any()
    private val _isOperating = MutableStateFlow(false)
    val isOperating: StateFlow<Boolean> = _isOperating.asStateFlow()

    fun tryBegin(): Boolean = synchronized(stateGuard) {
        if (!profileChange.tryLock()) return@synchronized false
        _isOperating.value = true
        true
    }

    fun end() = synchronized(stateGuard) {
        profileChange.unlock()
        _isOperating.value = false
    }
}
