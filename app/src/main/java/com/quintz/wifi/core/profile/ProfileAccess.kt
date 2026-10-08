package com.quintz.wifi.core.profile

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import com.quintz.wifi.core.DiagnosticLogger
import com.quintz.wifi.shizuku.IWifiProfileService
import com.quintz.wifi.shizuku.ShizukuManager
import com.quintz.wifi.shizuku.WifiProfileService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

interface ProfileGateway {
    suspend fun callChecked(context: Context, operation: String, request: Bundle): Bundle
    suspend fun call(context: Context, operation: String, request: Bundle): Bundle? = try {
        callChecked(context, operation, request)
    } catch (failure: ProfileRecoveryException) {
        DiagnosticLogger.log("PROFILE_ACCESS", "operation=$operation rejected=${failure.reason.name}")
        null
    }
}

/** One serialized service generation for the activity, watchdog and tile. */
object ProfileAccess : ProfileGateway {
    private val mutex = Mutex()
    private val runner = RetiringCallRunner()
    private val remover = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(1), { task -> Thread(task, "quintz-profile-stop").apply { isDaemon = true } })
    private class Binding(context: Context) {
        val args = Shizuku.UserServiceArgs(ComponentName(context, WifiProfileService::class.java))
            .processNameSuffix("profiles").daemon(false).version(7)
        val service = MutableStateFlow<IWifiProfileService?>(null)
        val death = CompletableDeferred<Unit>()
        var binder: IBinder? = null
        var stopping = false
        var removal: CompletableDeferred<Boolean>? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, connected: IBinder) {
                if (binding !== this@Binding || stopping) return
                binder = connected
                try {
                    connected.linkToDeath({ death.complete(Unit); service.value = null }, 0)
                    service.value = IWifiProfileService.Stub.asInterface(connected)
                } catch (_: Exception) { death.complete(Unit) }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                death.complete(Unit); service.value = null
            }
        }
    }
    @Volatile private var binding: Binding? = null

    private suspend fun terminate(old: Binding): Boolean {
        withContext(Dispatchers.Main.immediate) {
            old.stopping = true
            old.service.value = null
            if (binding === old) binding = null
            if (old.removal == null) {
                val removal = CompletableDeferred<Boolean>()
                old.removal = removal
                try {
                    remover.execute {
                        removal.complete(runCatching {
                            Shizuku.unbindUserService(old.args, old.connection, true)
                        }.isSuccess)
                    }
                } catch (_: Exception) { removal.complete(false) }
            }
        }
        if (old.removal!!.await() != true) return old.binder?.isBinderAlive == false
        val binder = old.binder ?: return true
        if (!binder.isBinderAlive) return true
        return withTimeoutOrNull(2_000L) { old.death.await(); true } == true && !binder.isBinderAlive
    }

    override suspend fun callChecked(context: Context, operation: String, request: Bundle): Bundle = mutex.withLock {
        if (!ShizukuManager.isReady()) throw ProfileRecoveryException(RecoveryFailure.ACCESS_UNAVAILABLE)
        var selected: Binding? = null
        try {
            // Finish retirement before binding a new process or issuing another privileged call.
            runner.awaitReady(4_000L)
            val service = withTimeout(4_000L) {
                selected = withContext(Dispatchers.Main.immediate) {
                    val old = binding
                    if (old == null || old.death.isCompleted) {
                        Binding(context.applicationContext).also { next ->
                            binding = next
                            try { Shizuku.bindUserService(next.args, next.connection) }
                            catch (failure: Exception) { binding = null; throw failure }
                        }
                    } else old
                }
                selected!!.service.filterNotNull().first()
            }
            val current = selected!!
            runner.run(4_000L, terminate = { terminate(current) }) { service.call(operation, request) }.also { result ->
                if (!result.getBoolean("ok")) throw ProfileRecoveryException(
                    runCatching { RecoveryFailure.valueOf(result.getString("reason").orEmpty()) }
                        .getOrDefault(RecoveryFailure.ACCESS_UNAVAILABLE))
            }
        } catch (_: TimeoutCancellationException) {
            selected?.let { withContext(NonCancellable) { withTimeoutOrNull(2_000L) { terminate(it) } } }
            throw ProfileRecoveryException(RecoveryFailure.TIMEOUT)
        } catch (_: CallRetirementPending) { throw ProfileRecoveryException(RecoveryFailure.TIMEOUT) }
        catch (failure: CancellationException) { throw failure }
        catch (failure: ProfileRecoveryException) { throw failure }
        catch (_: Exception) {
            selected?.let { withContext(NonCancellable) { withTimeoutOrNull(2_000L) { terminate(it) } } }
            throw ProfileRecoveryException(RecoveryFailure.ACCESS_UNAVAILABLE)
        }
    }

    fun identity(ssid: String, security: String, id: Int? = null) = Bundle().apply {
        putString("ssid", "\"$ssid\""); putString("security", security); putInt("id", id ?: -1)
    }
}
