package com.quintz.wifi.shizuku

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.io.InputStream
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/** At most two processes and six workers. Queue time belongs to the caller's deadline. */
class BoundedShellRunner(private val outputLimit: Int = 262_144) {
    private val permits = Semaphore(2)
    private val executor = Executors.newFixedThreadPool(6) { r -> Thread(r, "quintz-shell").apply { isDaemon = true } }
    suspend fun run(timeoutMillis: Long, create: () -> Process): ShellResult = withTimeout(timeoutMillis) {
        permits.acquire()
        suspendCancellableCoroutine { continuation ->
            val process = AtomicReference<Process?>()
            val readers = CopyOnWriteArrayList<Future<*>>()
            fun stop() {
                process.get()?.let { p ->
                    runCatching { p.destroy() }
                    runCatching { p.inputStream.close() }; runCatching { p.errorStream.close() }; runCatching { p.outputStream.close() }
                }
                readers.forEach { it.cancel(true) }
            }
            continuation.invokeOnCancellation { stop() }
            try {
                executor.execute {
                    try {
                        if (!continuation.isActive) return@execute
                        val p = create(); process.set(p)
                        if (!continuation.isActive) { stop(); return@execute }
                        val stdout = executor.submit<String> { read(p.inputStream) { stop() } }.also { readers += it }
                        val stderr = executor.submit<String> { read(p.errorStream) { stop() } }.also { readers += it }
                        val code = p.waitFor()
                        val result = ShellResult(code, stdout.get(), stderr.get())
                        if (continuation.isActive) continuation.resume(result)
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resume(ShellResult(-1, "", "Command output failed or exceeded its limit"))
                    } finally { stop(); permits.release() }
                }
            } catch (_: RejectedExecutionException) {
                permits.release(); continuation.resume(ShellResult(-1, "", "Shell capacity unavailable"))
            }
        }
    }
    private fun read(stream: InputStream, overflow: () -> Unit): String = stream.bufferedReader().use { reader ->
        val output = StringBuilder(); val buffer = CharArray(4096)
        while (true) {
            val count = reader.read(buffer); if (count < 0) break
            if (output.length + count > outputLimit) { overflow(); error("Output limit exceeded") }
            output.append(buffer, 0, count)
        }
        output.toString().trimEnd('\n', '\r')
    }
}
