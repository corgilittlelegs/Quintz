package com.quintz.wifi.core

import android.os.SystemClock
import com.quintz.wifi.model.WifiStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shares concurrent status reads from the activity and watchdog controllers. */
internal object WifiStatusCoordinator {
    private const val CACHE_WINDOW_MS = 120L
    private val mutex = Mutex()
    private var cachedStatus: WifiStatus? = null
    private var completedAtElapsedMs = 0L

    suspend fun refresh(forceFresh: Boolean, block: suspend () -> WifiStatus): Pair<WifiStatus, Boolean> = mutex.withLock {
        val now = SystemClock.elapsedRealtime()
        val cached = cachedStatus
        if (!forceFresh && cached != null && now - completedAtElapsedMs in 0..CACHE_WINDOW_MS) {
            return@withLock cached to true
        }
        val status = block()
        cachedStatus = status
        completedAtElapsedMs = SystemClock.elapsedRealtime()
        status to false
    }
}
