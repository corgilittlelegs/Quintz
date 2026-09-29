package com.quintz.wifi.core

import kotlinx.coroutines.sync.Mutex

/** Shared by the activity, Quick Settings tile, and watchdog in this app process. */
internal object WifiOperationCoordinator {
    private val profileChange = Mutex()

    fun tryBegin(): Boolean = profileChange.tryLock()

    fun end() = profileChange.unlock()
}
