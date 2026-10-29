// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import dev.stapler.stelekit.db.BackupEngine
import dev.stapler.stelekit.db.BackupFileInfo
import dev.stapler.stelekit.db.BackupFiles
import dev.stapler.stelekit.db.ConsoleBackup
import dev.stapler.stelekit.db.ReadHandleBackupEngine
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleBackupTest {
    private val dbPath = "/data/databases/graph.db"
    private val dir = "/data/databases/console-backups"

    private class FakeFiles(var dbBytes: Long = 100L * MB, var free: Long? = 1_000L * MB) : BackupFiles {
        val files = mutableMapOf<String, Long>()
        override fun ensureDir(path: String) = true
        override fun list(dir: String) = files.map { BackupFileInfo(it.key.substringAfterLast('/'), it.value) }
        override fun delete(path: String) = files.remove(path.substringAfterLast('/')) != null
        override fun rename(from: String, to: String): Boolean { val v = files.remove(from.substringAfterLast('/')) ?: return false; files[to.substringAfterLast('/')] = v; return true }
        override fun sizeOf(path: String) = if (path.endsWith("graph.db")) dbBytes else files[path.substringAfterLast('/')]?.let { 1L }
        override fun usableSpace(path: String) = free
    }

    private class FakeEngine(val files: FakeFiles, val clock: () -> Long) : BackupEngine {
        var integrity = listOf("ok")
        var failVacuum: String? = null
        val targets = mutableListOf<String>()
        override suspend fun vacuumInto(target: String) {
            failVacuum?.let { throw IllegalStateException(it) }
            targets += target
            files.files[target.substringAfterLast('/')] = clock()
        }
        override suspend fun integrityCheck(path: String) = integrity
    }

    private var now = 1_000_000_000_000L
    private fun backup(files: FakeFiles, engine: FakeEngine, retention: Int = 2) =
        ConsoleBackup(dbPath, files, engine, nowMs = { now }, retention = retention, zone = TimeZone.UTC)

    @Test
    fun backup_should_RefuseWithTheSingleFailureCopy_When_FreeSpaceBelowOnePointTwoTimesDb() = runBlocking<Unit> {
        val files = FakeFiles(dbBytes = 100L * MB, free = 119L * MB)
        val engine = FakeEngine(files) { now }
        val result = backup(files, engine).backup()
        assertEquals("Backup failed: not enough free space (need 120 MB, have 119 MB). Nothing was changed.", result.leftOrNull())
        assertTrue(engine.targets.isEmpty(), "nothing may be written when the precheck fails")
    }

    @Test
    fun plan_should_Pass_When_FreeSpaceIsExactlyOnePointTwoTimesDb() {
        val files = FakeFiles(dbBytes = 100L * MB, free = 120L * MB)
        val plan = backup(files, FakeEngine(files) { now }).plan().getOrNull()
        assertEquals(100L * MB, plan?.dbBytes)
        assertEquals(dir, plan?.directory)
    }

    @Test
    fun backup_should_NameTheFileByStemAndTimestamp_When_Verified() = runBlocking<Unit> {
        val files = FakeFiles()
        val engine = FakeEngine(files) { now }
        val path = backup(files, engine).backup().getOrNull()
        assertEquals("$dir/graph-20010909-014640.db", path)
        assertTrue(engine.targets.single().endsWith(".db.partial"), "the snapshot is written under a partial name first")
        assertEquals(setOf("graph-20010909-014640.db"), files.files.keys)
    }

    @Test
    fun backup_should_FailAndLeaveNothing_When_IntegrityCheckIsNotOk() = runBlocking<Unit> {
        val files = FakeFiles()
        val engine = FakeEngine(files) { now }.also { it.integrity = listOf("row 3 missing from index x") }
        val result = backup(files, engine).backup()
        assertEquals("Backup failed: integrity_check reported row 3 missing from index x. Nothing was changed.", result.leftOrNull())
        assertTrue(files.files.isEmpty())
    }

    @Test
    fun backup_should_FailWithEngineReason_When_VacuumIntoThrows() = runBlocking<Unit> {
        val files = FakeFiles()
        val engine = FakeEngine(files) { now }.also { it.failVacuum = "disk I/O error" }
        val result = backup(files, engine).backup()
        assertEquals("Backup failed: disk I/O error. Nothing was changed.", result.leftOrNull())
        assertTrue(files.files.isEmpty())
    }

    @Test
    fun backup_should_KeepOnlyTheNewestN_When_RetentionExceeded() = runBlocking<Unit> {
        val files = FakeFiles()
        val engine = FakeEngine(files) { now }
        val b = backup(files, engine, retention = 2)
        repeat(4) { now += 60_000; assertTrue(b.backup().isRight()) }
        assertEquals(2, files.files.size)
        assertEquals(listOf(now - 60_000, now), files.files.values.sorted())
    }

    @Test
    fun retention_should_BeClampedToOneThroughFive_When_Configured() {
        val files = FakeFiles()
        assertEquals(1, backup(files, FakeEngine(files) { now }, retention = 0).retention)
        assertEquals(5, backup(files, FakeEngine(files) { now }, retention = 50).retention)
        assertEquals(2, ConsoleBackup.DEFAULT_RETENTION)
    }

    @Test
    fun backup_should_RemoveStalePartialFile_When_AKilledRunLeftOne() = runBlocking<Unit> {
        val files = FakeFiles()
        files.files["graph-20010101-000000.db.partial"] = 1
        val result = backup(files, FakeEngine(files) { now }).backup()
        assertTrue(result.isRight())
        assertFalse(files.files.keys.any { it.endsWith(".partial") })
    }

    @Test
    fun needed_should_FollowSessionAndDestructivePolicy_When_WritesFollowEachOther() = runBlocking<Unit> {
        val files = FakeFiles()
        val b = backup(files, FakeEngine(files) { now })
        assertTrue(b.needed(destructive = false), "first write of a session")
        b.backup()
        now += 60_000
        assertFalse(b.needed(destructive = false), "an INSERT or UPDATE within 10 minutes reuses the backup")
        assertTrue(b.needed(destructive = true), "a DELETE or REPLACE always gets a fresh one")
        now += ConsoleBackup.FRESH_WINDOW_MS
        assertTrue(b.needed(destructive = false), "a backup older than 10 minutes is stale")
    }

    @Test
    fun forJdbcUrl_should_ReturnNull_When_InMemoryDatabase() {
        assertEquals(null, ConsoleBackup.forJdbcUrl("jdbc:sqlite::memory:"))
    }

    @Test
    fun realEngine_should_TakeVerifiedSnapshotWithRetention_When_RunOnFileDatabase() = runBlocking<Unit> {
        SqlConsoleFixture(pages = 5).use { fixture ->
            val backup = ConsoleBackup.forJdbcUrl(fixture.url, retention = 2) ?: error("JVM must support console backups")
            val first = backup.backup().getOrNull() ?: error("backup failed")
            assertTrue(File(first).isFile && File(first).length() > 0, first)
            assertEquals(File(fixture.dir, "console-backups"), File(first).parentFile)
            assertEquals(listOf("ok"), ReadHandleBackupEngine(fixture.url).integrityCheck(first))
            val second = backup.backup().getOrNull() ?: error("second backup failed")
            val third = backup.backup().getOrNull() ?: error("third backup failed")
            val kept = File(fixture.dir, "console-backups").listFiles().orEmpty().map { it.path }.sorted()
            assertEquals(listOf(second, third).sorted(), kept, "only the 2 newest remain and no .partial is left")
        }
    }

    private companion object {
        const val MB = 1_048_576L
    }
}
