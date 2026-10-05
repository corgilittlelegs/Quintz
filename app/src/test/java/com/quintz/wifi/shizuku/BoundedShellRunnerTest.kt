package com.quintz.wifi.shizuku

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BoundedShellRunnerTest {
    @Test fun excessiveOutputFailsWithoutReturningPartialEvidence() = runBlocking {
        val runner = BoundedShellRunner(32)
        val result = runner.run(2000) { ProcessBuilder("sh", "-c", "printf '%0100d' 0").start() }
        assertFalse(result.isSuccess); assertEquals("", result.stdout)
    }
    @Test fun cancellingCallerDestroysProcessAndReleasesCapacity() = runBlocking {
        val runner = BoundedShellRunner(); var process: Process? = null
        val job = launch { runner.run(10000) { ProcessBuilder("sh", "-c", "exec sleep 10").start().also { process = it } } }
        while (process == null) delay(10)
        job.cancelAndJoin()
        withTimeout(2000) { while (process!!.isAlive) delay(10) }
        assertTrue(runner.run(2000) { ProcessBuilder("sh", "-c", "printf ready").start() }.isSuccess)
    }
    @Test fun processCountAndQueueTimeAreBounded() = runBlocking {
        val runner = BoundedShellRunner(); val launches = AtomicInteger()
        val jobs = (1..2).map { async { runner.run(2000) { launches.incrementAndGet(); ProcessBuilder("sh", "-c", "exec sleep 0.4").start() } } }
        while (launches.get() < 2) delay(10)
        assertTrue(runCatching { runner.run(50) { launches.incrementAndGet(); ProcessBuilder("sh", "-c", "true").start() } }.exceptionOrNull() is TimeoutCancellationException)
        assertEquals(2, launches.get()); jobs.awaitAll(); Unit
    }
}
