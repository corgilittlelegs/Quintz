package com.quintz.wifi.core.profile

import android.content.*
import android.os.*
import com.quintz.wifi.shizuku.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import rikka.shizuku.Shizuku

/** One permission-gated privileged process, shared by activity, watchdog and tile. */
object ProfileAccess {
    private val permits = kotlinx.coroutines.sync.Semaphore(1)
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "quintz-profile").apply { isDaemon = true } }
    private suspend fun invoke(service: IWifiProfileService, operation: String, request: Bundle): Bundle = withTimeout(4_000L) {
        permits.acquire()
        suspendCancellableCoroutine { continuation ->
            worker.execute {
                try {
                    val result = service.call(operation, request)
                    if (continuation.isActive) continuation.resumeWith(Result.success(result))
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWith(Result.failure(e)) }
                finally { permits.release() }
            }
        }
    }
    private val remote = MutableStateFlow<IWifiProfileService?>(null)
    private var binding = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { remote.value = IWifiProfileService.Stub.asInterface(binder) }
        override fun onServiceDisconnected(name: ComponentName) { remote.value = null; binding = false }
    }
    suspend fun callChecked(context: Context, operation: String, request: Bundle): Bundle {
        if (!ShizukuManager.isReady()) { remote.value = null; binding = false; throw ProfileRecoveryException(RecoveryFailure.ACCESS_UNAVAILABLE) }
        return try {
            val service = withTimeout(4_000L) {
                withContext(Dispatchers.Main.immediate) {
                    if (!binding) {
                        val args = Shizuku.UserServiceArgs(ComponentName(context, WifiProfileService::class.java))
                            .processNameSuffix("profiles").daemon(false).version(6)
                        binding = true
                        try { Shizuku.bindUserService(args, connection) } catch (e: Exception) { binding = false; throw e }
                    }
                }
                remote.filterNotNull().first()
            }
            invoke(service, operation, request).also { result ->
                if (!result.getBoolean("ok")) throw ProfileRecoveryException(
                    runCatching { RecoveryFailure.valueOf(result.getString("reason").orEmpty()) }.getOrDefault(RecoveryFailure.ACCESS_UNAVAILABLE))
            }
        } catch (_: TimeoutCancellationException) {
            if (remote.value == null) binding = false
            throw ProfileRecoveryException(RecoveryFailure.TIMEOUT)
        }
        catch (e: CancellationException) { throw e }
        catch (e: ProfileRecoveryException) { throw e }
        catch (_: Exception) { remote.value = null; binding = false; throw ProfileRecoveryException(RecoveryFailure.ACCESS_UNAVAILABLE) }
    }
    suspend fun call(context: Context, operation: String, request: Bundle): Bundle? = try {
        callChecked(context, operation, request)
    } catch (failure: ProfileRecoveryException) {
        com.quintz.wifi.core.DiagnosticLogger.log("PROFILE_ACCESS", "operation=$operation rejected=${failure.reason.name}")
        null
    }
    fun identity(ssid: String, security: String, id: Int? = null) = Bundle().apply {
        putString("ssid", "\"$ssid\""); putString("security", security); putInt("id", id ?: -1)
    }
}
