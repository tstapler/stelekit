// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.console.spike.SpikeSupport
import dev.stapler.stelekit.db.ConsoleBackup
import dev.stapler.stelekit.db.DbRestore
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.ReadHandleRestoreInspector
import dev.stapler.stelekit.db.RestoreHost
import dev.stapler.stelekit.db.RestoreOutcome
import dev.stapler.stelekit.db.RestoreRecovery
import dev.stapler.stelekit.db.RestoreSource
import dev.stapler.stelekit.db.platformBackupFiles
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The restore swap on real files and a real SQLite database through the production file operations, inspector and
 * backup. `SPIKE_DB_BYTES=472907776 SPIKE_TMP=<big disk>` runs it at the owner's 451 MB size. The app side is a
 * driver the test opens and closes the way `GraphManager` does around a restore.
 */
class DbRestoreSwapTest {
    private lateinit var dir: File
    private lateinit var live: File
    private lateinit var host: DriverHost
    private val files = checkNotNull(platformBackupFiles())

    private class DriverHost(val live: File) : RestoreHost {
        var app: SqlDriver = open()
        var reopenResults = ArrayDeque<Boolean>()
        private fun open() = DriverFactory().createDriver(SpikeSupport.url(live))
        override suspend fun closeDatabase() = app.close()
        override suspend fun <T> underSwapLock(block: suspend () -> T): T = block()
        override suspend fun reopenDatabase(): Boolean {
            app = open()
            return reopenResults.removeFirstOrNull() ?: true
        }
    }

    @BeforeTest
    fun setUp() {
        RestoreRecovery.resetForTest()
        dir = SpikeSupport.tempDir("restore-swap")
        live = dir.resolve("graph.db")
        host = DriverHost(live)
    }

    @AfterTest
    fun tearDown() {
        runCatching { host.app.close() }
        dir.deleteRecursively()
    }

    private fun pages(file: File = live): Long = SpikeSupport.readOnlyConnection(file).use { SpikeSupport.scalarLong(it, "SELECT COUNT(*) FROM pages") }

    private fun addPages(n: Int) = SpikeSupport.readWriteConnection(live).use { c ->
        repeat(n) { c.createStatement().use { s -> s.execute("INSERT INTO pages(uuid,name,created_at,updated_at) VALUES ('late-$it','Late $it',1,1)") } }
    }

    private fun integrity(file: File = live) = SpikeSupport.readOnlyConnection(file).use { SpikeSupport.scalarString(it, "PRAGMA integrity_check") }

    /** Seeds, takes a real `ConsoleBackup`, then adds [extraPages] the restore will discard. Returns the backup name and seeded page count. */
    private fun seedBackupAndDrift(extraPages: Int = 50): Pair<String, Long> {
        SpikeSupport.seed(live, SpikeSupport.targetDbBytes(), blocksPerPage = 2, contentBytes = 100)
        val seeded = pages()
        val snapshot = runBlocking { checkNotNull(ConsoleBackup.forJdbcUrl(SpikeSupport.url(live))).backup() }.getOrNull()
        assertNotNull(snapshot, "backup must succeed")
        addPages(extraPages)
        assertEquals(seeded + extraPages, pages())
        return File(snapshot).name to seeded
    }

    private fun restore(source: RestoreSource, steps: MutableList<String> = mutableListOf()): RestoreOutcome =
        runBlocking {
            DbRestore(live.absolutePath, files, ReadHandleRestoreInspector(), host, File(dir, ConsoleBackup.DIRECTORY_NAME).absolutePath)
                .restore(source, { steps += it.label })
        }

    @Test
    fun restore_should_ReplaceTheDatabaseWithTheSnapshotAndKeepTheOldOneAsUndo_When_RealFilesAreSwapped() {
        val (name, seeded) = seedBackupAndDrift()
        val started = System.nanoTime()
        val outcome = restore(RestoreSource.Backup(name))
        SpikeSupport.record("3.4", "restore_ms", (System.nanoTime() - started) / NANOS_PER_MS)
        val restored = assertIs<RestoreOutcome.Restored>(outcome)
        assertEquals(seeded, restored.counts.pages)
        assertEquals(seeded, pages(), "later changes are lost")
        assertEquals("ok", integrity())
        assertEquals(seeded + 50, pages(File(live.path + ".pre-restore")), "the undo copy holds what was live")
        assertFalse(File(live.path + ".restoring").exists())
        assertFalse(File(live.path + ".restore-journal").exists())
        // The reopened app driver is writable and sees the restored data (no stale WAL replayed onto it).
        host.app.execute(null, "INSERT INTO pages(uuid,name,created_at,updated_at) VALUES ('after','After',1,1)", 0).value
        host.app.close()
        assertEquals(seeded + 1, pages())
    }

