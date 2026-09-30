package com.quintz.wifi.core

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Private, bounded journal. Every append is closed before returning to the caller. */
internal class FlightJournal(
    private val directory: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val maxTotalBytes: Long = 40L * 1024 * 1024,
    private val maxFileBytes: Long = 4L * 1024 * 1024,
    private val retentionMillis: Long = 7L * 24 * 60 * 60 * 1000
) {
    internal data class Heartbeat(val timestampMs: Long, val pid: Int, val enabled: Boolean)
    internal data class UnobservedInterval(val lastHeartbeatMs: Long, val resumedMs: Long, val priorPid: Int) {
        val durationMs: Long get() = resumedMs - lastHeartbeatMs
    }

    init {
        // Carry forward journals written by the first debug flight-recorder build.
        listOf("events.previous.log", "events.log").map { File(directory, it) }
            .filter { it.isFile }.forEach { legacy ->
                val day = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date(legacy.lastModified()))
                var index = 0
                var target = File(directory, "events-$day-${index.toString().padStart(3, '0')}.log")
                while (target.exists()) {
                    index++
                    target = File(directory, "events-$day-${index.toString().padStart(3, '0')}.log")
                }
                // A failed migration must not prevent the debug app from starting.
                legacy.renameTo(target)
            }
        prune()
    }

    private fun segments(): List<File> {
        val files = directory.listFiles()?.filter { it.isFile }.orEmpty()
        val legacy = listOf("events.previous.log", "events.log")
            .map { name -> files.firstOrNull { it.name == name } }
            .filterNotNull()
        val current = files.filter { it.name.matches(Regex("events-\\d{8}-\\d{3}\\.log")) }
            .sortedBy { it.name }
        return legacy + current
    }

    @Synchronized
    fun append(line: String) {
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create diagnostic directory" }
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(nowMillis()))
        val today = segments().lastOrNull { it.name.startsWith("events-$day-") }
        val bytes = (line.replace('\n', ' ').replace('\r', ' ') + "\n").toByteArray(Charsets.UTF_8)
        val index = if (today == null) 0 else if (today.length() + bytes.size > maxFileBytes) {
            today.name.substringAfterLast('-').substringBefore('.').toInt() + 1
        } else today.name.substringAfterLast('-').substringBefore('.').toInt()
        val target = File(directory, "events-$day-${index.toString().padStart(3, '0')}.log")
        FileOutputStream(target, true).use { it.write(bytes) }
        target.setLastModified(nowMillis())
        prune()
    }

    @Synchronized
    private fun prune() {
        val cutoff = nowMillis() - retentionMillis
        segments().filter { it.lastModified() < cutoff }.forEach { it.delete() }
        var retained = segments()
        var total = retained.sumOf { it.length() }
        while (total > maxTotalBytes && retained.size > 1) {
            val oldest = retained.first()
            if (!oldest.delete()) break
            retained = retained.drop(1)
            total = retained.sumOf { it.length() }
        }
    }

    @Synchronized
    fun oldestEvent(): String? = segments().firstOrNull()?.bufferedReader()?.use { it.readLine() }

    @Synchronized
    fun snapshot(): List<Pair<String, ByteArray>> = segments().map { it.name to it.readBytes() }

    @Synchronized
    fun writeZip(zip: ZipOutputStream, report: String) {
        zip.putNextEntry(ZipEntry("report.txt"))
        zip.write(report.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        segments().forEach { file ->
            zip.putNextEntry(ZipEntry(file.name))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    @Synchronized
    fun markHeartbeat(pid: Int, enabled: Boolean) {
        writeState("heartbeat.state", "${nowMillis()},$pid,$enabled")
    }

    @Synchronized
    fun markServiceStop() {
        writeState("service-stop.state", nowMillis().toString())
    }

    @Synchronized
    fun lastHeartbeat(): Heartbeat? = runCatching {
        val fields = File(directory, "heartbeat.state").readText().split(',')
        Heartbeat(fields[0].toLong(), fields[1].toInt(), fields[2].toBooleanStrict())
    }.getOrNull()

    @Synchronized
    fun lastServiceStop(): Long? = runCatching {
        File(directory, "service-stop.state")
            .takeIf { it.isFile }?.readText()?.toLongOrNull()
    }.getOrNull()

    @Synchronized
    fun unobservedInterval(minimumMs: Long = 3L * 60 * 1000): UnobservedInterval? {
        val heartbeat = lastHeartbeat() ?: return null
        if (!heartbeat.enabled || (lastServiceStop() ?: 0L) > heartbeat.timestampMs) return null
        val resumed = nowMillis()
        if (resumed - heartbeat.timestampMs < minimumMs) return null
        return UnobservedInterval(heartbeat.timestampMs, resumed, heartbeat.pid)
    }

    private fun writeState(name: String, value: String) {
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create diagnostic directory" }
        val temporary = File(directory, "$name.tmp")
        temporary.writeText(value)
        check(temporary.renameTo(File(directory, name))) { "Cannot update diagnostic state" }
    }

    @Synchronized
    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }
}
