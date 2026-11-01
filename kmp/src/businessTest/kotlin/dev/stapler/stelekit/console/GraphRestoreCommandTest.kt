// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import dev.stapler.stelekit.console.commands.BackupsCommand
import dev.stapler.stelekit.console.commands.GraphRestoreCommand
import dev.stapler.stelekit.console.commands.HelpCommand
import dev.stapler.stelekit.console.commands.RestoreConfirm
import dev.stapler.stelekit.console.commands.RestoreRunner
import dev.stapler.stelekit.console.commands.RestoreToolkit
import dev.stapler.stelekit.db.DbRestore
import dev.stapler.stelekit.db.RestoreCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphRestoreCommandTest {
    private val db = "/data/graph.db"
    private val dir = "/data/console-backups"
    private val name = "graph-20261027-101500.db"
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_793_000_000_000L
    private val files = FakeRestoreFiles()
    private val host = FakeRestoreHost()
    private val requests = mutableListOf<ConfirmRequest>()
    private val log = ConsoleWriteLog()
    private var localPages = listOf<DbOnlyPage>()
    private var enabled = true

    private val inspector = FakeRestoreInspector(files)
    private val toolkit = RestoreToolkit(
        dbPath = db,
        backupsDirectory = dir,
        catalog = RestoreCatalog(db, files, dir),
        inspector = inspector,
        restore = DbRestore(db, files, inspector, host, dir),
        guard = DbOnlyRowsGuard({ localPages }, files, "/data/console-recovery", nowMs = { now }, zone = TimeZone.UTC),
    )
    private val runner = RestoreRunner({ toolkit }, log, { enabled }, { now })
    private val command = GraphRestoreCommand(runner) { enabled }

    private fun seed() {
        files.put(db, "pages=9431,blocks=90000;live")
        files.put("$dir/$name", "pages=9402,blocks=88000;snapshot", now - 2 * day)
    }

    private fun context(answer: ConfirmAnswer) = ConsoleContext(ConsoleRegistry(emptyList()), confirmer = ConsoleConfirmer { requests += it; answer })
    private fun run(args: List<String>, answer: ConfirmAnswer = ConfirmAnswer.Proceed(RestoreConfirm.PHRASE), out: CapturedOutput = CapturedOutput()) =
        runBlocking { command.run(args, context(answer), out) } to out

    @Test
    fun run_should_BeDisabledAndOpenNoConfirm_When_WritesNotEnabledInThisBuild() = runBlocking<Unit> {
        seed()
        enabled = false
        assertEquals("writes not enabled in this build", command.disabledReason)
        val (result, _) = run(listOf("restore-backup", name))
        assertEquals("disabled: writes not enabled in this build", result.leftOrNull()?.message)
        assertTrue(requests.isEmpty())
        assertEquals("pages=9431,blocks=90000;live", files.store[db])
        assertTrue(host.calls.isEmpty())
        val help = CapturedOutput()
        HelpCommand().run(emptyList(), ConsoleContext(ConsoleRegistry(listOf(command))), help)
        assertTrue(help.lines.any { it.startsWith("graph") && it.contains("disabled: writes not enabled in this build") }, help.lines.toString())
    }

    @Test
    fun run_should_ShowUsage_When_ArgumentsAreWrong() {
        seed()
        listOf(emptyList<String>(), listOf("restore-backup"), listOf("restore-backup", "a", "b"), listOf("reload"), listOf("undo-restore", "x")).forEach { args ->
            val (result, _) = run(args)
            assertTrue(result.leftOrNull()?.message.orEmpty().startsWith("Usage:"), args.toString())
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun run_should_QuantifyWhatIsLostInTheConfirm_When_RestoringATwoDayOldBackup() {
        seed()
        localPages = listOf(DbOnlyPage("2026_10_07", "- a\n"), DbOnlyPage("2026_10_09", "- b\n"), DbOnlyPage("2026_10_10", "- c\n"))
        files.put("$db.pre-restore", "pages=1,blocks=1;old-undo", now - 5 * day)
        run(listOf("restore-backup", name))
        val request = requests.single()
        val text = request.details.joinToString("\n")
        assertEquals(RestoreConfirm.TIER, request.tier)
        assertEquals("Restore 2-day-old backup", request.primaryLabel)
        assertTrue("2 days ago" in text, text)
        assertTrue("9402 pages and 88000 blocks" in text && "9431 pages and 90000 blocks" in text, text)
        assertTrue("29 pages" in text, text)
        assertTrue("3 local-only journal pages" in text && "/data/console-recovery" in text, text)
        assertTrue("replaces the earlier undo copy from 5 days ago" in text, text)
        assertTrue("Later changes are lost" in text, text)
    }

    @Test
    fun run_should_RestoreAndRecordIt_When_TheOwnerTypesRestoreExactly() {
        seed()
        val (result, out) = run(listOf("restore-backup", name))
        assertTrue(result.isRight(), result.toString())
        assertEquals("pages=9402,blocks=88000;snapshot", files.store[db])
        assertTrue(out.lines.any { it.startsWith("Restored. Pages: 9402, blocks: 88000 - matches the backup.") }, out.lines.toString())
        assertEquals(listOf("Checking backup...", "Exporting local-only rows...", "Swapping database...", "Reloading graph..."), out.lines.take(4))
        assertTrue(log.entries.single().startsWith("RESTORE "), log.entries.toString())
    }

    @Test
    fun run_should_SaveLocalOnlyPagesBeforeTheSwap_When_JournalRowsExist() {
        seed()
        localPages = listOf(DbOnlyPage("2026_10_07", "- a\n"))
        var exportedBeforeClose = false
        host.onClose = { exportedBeforeClose = files.store.keys.any { it.startsWith("/data/console-recovery/") } }
        val (_, out) = run(listOf("restore-backup", name))
        assertTrue(exportedBeforeClose, "DB-only rows must be on disk before the database closes")
        assertTrue(out.lines.any { it.startsWith("Local-only pages saved to /data/console-recovery/") }, out.lines.toString())
    }

    @Test
    fun run_should_ChangeNothing_When_ConfirmIsCancelledOrTypedWrongly() {
        seed()
        listOf(ConfirmAnswer.Cancel, ConfirmAnswer.Proceed("restore"), ConfirmAnswer.Proceed("RESTORE now"), ConfirmAnswer.Proceed(null)).forEach { answer ->
            val (result, out) = run(listOf("restore-backup", name), answer)
            assertTrue(result.isRight())
            assertEquals(listOf("Cancelled. Nothing was changed."), out.lines, answer.toString())
        }
        assertEquals("pages=9431,blocks=90000;live", files.store[db])
        assertTrue(host.calls.isEmpty())
        assertNull(files.store["$db.restore-journal"])
    }

    @Test
    fun run_should_AcceptSurroundingSpacesAroundTheTypedPhrase() {
        seed()
        val (result, _) = run(listOf("restore-backup", name), ConfirmAnswer.Proceed("  RESTORE  "))
        assertTrue(result.isRight())
        assertEquals("pages=9402,blocks=88000;snapshot", files.store[db])
    }

    @Test
    fun run_should_FailBeforeAskingAnything_When_TheBackupNameIsUnknown() {
        seed()
        val (result, _) = run(listOf("restore-backup", "graph-nope.db"))
        assertTrue(result.leftOrNull()?.message.orEmpty().contains("No backup named graph-nope.db"), result.toString())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun run_should_ReportFailureText_When_TheSwapFails() {
        seed()
        files.failRename = { from, _ -> from == "$db.restoring" }
        val (result, _) = run(listOf("restore-backup", name))
        assertTrue(result.leftOrNull()?.message.orEmpty().contains("Your previous data was put back"), result.toString())
        assertEquals("pages=9431,blocks=90000;live", files.store[db])
        assertTrue(log.entries.isEmpty(), "a failed restore is not logged as a write")
    }

    @Test
    fun undoRestore_should_SwapBackThePreviousState_When_AnUndoCopyExists() {
        seed()
        files.put("$db-wal", "")
        run(listOf("restore-backup", name))
        requests.clear()
        val (result, out) = run(listOf("undo-restore"))
        assertTrue(result.isRight(), result.toString())
        assertEquals("Restore undo copy", requests.single().primaryLabel)
        assertEquals("pages=9431,blocks=90000;live", files.store[db])
        assertTrue(out.lines.any { it.startsWith("Restored. Pages: 9431") }, out.lines.toString())
    }

    @Test
    fun undoRestore_should_Fail_When_ThereIsNoUndoCopy() {
        seed()
        val (result, _) = run(listOf("undo-restore"))
        assertTrue(result.leftOrNull()?.message.orEmpty().contains("No undo copy found"), result.toString())
    }

    @Test
    fun backups_should_ListSnapshotsNewestFirstWithAgeAndTheUndoCopy() = runBlocking<Unit> {
        seed()
        files.put("$dir/graph-20261028-090000.db", "pages=1,blocks=1", now - day)
        files.put("$db.pre-restore", "pages=1,blocks=1;x", now - 3600_000L)
        val out = CapturedOutput()
        val backups = BackupsCommand({ toolkit }, runner, { now })
        assertTrue(backups.run(emptyList(), ConsoleContext(ConsoleRegistry(emptyList())), out).isRight())
        assertEquals(3, out.lines.size)
        assertTrue(out.lines[0].startsWith("graph-20261028-090000.db  1 day ago"), out.lines[0])
        assertTrue(out.lines[1].startsWith("$name  2 days ago"), out.lines[1])
        assertTrue(out.lines[2].startsWith("Before restore  1 hour ago") && "graph undo-restore" in out.lines[2], out.lines[2])
    }

    @Test
    fun backups_should_ListEvenWhenWritesAreDisabled_AndRestoreAliasIsGated() = runBlocking<Unit> {
        seed()
        enabled = false
        val backups = BackupsCommand({ toolkit }, runner, { now })
        assertNull(backups.disabledReason)
        val out = CapturedOutput()
        assertTrue(backups.run(emptyList(), ConsoleContext(ConsoleRegistry(emptyList())), out).isRight())
        assertEquals(1, out.lines.size)
        val restore = backups.run(listOf("restore", name), context(ConfirmAnswer.Proceed(RestoreConfirm.PHRASE)), out)
        assertEquals("disabled: writes not enabled in this build", restore.leftOrNull()?.message)
        assertEquals("pages=9431,blocks=90000;live", files.store[db])
    }

    @Test
    fun backups_should_RestoreLikeGraphRestoreBackup_When_EnabledAndConfirmed() = runBlocking<Unit> {
        seed()
        val backups = BackupsCommand({ toolkit }, runner, { now })
        assertTrue(backups.run(listOf("restore", name), context(ConfirmAnswer.Proceed(RestoreConfirm.PHRASE)), CapturedOutput()).isRight())
        assertEquals("pages=9402,blocks=88000;snapshot", files.store[db])
    }

    @Test
    fun backups_should_SayThereAreNone_When_NothingWasBackedUp() = runBlocking<Unit> {
        val out = CapturedOutput()
        BackupsCommand({ toolkit }, runner, { now }).run(emptyList(), ConsoleContext(ConsoleRegistry(emptyList())), out)
        assertTrue(out.lines.single().startsWith("No backups yet"), out.lines.toString())
    }

    @Test
    fun command_should_BeUnavailable_When_ThereIsNoDatabaseFile() = runBlocking<Unit> {
        val unavailable = GraphRestoreCommand(RestoreRunner({ null }, log, { true })) { true }
        assertEquals("no database file on this platform", unavailable.disabledReason)
        val result = unavailable.run(listOf("undo-restore"), context(ConfirmAnswer.Cancel), CapturedOutput())
        assertEquals("disabled: no database file on this platform", result.leftOrNull()?.message)
        assertFalse(BackupsCommand({ null }, RestoreRunner({ null }, log, { true })).disabledReason == null)
    }

    @Test
    fun age_should_UseTheLargestWholeUnit() {
        assertEquals("just now", RestoreConfirm.age(59_999L))
        assertEquals("1 minute ago", RestoreConfirm.age(60_000L))
        assertEquals("59 minutes ago", RestoreConfirm.age(59 * 60_000L))
        assertEquals("1 hour ago", RestoreConfirm.age(60 * 60_000L))
        assertEquals("23 hours ago", RestoreConfirm.age(23 * 60 * 60_000L))
        assertEquals("1 day ago", RestoreConfirm.age(day))
        assertEquals("3 days ago", RestoreConfirm.age(3 * day + 5))
    }
}
