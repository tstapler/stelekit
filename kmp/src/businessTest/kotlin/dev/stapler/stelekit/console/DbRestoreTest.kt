// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.DbRestore
import dev.stapler.stelekit.db.RestoreCatalog
import dev.stapler.stelekit.db.RestoreInFlight
import dev.stapler.stelekit.db.RestoreJournal
import dev.stapler.stelekit.db.RestoreOutcome
import dev.stapler.stelekit.db.RestoreSource
import dev.stapler.stelekit.db.RestoreStep
import dev.stapler.stelekit.db.RowCounts
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DbRestoreTest {
    private val db = "/data/graph.db"
    private val dir = "/data/console-backups"
    private val backup = "graph-20261027-101500.db"
    private val files = FakeRestoreFiles()
    private val host = FakeRestoreHost()
    private val inspector = FakeRestoreInspector(files)
    private val restore = DbRestore(db, files, inspector, host, dir)

    private fun seed(liveCounts: String = "pages=9,blocks=90;live") {
        files.put(db, liveCounts)
        files.put("$db-wal", "wal-bytes")
        files.put("$db-shm", "shm-bytes")
        files.put("$dir/$backup", "pages=4,blocks=40;snapshot")
    }

    private fun run(source: RestoreSource = RestoreSource.Backup(backup), steps: MutableList<RestoreStep> = mutableListOf(), export: suspend () -> arrow.core.Either<String, Unit> = { Unit.right() }) =
        runBlocking { restore.restore(source, { steps += it }, export) }

    private fun assertUntouched() {
        assertEquals("pages=9,blocks=90;live", files.store[db])
        assertEquals("wal-bytes", files.store["$db-wal"])
        assertEquals("shm-bytes", files.store["$db-shm"])
        assertNull(files.store["$db.restoring"], "staged copy must be removed")
        assertNull(files.store["$db.restore-journal"], "journal must be cleared")
        assertNull(files.store["$db.pre-restore"])
    }

    @Test
    fun restore_should_SwapSnapshotInAndKeepPreviousAsUndo_When_EveryStepSucceeds() {
        seed()
        val steps = mutableListOf<RestoreStep>()
        val outcome = run(steps = steps)
        assertEquals(RestoreOutcome.Restored(RowCounts(4, 40), undoKept = true), outcome)
        assertEquals("pages=4,blocks=40;snapshot", files.store[db])
        assertEquals("pages=9,blocks=90;live", files.store["$db.pre-restore"])
        assertEquals("wal-bytes", files.store["$db-wal.pre-restore"])
        assertEquals("shm-bytes", files.store["$db-shm.pre-restore"])
        assertNull(files.store["$db-wal"], "the old WAL must not sit beside the restored database")
        assertNull(files.store["$db.restoring"])
        assertNull(files.store["$db.restore-journal"])
        assertEquals(listOf(RestoreStep.CheckingBackup, RestoreStep.ExportingLocalRows, RestoreStep.SwappingDatabase, RestoreStep.ReloadingGraph), steps)
        assertEquals(listOf("close#1", "lock", "unlock", "reopen"), host.calls)
    }

    @Test
    fun restore_should_WriteSwappingJournalBeforeClosingAndCopiedJournalAfterTheSwap_When_Restoring() {
        seed()
        var atClose: RestoreJournal? = null
        var atReopen: RestoreJournal? = null
        host.onClose = { atClose = RestoreJournal.parse(files.store["$db.restore-journal"]) }
        host.onReopen = { atReopen = RestoreJournal.parse(files.store["$db.restore-journal"]); true }
        run()
        assertEquals(RestoreJournal.State.Swapping, atClose?.state)
        assertEquals(RestoreJournal.State.Copied, atReopen?.state)
    }

    @Test
    fun restore_should_RunTheExportAfterVerifyingAndBeforeAnyRename_When_LocalRowsExist() {
        seed()
        var renamesAtExport = -1
        run(export = { renamesAtExport = files.renames.size; Unit.right() })
        assertEquals(0, renamesAtExport)
        assertTrue(host.calls.first().startsWith("close"))
    }

    @Test
    fun restore_should_ChangeNothing_When_TheExportFails() {
        seed()
        val outcome = run(export = { "disk full".left() })
        assertEquals(RestoreOutcome.Failed("disk full"), outcome)
        assertUntouched()
        assertTrue(host.calls.isEmpty(), "the database must not be closed when the export failed")
    }

    @Test
    fun restore_should_RefuseAndNotCloseTheDatabase_When_TheBackupFailsIntegrityCheck() {
        seed()
        files.put("$dir/$backup", "CORRUPT")
        val outcome = assertIs<RestoreOutcome.Failed>(run())
        assertTrue("failed integrity_check" in outcome.message, outcome.message)
        assertUntouched()
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun restore_should_RefuseAndNotCloseTheDatabase_When_TheBackupIsNotASteleKitDatabase() {
        seed()
        files.put("$dir/$backup", "just some bytes")
        val outcome = assertIs<RestoreOutcome.Failed>(run())
        assertTrue("isn't a SteleKit database" in outcome.message, outcome.message)
        assertUntouched()
    }

    @Test
    fun restore_should_RefuseWithoutCopying_When_FreeSpaceIsBelowOnePointTwoTimesTheBackup() {
        seed()
        val size = files.sizeOf("$dir/$backup")!!
        files.free = size + size / 5 - 1
        val outcome = assertIs<RestoreOutcome.Failed>(run())
        assertTrue(outcome.message.startsWith("Not enough free space"), outcome.message)
        assertNull(files.store["$db.restoring"])
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun restore_should_Pass_When_FreeSpaceIsExactlyOnePointTwoTimesTheBackup() {
        seed()
        val size = files.sizeOf("$dir/$backup")!!
        files.free = size + size / 5
        assertIs<RestoreOutcome.Restored>(run())
    }

    @Test
    fun restore_should_Fail_When_TheBackupCannotBeCopied() {
        seed()
        files.failCopy = true
        assertIs<RestoreOutcome.Failed>(run())
        assertUntouched()
    }

    @Test
    fun restore_should_Fail_When_TheJournalCannotBeWritten() {
        seed()
        files.failWrite = { it.endsWith("restore-journal") }
        val outcome = assertIs<RestoreOutcome.Failed>(run())
        assertTrue("journal" in outcome.message, outcome.message)
        assertUntouched()
        assertTrue(host.calls.isEmpty(), "no swap may start without a journal")
    }

    @Test
    fun restore_should_RejectNamesThatEscapeTheBackupDirectory_When_GivenAPath() {
        seed()
        listOf("../graph.db", "sub/graph.db", "graph.txt", "", "..").forEach { name ->
            val outcome = assertIs<RestoreOutcome.Failed>(run(RestoreSource.Backup(name)), name)
            assertTrue("isn't a backup name" in outcome.message, outcome.message)
        }
        assertUntouched()
    }

    @Test
    fun restore_should_Fail_When_TheNamedBackupDoesNotExist() {
        seed()
        val outcome = assertIs<RestoreOutcome.Failed>(run(RestoreSource.Backup("graph-missing.db")))
        assertTrue(outcome.message.startsWith("No backup at"), outcome.message)
    }

    @Test
    fun restore_should_PutPreviousFilesBack_When_MovingTheWalAsideFails() {
        seed()
        files.failRename = { from, _ -> from == "$db-wal" }
        val outcome = assertIs<RestoreOutcome.Failed>(run())
        assertTrue("Your previous data was put back" in outcome.message, outcome.message)
        assertUntouched()
        assertEquals(listOf("close#1", "lock", "unlock", "reopen"), host.calls, "a failed swap reopens the graph on the old data")
    }

    @Test
    fun restore_should_PutPreviousFilesBack_When_TheVerifiedCopyCannotBeMovedIntoPlace() {
        seed()
        files.failRename = { from, _ -> from == "$db.restoring" }
        val outcome = assertIs<RestoreOutcome.Failed>(run())
        assertTrue("Your previous data was put back" in outcome.message, outcome.message)
        assertUntouched()
    }

    @Test
    fun restore_should_RollBackAndReopen_When_TheRestoredDatabaseDoesNotOpen() {
        seed()
        var opens = 0
        host.onReopen = { opens++ > 0 }
        val outcome = assertIs<RestoreOutcome.Failed>(run())
        assertTrue("didn't open" in outcome.message && "put back" in outcome.message, outcome.message)
        assertUntouched()
        assertEquals(listOf("close#1", "lock", "unlock", "reopen", "close#2", "lock", "unlock", "reopen"), host.calls)
    }

    @Test
    fun restore_should_RollBack_When_RowCountsAfterTheSwapDoNotMatchTheBackup() {
        seed()
        var tampered = false
        host.onReopen = { if (!tampered) files.put(db, "pages=5,blocks=40;tampered"); tampered = true; true }
        val outcome = assertIs<RestoreOutcome.Failed>(run())
        assertTrue("don't match the backup" in outcome.message, outcome.message)
        assertUntouched()
    }

    @Test
    fun restore_should_KeepTheJournalAndNameTheFiles_When_RollbackAlsoFails() {
        seed()
        files.failRename = { from, to -> from == "$db.restoring" || (from == "$db.pre-restore" && to == db) }
        val outcome = assertIs<RestoreOutcome.RollbackFailed>(run())
        assertTrue(outcome.paths.contains("$db.pre-restore"), outcome.message)
        assertTrue("could not be put back automatically" in outcome.message && "$db.pre-restore" in outcome.message, outcome.message)
        assertNotNull(files.store["$db.restore-journal"], "the journal stays so the next launch retries the rollback")
        assertEquals("pages=9,blocks=90;live", files.store["$db.pre-restore"], "the previous data is intact at the named path")
        assertFalse(host.calls.contains("reopen"), "do not reopen while the live file is missing")
    }

    @Test
    fun restore_should_ReopenOnOldData_When_ClosingTheDatabaseThrows() {
        seed()
        host.onClose = { error("pool busy") }
        val outcome = assertIs<RestoreOutcome.Failed>(run())
        assertTrue("pool busy" in outcome.message && "Nothing was changed" in outcome.message, outcome.message)
        assertUntouched()
        assertEquals("reopen", host.calls.last())
    }

    @Test
    fun restore_should_MarkTheRestoreInFlightOnlyWhileRunning() {
        seed()
        var duringRestore = false
        host.onClose = { duringRestore = RestoreInFlight.contains(db) }
        run()
        assertTrue(duringRestore)
        assertFalse(RestoreInFlight.contains(db))
    }

    @Test
    fun restore_should_ToggleBetweenTwoStates_When_TheUndoCopyIsRestored() {
        seed()
        files.put("$db-wal", "") // a cleanly closed database leaves an empty WAL
        run()
        val outcome = run(RestoreSource.UndoCopy)
        assertEquals(RestoreOutcome.Restored(RowCounts(9, 90), undoKept = true), outcome)
        assertEquals("pages=9,blocks=90;live", files.store[db], "undo returns the state that was live before the restore")
        assertEquals("pages=4,blocks=40;snapshot", files.store["$db.pre-restore"], "the restored state is now the single undo copy")
        assertEquals(listOf("$db.pre-restore"), files.store.keys.filter { it.endsWith(".pre-restore") }.sorted(), "exactly one set, never two")
    }

    @Test
    fun restore_should_RefuseTheUndoCopy_When_ItHasUnflushedWalChanges() {
        seed()
        run()
        files.put("$db-wal.pre-restore", "unflushed")
        val outcome = assertIs<RestoreOutcome.Failed>(run(RestoreSource.UndoCopy))
        assertTrue("unflushed" in outcome.message, outcome.message)
    }

    @Test
    fun restore_should_ReplaceTheEarlierUndoCopy_When_RestoringTwice() {
        seed()
        run()
        files.put("$dir/graph-20261028-090000.db", "pages=7,blocks=70;second")
        run(RestoreSource.Backup("graph-20261028-090000.db"))
        assertEquals("pages=4,blocks=40;snapshot", files.store["$db.pre-restore"], "the undo copy is the state live just before the second restore")
    }

    @Test
    fun catalog_should_ListOnlyThisGraphsSnapshotsNewestFirstAndFindTheUndoCopy() {
        files.put("$dir/graph-20261027-101500.db", "pages=1,blocks=1", 100)
        files.put("$dir/graph-20261028-090000.db", "pages=1,blocks=1", 200)
        files.put("$dir/other-20261028-090000.db", "pages=1,blocks=1", 300)
        files.put("$dir/graph-20261029-000000.db.partial", "x", 400)
        val catalog = RestoreCatalog(db, files, dir)
        assertEquals(listOf("graph-20261028-090000.db", "graph-20261027-101500.db"), catalog.backups().map { it.name })
        assertNull(catalog.undoCopy())
        files.put("$db.pre-restore", "pages=1,blocks=1", 500)
        assertEquals("graph.db.pre-restore", catalog.undoCopy()?.name)
    }

    @Test
    fun journal_should_RoundTripAndIgnoreGarbage() = runBlocking<Unit> {
        checkAll(Arb.enum<RestoreJournal.State>(), Arb.string(0, 40), Arb.long(0, Long.MAX_VALUE), Arb.long(0, Long.MAX_VALUE)) { state, source, bytes, started ->
            val clean = source.replace("\n", "")
            val journal = RestoreJournal(state, clean, bytes, started)
            assertEquals(journal, RestoreJournal.parse(journal.render()))
        }
        assertNull(RestoreJournal.parse(null))
        assertNull(RestoreJournal.parse("state=Nonsense\n"))
        assertNull(RestoreJournal.parse("garbage"))
    }
}
