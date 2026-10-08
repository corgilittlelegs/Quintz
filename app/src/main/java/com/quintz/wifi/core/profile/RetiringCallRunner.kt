package com.quintz.wifi.core.profile

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class CallRetirementPending : Exception()

/** A timeout never permits another privileged call to overlap an unknown first outcome. */
internal class RetiringCallRunner {
    private val mutex = Mutex()
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(1), { task -> Thread(task, "quintz-profile").apply { isDaemon = true } })
    private data class Retired(val finished: CompletableDeferred<Unit>, val terminate: suspend () -> Boolean,
        var terminated: Boolean = false)
    private var retired: Retired? = null

    private suspend fun clearRetired(timeoutMillis: Long) {
        retired?.let { old ->
            if (!old.terminated) old.terminated = withTimeoutOrNull(timeoutMillis) { old.terminate() } == true
            if (!old.terminated || withTimeoutOrNull(timeoutMillis) { old.finished.await(); true } != true)
                throw CallRetirementPending()
            retired = null
        }
    }
    suspend fun awaitReady(timeoutMillis: Long) = mutex.withLock { clearRetired(timeoutMillis) }

    suspend fun <T> run(timeoutMillis: Long, terminate: suspend () -> Boolean, call: () -> T): T = mutex.withLock {
        clearRetired(timeoutMillis)
        val finished = CompletableDeferred<Unit>()
        try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    try {
                        worker.execute {
                            try {
                                if (continuation.isActive) {
                                    val result = call()
                                    if (continuation.isActive) continuation.resume(result)
                                }
                            } catch (failure: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(failure)
                            } finally { finished.complete(Unit) }
                        }
                    } catch (failure: Exception) {
                        finished.complete(Unit)
                        continuation.resumeWithException(failure)
                    }
                }
            }
        } catch (failure: CancellationException) {
            val old = Retired(finished, terminate)
            retired = old
            withContext(NonCancellable) {
                old.terminated = withTimeoutOrNull(2_000L) { terminate() } == true
            }
            throw failure
        }
    }
}
