package com.quintz.wifi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FlightJournalTest {
    @Test
    fun eventsSurviveNewJournalInstanceAndCanBeCleared() {
        val directory = Files.createTempDirectory("quintz-flight-test").toFile()
        try {
            FlightJournal(directory).append("before process restart")
            val reopened = FlightJournal(directory)
            assertTrue(reopened.snapshot().single().second.toString(Charsets.UTF_8).contains("before process restart"))
            reopened.clear()
            assertEquals(emptyList<Pair<String, ByteArray>>(), FlightJournal(directory).snapshot())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun gapDistinguishesMissingHeartbeatFromNormalStop() {
        val directory = Files.createTempDirectory("quintz-gap-test").toFile()
        try {
            var now = Instant.parse("2026-09-30T00:00:00Z").toEpochMilli()
            val journal = FlightJournal(directory, { now })
            journal.markHeartbeat(123, true)
            now += 6 * 60 * 1000
            val reopened = FlightJournal(directory, { now })
            assertEquals(6 * 60 * 1000L, reopened.unobservedInterval()?.durationMs)
            assertEquals(123, reopened.unobservedInterval()?.priorPid)
            reopened.markServiceStop()
            assertEquals(null, reopened.unobservedInterval())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun prunesOldEventsAndExportsRetainedEventsInZip() {
        val directory = Files.createTempDirectory("quintz-retention-test").toFile()
        try {
            var now = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli()
            val journal = FlightJournal(directory, { now }, maxTotalBytes = 40, maxFileBytes = 20)
            journal.append("old event")
            now += 8L * 24 * 60 * 60 * 1000
            journal.append("recent event")
            assertFalse(journal.snapshot().any { it.second.toString(Charsets.UTF_8).contains("old event") })
            assertTrue(journal.oldestEvent()!!.contains("recent event"))

            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { journal.writeZip(it, "device report") }
            val contents = mutableMapOf<String, String>()
            ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    contents[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
            assertEquals("device report", contents["report.txt"])
            assertNotNull(contents.entries.firstOrNull { it.key.startsWith("events-") && it.value.contains("recent event") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun storageCapRemovesOldestSegment() {
        val directory = Files.createTempDirectory("quintz-cap-test").toFile()
        try {
            val now = Instant.parse("2026-09-30T00:00:00Z").toEpochMilli()
            val journal = FlightJournal(directory, { now }, maxTotalBytes = 33, maxFileBytes = 12)
            (1..4).forEach { journal.append("entry-$it-xx") }
            val retained = journal.snapshot().joinToString("") { it.second.toString(Charsets.UTF_8) }
            assertFalse(retained.contains("entry-1"))
            assertTrue(retained.contains("entry-4"))
            assertTrue(journal.snapshot().sumOf { it.second.size } <= 33)
        } finally {
            directory.deleteRecursively()
        }
    }
    @Test fun exportedSnapshotDoesNotChangeWhenJournalContinuesWriting() {
        val directory = Files.createTempDirectory("quintz-journal-test").toFile()
        val snapshot = Files.createTempDirectory("quintz-snapshot-test").toFile()
        try {
            val journal = FlightJournal(directory); journal.append("before snapshot")
            val files = journal.snapshotFiles(snapshot); journal.append("after snapshot")
            assertTrue(files.single().readText().contains("before snapshot"))
            assertFalse(files.single().readText().contains("after snapshot"))
        } finally { directory.deleteRecursively(); snapshot.deleteRecursively() }
    }
}
