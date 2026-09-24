package com.quintz.wifi.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.quintz.wifi.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque

object DiagnosticLogger {

    private const val TAG = "QuintzDiag"
    private const val MAX_ENTRIES = 500

    private val entries = ConcurrentLinkedDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val timeFormatLock = Any()

    fun newCorrelationId(): String = UUID.randomUUID().toString().take(8)

    fun initialize(context: Context) {
        if (!BuildConfig.DEBUG) return
        log(
            "INIT",
            "App started on ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})"
        )

        // Capture uncaught crashes so they can be retrieved
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                log("CRASH", "FATAL in thread '${thread.name}': ${throwable.stackTraceToString()}")
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun log(tag: String, message: String) {
        if (!BuildConfig.DEBUG) return

        val timestamp = synchronized(timeFormatLock) { timeFormat.format(Date()) }
        val sanitizedMessage = sanitize(message)
        val entry = "[$timestamp] [$tag] $sanitizedMessage"

        entries.addLast(entry)
        while (entries.size > MAX_ENTRIES) {
            entries.pollFirst()
        }

        Log.d(TAG, entry)
    }

    fun logCommand(
        command: String,
        exitCode: Int,
        durationMs: Long,
        stdout: String,
        stderr: String,
        correlationId: String? = null
    ) {
        if (!BuildConfig.DEBUG) return
        val sanitizedCmd = sanitize(command)
        val statusSymbol = if (exitCode == 0) "✓" else "✗"
        val snippet = if (stderr.isNotEmpty()) {
            " | stderr: ${sanitize(stderr).take(120)}"
        } else if (stdout.isNotEmpty()) {
            " | out: ${sanitize(stdout.lines().firstOrNull().orEmpty()).take(80)}"
        } else ""
        val correlation = correlationId?.let { " id=$it" }.orEmpty()
        log("CMD", "$statusSymbol [$exitCode] (${durationMs}ms)$correlation $sanitizedCmd$snippet")
    }

    fun sanitize(raw: String): String {
        // Redact Wi-Fi passwords from cmd wifi connect-network / add-network
        // e.g. cmd wifi connect-network 'MySSID' 'wpa2' 'SecretPass'
        // or cmd wifi add-network 'MySSID' 'wpa2' 'SecretPass'
        val connectRegex = Regex("(cmd wifi (?:connect-network|add-network)\\s+\\S+\\s+\\S+\\s+)(['\"]?)(.+?)\\2(?=\\s|\$)", RegexOption.IGNORE_CASE)
        var sanitized = connectRegex.replace(raw) { matchResult ->
            "${matchResult.groupValues[1]}***"
        }
        // Also redact raw password parameters if formatted as password='...' or pwd=...
        sanitized = sanitized.replace(Regex("(password|passphrase|pwd)=([^\\s&]+)", RegexOption.IGNORE_CASE), "$1=***")
        return sanitized
    }

    fun getRecentLogs(): List<String> {
        if (!BuildConfig.DEBUG) return emptyList()
        return entries.toList()
    }

    fun clearLogs() {
        if (!BuildConfig.DEBUG) return
        entries.clear()
        log("DIAG", "Logs cleared by user")
    }

    fun buildDiagnosticReport(context: Context, statusSummary: String = ""): String {
        if (!BuildConfig.DEBUG) return "Diagnostics disabled in release build."

        val sb = StringBuilder()
        sb.appendLine("=== Quintz Diagnostic Report ===")
        sb.appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})")
        sb.appendLine("Android OS: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}, Build ${Build.DISPLAY})")
        sb.appendLine("Package: ${context.packageName}")
        sb.appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("Build Type: DEBUG")

        if (statusSummary.isNotEmpty()) {
            sb.appendLine("\n--- Current App State ---")
            sb.appendLine(statusSummary)
        }

        sb.appendLine("\n--- Event Log (Last ${entries.size} entries) ---")
        entries.forEach { entry ->
            sb.appendLine(entry)
        }
        sb.appendLine("\n=== End of Report ===")
        return sb.toString()
    }

    fun copyToClipboard(context: Context, report: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Quintz Diagnostics", report)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Diagnostics copied to clipboard (${entries.size} events)", Toast.LENGTH_SHORT).show()
    }

    fun shareReport(context: Context, report: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Quintz Diagnostic Report - ${Build.MODEL}")
            putExtra(Intent.EXTRA_TEXT, report)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(Intent.createChooser(intent, "Share Quintz Diagnostics").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }
}
