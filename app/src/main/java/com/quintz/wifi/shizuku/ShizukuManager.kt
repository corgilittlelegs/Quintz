package com.quintz.wifi.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.quintz.wifi.core.DiagnosticLogger
import com.quintz.wifi.model.ShizukuState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean get() = exitCode == 0
}

object ShizukuManager {

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val REQUEST_CODE_SHIZUKU_PERMISSION = 7001

    private val shellRunner = BoundedShellRunner()

    private val _state = MutableStateFlow(ShizukuState())
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private var newProcessMethod: Method? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        updateState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        updateState()
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE_SHIZUKU_PERMISSION) {
                updateState()
            }
        }

    fun initialize(context: Context) {
        val tag = "Shizuku"
        try {
            newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
            android.util.Log.d(tag, "Shizuku newProcess method located successfully")
        } catch (e: Exception) {
            android.util.Log.e(tag, "Failed to get Shizuku newProcess method", e)
            newProcessMethod = null
        }

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        updateState(context)
    }

    fun cleanUp() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
    }

    fun updateState(context: Context? = null) {
        val tag = "Shizuku"
        val isRunning = try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            android.util.Log.e(tag, "pingBinder error", e)
            false
        }
        val isInstalled = isRunning || (context?.let { isPackageInstalled(it, SHIZUKU_PACKAGE) } ?: _state.value.isInstalled)

        val isGranted = isRunning && try {
            if (Shizuku.getVersion() < 11) false
            else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            android.util.Log.e(tag, "checkSelfPermission error", e)
            false
        }

        val version = if (isRunning) {
            try { Shizuku.getVersion() } catch (_: Throwable) { 0 }
        } else 0

        val newState = ShizukuState(
            isInstalled = isInstalled,
            isRunning = isRunning,
            isPermissionGranted = isGranted,
            version = version
        )
        if (_state.value != newState) {
            DiagnosticLogger.log("SHIZUKU", "State: running=$isRunning, granted=$isGranted, v$version")
        }
        android.util.Log.d(tag, "Updated Shizuku state: $newState")
        _state.value = newState
    }

    fun openShizukuApp(context: Context): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("Shizuku", "Failed to launch Shizuku app", e)
            false
        }
    }

    fun openPlayStore(context: Context) {
        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(marketIntent)
        } catch (_: Exception) {
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
            } catch (e: Exception) {
                android.util.Log.e("Shizuku", "Failed to open Play Store link", e)
            }
        }
    }

    fun launchOrInstall(context: Context) {
        if (!openShizukuApp(context)) {
            openPlayStore(context)
        }
    }

    fun requestPermission() {
        android.util.Log.d("Shizuku", "Requesting Shizuku permission...")
        if (!Shizuku.pingBinder()) return
        if (Shizuku.getVersion() < 11) return
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(REQUEST_CODE_SHIZUKU_PERMISSION)
        }
    }

    fun isReady(): Boolean = _state.value.isRunning && _state.value.isPermissionGranted

    fun escapeShellArg(arg: String): String {
        return "'" + arg.replace("'", "'\\''") + "'"
    }

    suspend fun exec(command: String, timeoutSeconds: Long = 12, correlationId: String? = null, onStarted: (() -> Unit)? = null): ShellResult {
        if (!isReady()) return ShellResult(-1, "", "Shizuku access unavailable")
        val method = newProcessMethod ?: return ShellResult(-1, "", "Process API unavailable")
        val start = android.os.SystemClock.elapsedRealtime()
        return try {
            val result = shellRunner.run(timeoutSeconds * 1000L) {
                (method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process).also { onStarted?.invoke() }
            }
            DiagnosticLogger.logCommand(command, result.exitCode, android.os.SystemClock.elapsedRealtime() - start, correlationId)
            result
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            ShellResult(-1, "", "Command deadline expired")
        } catch (_: Exception) { ShellResult(-1, "", "Command execution failed") }
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