    @Test
    fun restore_should_ToggleBackWithUndo_When_TheUndoCopyIsRestored() {
        val (name, seeded) = seedBackupAndDrift()
        assertIs<RestoreOutcome.Restored>(restore(RestoreSource.Backup(name)))
        // The close inside the restore must leave no unflushed WAL on the undo copy, or undo would refuse.
        assertIs<RestoreOutcome.Restored>(restore(RestoreSource.UndoCopy))
        host.app.close()
        assertEquals(seeded + 50, pages())
        assertEquals(seeded, pages(File(live.path + ".pre-restore")))
        assertEquals("ok", integrity())
    }

    @Test
    fun restore_should_RefuseACorruptBackupAndLeaveTheLiveDatabaseOpenable() {
        val (name, seeded) = seedBackupAndDrift()
        val backup = File(dir, "${ConsoleBackup.DIRECTORY_NAME}/$name")
        java.io.RandomAccessFile(backup, "rw").use { f ->
            f.seek(backup.length() / 2)
            f.write(ByteArray(CORRUPT_BYTES) { 0x5A })
        }
        val outcome = restore(RestoreSource.Backup(name))
        val failed = assertIs<RestoreOutcome.Failed>(outcome)
        assertTrue("integrity_check" in failed.message || "isn't a SteleKit database" in failed.message, failed.message)
        assertEquals(seeded + 50, pages(), "the live database is untouched")
        assertFalse(File(live.path + ".restoring").exists())
        assertFalse(File(live.path + ".pre-restore").exists())
    }

    @Test
    fun restore_should_RollBackToTheLiveDatabase_When_TheRestoredDatabaseFailsToOpen() {
        val (name, seeded) = seedBackupAndDrift()
        host.reopenResults = ArrayDeque(listOf(false, true))
        val outcome = restore(RestoreSource.Backup(name))
        val failed = assertIs<RestoreOutcome.Failed>(outcome)
        assertTrue("put back" in failed.message, failed.message)
        host.app.close()
        assertEquals(seeded + 50, pages(), "rollback returned the pre-restore data")
        assertEquals("ok", integrity())
        assertFalse(File(live.path + ".pre-restore").exists())
        assertFalse(File(live.path + ".restore-journal").exists())
    }

    @Test
    fun recovery_should_PutTheLiveDatabaseBack_When_ARestoreWasKilledAfterTheRenames() {
        val (name, seeded) = seedBackupAndDrift()
        // Reproduce a kill between "live moved aside" and "copy opened": run the renames by hand, leave the journal.
        host.app.close()
        File(live.path).renameTo(File(live.path + ".pre-restore"))
        File(live.path + "-wal").renameTo(File(live.path + "-wal.pre-restore"))
        File(live.path + "-shm").renameTo(File(live.path + "-shm.pre-restore"))
        java.nio.file.Files.copy(File(dir, "${ConsoleBackup.DIRECTORY_NAME}/$name").toPath(), live.toPath())
        File(live.path + ".restore-journal").writeText("state=Swapping\nsource=$name\nbytes=1\nstartedMs=1\n")

        val recovered = RestoreRecovery.recoverIfNeeded(live.absolutePath, files)
        assertNotNull(recovered)
        assertTrue(recovered.isRight(), recovered.toString())
        assertEquals(seeded + 50, pages(), "recovery restored the data that was live")
        assertEquals("ok", integrity())
        assertNull(File(live.path + ".restore-journal").takeIf { it.exists() })
        assertEquals("A restore was interrupted - your previous data was put back. Nothing from the restore was kept.", RestoreRecovery.notice.value?.headline)
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val CORRUPT_BYTES = 4096
    }
}
