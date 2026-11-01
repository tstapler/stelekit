// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import arrow.core.Either
import dev.stapler.stelekit.db.RecoveryKind
import dev.stapler.stelekit.db.RestoreInFlight
import dev.stapler.stelekit.db.RestoreJournal
import dev.stapler.stelekit.db.RestoreRecovery
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Next-launch recovery of a restore the process did not finish (AC 3.4-G). */
class RestoreRecoveryTest {
    private val db = "/data/graph.db"
    private val files = FakeRestoreFiles()
    private val live = "pages=9,blocks=90;live"
    private val restored = "pages=4,blocks=40;snapshot"

    @BeforeTest
    fun reset() = RestoreRecovery.resetForTest()

    @AfterTest
    fun clear() = RestoreRecovery.resetForTest()

    private fun journal(state: RestoreJournal.State) =
        files.put("$db.restore-journal", RestoreJournal(state, "/data/console-backups/graph-1.db", restored.length.toLong(), 1L).render())

    @Test
    fun recover_should_PutPreviousFilesBack_When_KilledAfterTheLiveFilesWereMovedAside() {
        files.put("$db.pre-restore", live)
        files.put("$db-wal.pre-restore", "wal")
        files.put("$db.restoring", restored)
        journal(RestoreJournal.State.Swapping)
        val result = assertIs<Either.Right<*>>(RestoreRecovery.recoverIfNeeded(db, files, nowMs = 42L))
        assertEquals(RecoveryKind.PutBack, assertNotNull(RestoreRecovery.notice.value).kind)
        assertEquals(42L, RestoreRecovery.notice.value?.atMs)
        assertNotNull(result.value)
        assertEquals(live, files.store[db])
        assertEquals("wal", files.store["$db-wal"])
        assertNull(files.store["$db.pre-restore"])
        assertNull(files.store["$db.restoring"])
        assertNull(files.store["$db.restore-journal"])
    }

    @Test
    fun recover_should_DiscardTheRestoredFilesToo_When_KilledAfterTheVerifiedCopyWasMovedIn() {
        files.put(db, restored)
        files.put("$db.pre-restore", live)
        files.put("$db-wal", "stray-wal-of-restored-db")
        journal(RestoreJournal.State.Swapping)
        RestoreRecovery.recoverIfNeeded(db, files)
        assertEquals(live, files.store[db])
        assertNull(files.store["$db-wal"], "the restored database's WAL must not be applied to the previous database")
    }

    @Test
    fun recover_should_LeaveTheLiveDatabaseAlone_When_KilledBeforeAnyRename() {
        files.put(db, live)
        files.put("$db-wal", "wal")
        files.put("$db.restoring", restored)
        journal(RestoreJournal.State.Swapping)
        RestoreRecovery.recoverIfNeeded(db, files)
        assertEquals(live, files.store[db])
        assertEquals("wal", files.store["$db-wal"])
        assertNull(files.store["$db.restoring"])
        assertEquals(RecoveryKind.PutBack, RestoreRecovery.notice.value?.kind)
    }

    @Test
    fun recover_should_FinishWithoutTouchingSidecars_When_TheCopyWasAlreadyInPlace() {
        files.put(db, restored)
        files.put("$db-wal", "writes-made-after-reopen")
        files.put("$db.pre-restore", live)
        journal(RestoreJournal.State.Copied)
        RestoreRecovery.recoverIfNeeded(db, files)
        assertEquals(restored, files.store[db])
        assertEquals("writes-made-after-reopen", files.store["$db-wal"], "an already-opened restored database may have committed writes in its WAL")
        assertEquals(live, files.store["$db.pre-restore"], "the undo copy survives")
        assertNull(files.store["$db.restore-journal"])
        assertEquals(RecoveryKind.Finished, RestoreRecovery.notice.value?.kind)
    }

    @Test
    fun recover_should_KeepTheJournalAndReportTheStrandedFiles_When_RollbackFails() {
        files.put("$db.pre-restore", live)
        journal(RestoreJournal.State.Swapping)
        files.failRename = { _, _ -> true }
        val result = assertIs<Either.Left<List<String>>>(RestoreRecovery.recoverIfNeeded(db, files))
        assertEquals(listOf("$db.pre-restore"), result.value)
        assertNotNull(files.store["$db.restore-journal"], "retry on the next launch")
        assertNull(RestoreRecovery.notice.value, "no success banner for a failed recovery")
    }

    @Test
    fun recover_should_DoNothing_When_ThereIsNoJournal() {
        files.put(db, live)
        assertNull(RestoreRecovery.recoverIfNeeded(db, files))
        assertNull(RestoreRecovery.notice.value)
        assertEquals(live, files.store[db])
    }

    @Test
    fun recover_should_RemoveAStrayStagedCopy_When_ThereIsNoJournal() {
        files.put(db, live)
        files.put("$db.restoring", restored)
        assertNull(RestoreRecovery.recoverIfNeeded(db, files))
        assertNull(files.store["$db.restoring"])
        assertEquals(live, files.store[db])
    }

    @Test
    fun recover_should_SkipARestoreRunningInThisProcess() {
        files.put("$db.pre-restore", live)
        journal(RestoreJournal.State.Swapping)
        RestoreInFlight.begin(db)
        try {
            assertNull(RestoreRecovery.recoverIfNeeded(db, files))
        } finally {
            RestoreInFlight.end(db)
        }
        assertNotNull(files.store["$db.restore-journal"])
        assertNull(files.store[db])
    }

    @Test
    fun dismiss_should_ClearTheBannerAndTheDetailsFile() {
        files.put("$db.pre-restore", live)
        journal(RestoreJournal.State.Swapping)
        RestoreRecovery.recoverIfNeeded(db, files, nowMs = 7L)
        assertEquals(RecoveryKind.PutBack, RestoreRecovery.persistedNotice(db, files)?.kind)
        assertTrue(RestoreRecovery.notice.value?.headline.orEmpty().startsWith("A restore was interrupted - your previous data was put back."))
        RestoreRecovery.dismiss(db, files)
        assertNull(RestoreRecovery.notice.value)
        assertNull(RestoreRecovery.persistedNotice(db, files))
    }
}
