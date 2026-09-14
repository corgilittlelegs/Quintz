package com.quintz.wifi.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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

    private val shellExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable).apply {
            isDaemon = true
            name = "shizuku-worker"
        }
    }

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

    fun exec(command: String, timeoutSeconds: Long = 12): ShellResult {
        val tag = "Shizuku"
        if (!isReady()) {
            android.util.Log.w(tag, "exec called but Shizuku not ready: ${_state.value}")
            return ShellResult(-1, "", "Shizuku service not available or permission denied")
        }
        val method = newProcessMethod ?: return ShellResult(-1, "", "newProcess method unavailable")

        return try {
            android.util.Log.d(tag, "executing command: $command")
            val process = method.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as Process

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            // Read stdout and stderr concurrently via shared thread pool to prevent pipe buffer deadlock
            val stdoutFuture = shellExecutor.submit {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line -> stdoutBuilder.appendLine(line) }
                    }
                } catch (_: Exception) {}
            }

            val stderrFuture = shellExecutor.submit {
                try {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { line -> stderrBuilder.appendLine(line) }
                    }
                } catch (_: Exception) {}
            }

            val waitFuture = shellExecutor.submit<Int> {
                try {
                    process.waitFor()
                } catch (_: Exception) {
                    -1
                }
            }

            val code = try {
                waitFuture.get(timeoutSeconds, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                android.util.Log.w(tag, "Command timed out after ${timeoutSeconds}s: $command")
                try { process.destroy() } catch (_: Exception) {}
                waitFuture.cancel(true)
                stdoutFuture.cancel(true)
                stderrFuture.cancel(true)
                return ShellResult(-1, "", "Command timed out after ${timeoutSeconds}s")
            }

            try { stdoutFuture.get(1, TimeUnit.SECONDS) } catch (_: Exception) {}
            try { stderrFuture.get(1, TimeUnit.SECONDS) } catch (_: Exception) {}

            val stdout = stdoutBuilder.toString().trim()
            val stderr = stderrBuilder.toString().trim()
            android.util.Log.d(tag, "command completed ($code), stdout length: ${stdout.length}, stderr: $stderr")
            ShellResult(code, stdout, stderr)
        } catch (e: Exception) {
            android.util.Log.e(tag, "Command execution error", e)
            ShellResult(-1, "", e.message ?: "Execution error")
        }
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
