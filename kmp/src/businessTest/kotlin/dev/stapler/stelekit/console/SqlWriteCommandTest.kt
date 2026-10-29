// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import dev.stapler.stelekit.console.commands.DiagCommand
import dev.stapler.stelekit.console.commands.HelpCommand
import dev.stapler.stelekit.console.commands.SqlCommand
import dev.stapler.stelekit.console.commands.SqlWriteCommand
import dev.stapler.stelekit.db.BackupEngine
import dev.stapler.stelekit.db.BackupFileInfo
import dev.stapler.stelekit.db.BackupFiles
import dev.stapler.stelekit.db.ConsoleBackup
import dev.stapler.stelekit.db.ReadHandleBackupEngine
import dev.stapler.stelekit.db.platformBackupFiles
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SqlWriteCommandTest {
    private val requests = mutableListOf<ConfirmRequest>()

    private fun context(answer: (ConfirmRequest) -> ConfirmAnswer = { ConfirmAnswer.Proceed() }) =
        ConsoleContext(ConsoleRegistry(emptyList()), confirmer = ConsoleConfirmer { r -> requests += r; answer(r) })

    private fun command(f: SqlWriteFixture, log: ConsoleWriteLog = ConsoleWriteLog(), backup: ConsoleBackup = f.backup, enabled: Boolean = true) =
        SqlWriteCommand({ f.writer }, { backup }, log, writesEnabled = { enabled })

    private fun SqlWriteCommand.runSql(sql: String, ctx: ConsoleContext, out: CapturedOutput = CapturedOutput()) =
        runBlocking { run(listOf(sql), ctx, out) }

    @Test
    fun plainSql_should_ReturnTheSingleDeniedString_When_WriteSentWithoutBang() = runBlocking<Unit> {
        SqlWriteFixture(pageCount = 2).use { f ->
            val reader = f.base.reader()
            val sql = SqlCommand { reader }
            listOf("update pages set is_favorite=1", "insert into pages(uuid,name,created_at,updated_at) values ('x','y',1,1)", "delete from pages").forEach { statement ->
                val result = sql.run(listOf(statement), context(), CapturedOutput())
                assertEquals("Denied: plain sql is read-only. Use sql! for writes.", result.leftOrNull()?.message, statement)
            }
            assertEquals(2, f.scalar("SELECT COUNT(*) FROM pages"))
            assertEquals(0, f.scalar("SELECT COUNT(*) FROM pages WHERE is_favorite = 1"))
            reader.close()
        }
    }

    @Test
    fun run_should_BeDisabledAndRunNothing_When_WritesNotEnabledInThisBuild() = runBlocking<Unit> {
        SqlWriteFixture(pageCount = 2).use { f ->
            val cmd = command(f, enabled = false)
            assertEquals("writes not enabled in this build", cmd.disabledReason)
            val result = cmd.run(listOf("update pages set is_favorite=1"), context(), CapturedOutput())
            assertEquals("disabled: writes not enabled in this build", result.leftOrNull()?.message)
            assertTrue(requests.isEmpty(), "no confirm may open when the write path is off")
            assertEquals(0, f.scalar("SELECT COUNT(*) FROM pages WHERE is_favorite = 1"))
            assertTrue(f.backupFiles().isEmpty())
            val help = CapturedOutput()
            HelpCommand().run(emptyList(), ConsoleContext(ConsoleRegistry(listOf(cmd))), help)
            assertTrue(help.lines.any { it.startsWith("sql!") && it.contains("disabled: writes not enabled in this build") }, help.lines.toString())
        }
    }

    @Test
    fun run_should_DenyWithoutConfirmOrBackup_When_HardDeniedStatement() = runBlocking<Unit> {
        SqlWriteFixture(pageCount = 1).use { f ->
            val result = command(f).runSql("drop table pages", context())
            assertIs<ConsoleError.Denied>(result.leftOrNull())
            assertTrue(requests.isEmpty())
            assertTrue(f.backupFiles().isEmpty())
            assertEquals(1, f.scalar("SELECT COUNT(*) FROM pages"))
        }
    }

    @Test
    fun run_should_ChangeNothingAndTakeNoBackup_When_OwnerCancelsTheConfirm() = runBlocking<Unit> {
        SqlWriteFixture(pageCount = 3).use { f ->
            val out = CapturedOutput()
            val result = command(f).runSql("update pages set is_favorite=1 where uuid = 'page-1'", context { ConfirmAnswer.Cancel }, out)
            assertTrue(result.isRight())
            assertTrue(out.lines.any { it.startsWith("Cancelled") }, out.lines.toString())
            assertEquals(0, f.scalar("SELECT COUNT(*) FROM pages WHERE is_favorite = 1"))
            assertTrue(f.backupFiles().isEmpty(), "a cancelled write never takes a backup")
            assertEquals(ConsoleMode.ReadOnly, f.writer.mode.value)
        }
    }

    @Test
    fun run_should_ShowStatementCountAndBackupAndDefaultToStandard_When_SmallWrite() = runBlocking<Unit> {
        SqlWriteFixture(pageCount = 30).use { f ->
            command(f).runSql("update pages set is_favorite=1 where uuid in ('page-1','page-2')", context { ConfirmAnswer.Cancel })
            val r = requests.single()
            assertEquals("update pages set is_favorite=1 where uuid in ('page-1','page-2')", r.statement)
            assertEquals(ConfirmTier.Standard, r.tier)
            assertEquals("Update 2 rows", r.primaryLabel)
            assertTrue(r.details.any { it.startsWith("2 rows will change in pages") }, r.details.toString())
            assertTrue(r.details.any { it.contains("backup") && it.contains("console-backups") }, r.details.toString())
            assertTrue(r.details.any { it.contains("not changed") && it.contains("markdown") }, r.details.toString())
        }
    }

    @Test
    fun run_should_TakeVerifiedBackupBeforeTheRowsChange_When_FirstWriteOfSession() = runBlocking<Unit> {
        SqlWriteFixture(pageCount = 30).use { f ->
            var nameAtSnapshot: String? = null
            val engine = object : BackupEngine {
                private val real = ReadHandleBackupEngine(f.base.url)
                override suspend fun vacuumInto(target: String) {
                    nameAtSnapshot = f.text("SELECT name FROM pages WHERE uuid = 'page-1'")
                    real.vacuumInto(target)
                }
                override suspend fun integrityCheck(path: String) = real.integrityCheck(path)
            }
            val backup = ConsoleBackup(f.base.dbFile.path, platformBackupFiles()!!, engine)
            val out = CapturedOutput()
            val result = command(f, backup = backup).runSql("update pages set name = 'Renamed' where uuid = 'page-1'", context(), out)
            assertTrue(result.isRight(), result.toString())
            assertEquals("Page 1", nameAtSnapshot, "the snapshot must be taken before UPDATE runs")
            assertEquals("Renamed", f.text("SELECT name FROM pages WHERE uuid = 'page-1'"))
            val snapshot = f.backupFiles().single()
            assertTrue(out.lines.any { it == "Backup: ${snapshot.path}" }, out.lines.toString())
            assertTrue(out.lines.any { it == "OK: 1 rows changed in pages. DB-only, not written to markdown." }, out.lines.toString())
            assertEquals(listOf("ok"), ReadHandleBackupEngine(f.base.url).integrityCheck(snapshot.path))
        }
    }

    @Test
    fun run_should_BackUpOnlyFirstWriteAndDestructiveWrites_When_ThreeWritesFollow() = runBlocking<Unit> {
        SqlWriteFixture(pageCount = 30).use { f ->
            val cmd = command(f)
            cmd.runSql("update pages set is_favorite=1 where uuid = 'page-1'", context())
            assertEquals(1, f.backupFiles().size)
            cmd.runSql("insert into pages(uuid,name,created_at,updated_at) values ('n1','New',1,1)", context())
            assertEquals(1, f.backupFiles().size, "an INSERT within 10 minutes reuses the backup")
            cmd.runSql("delete from pages where uuid = 'n1'", context())
            assertEquals(2, f.backupFiles().size, "a DELETE gets a fresh backup")
        }
    }

    @Test
    fun run_should_RequireExactTypedCount_When_MoreThanHalfOfTheTableChanges() = runBlocking<Unit> {
        SqlWriteFixture(pageCount = 12).use { f ->
            val cmd = command(f)
            val wrong = cmd.runSql("update pages set is_favorite=1", context { ConfirmAnswer.Proceed("11") })
            assertTrue(wrong.isRight())
            assertEquals(ConfirmTier.TypedCount(12), requests.single().tier)
            assertTrue(requests.single().details.any { it == "This changes 12 of 12 rows in pages." }, requests.single().details.toString())
            assertEquals(0, f.scalar("SELECT COUNT(*) FROM pages WHERE is_favorite = 1"), "a wrong typed count must not write")
            assertTrue(f.backupFiles().isEmpty())
            cmd.runSql("update pages set is_favorite=1", context { ConfirmAnswer.Proceed(" 12 ") })
            assertEquals(12, f.scalar("SELECT COUNT(*) FROM pages WHERE is_favorite = 1"))
        }
    }

    @Test
    fun run_should_ListCascadedTablesInTheConfirm_When_PageDeleteReachesBlocks() = runBlocking<Unit> {
        SqlWriteFixture(pageCount = 30).use { f ->
            f.addBlock("b1", "page-1", "x")
            f.addBlock("b2", "page-1", "y")
            command(f).runSql("delete from pages where uuid = 'page-1'", context { ConfirmAnswer.Cancel })
            val r = requests.single()
            assertIs<ConfirmTier.TypedCount>(r.tier)
            assertTrue(r.details.any { it.startsWith("Also changes blocks: -2 rows") }, r.details.toString())
        }
    }

    @Test
    fun run_should_BlockTheWriteWithTheBackupFailureCopy_When_NotEnoughFreeSpace() = runBlocking<Unit> {
        SqlWriteFixture(pageCount = 3).use { f ->
            val tight = object : BackupFiles by platformBackupFiles()!! {
                override fun usableSpace(path: String): Long = 1
                override fun list(dir: String): List<BackupFileInfo> = emptyList()
            }
            val backup = ConsoleBackup(f.base.dbFile.path, tight, ReadHandleBackupEngine(f.base.url))
            val result = command(f, backup = backup).runSql("update pages set is_favorite=1 where uuid = 'page-1'", context())
            assertTrue(result.leftOrNull()?.message?.startsWith("Backup failed: not enough free space") == true, result.toString())
            assertTrue(result.leftOrNull()?.message?.endsWith("Nothing was changed.") == true)
            assertEquals(0, f.scalar("SELECT COUNT(*) FROM pages WHERE is_favorite = 1"))
            assertTrue(requests.isEmpty(), "the confirm must not open for a write that cannot be backed up")
        }
    }

    @Test
    fun run_should_BlockTheWrite_When_IntegrityCheckOfTheSnapshotFails() = runBlocking<Unit> {
        SqlWriteFixture(pageCount = 3).use { f ->
            val real = ReadHandleBackupEngine(f.base.url)
            val bad = object : BackupEngine {
                override suspend fun vacuumInto(target: String) = real.vacuumInto(target)
                override suspend fun integrityCheck(path: String) = listOf("*** in database main ***")
            }
            val backup = ConsoleBackup(f.base.dbFile.path, platformBackupFiles()!!, bad)
            val result = command(f, backup = backup).runSql("update pages set is_favorite=1 where uuid = 'page-1'", context())
            assertEquals("Backup failed: integrity_check reported *** in database main ***. Nothing was changed.", result.leftOrNull()?.message)
            assertEquals(0, f.scalar("SELECT COUNT(*) FROM pages WHERE is_favorite = 1"))
            assertTrue(f.backupFiles().isEmpty(), "a failed snapshot is removed")
        }
    }

    @Test
    fun run_should_ExplainAndKeepBackup_When_RowCountChangedAfterConfirm() = runBlocking<Unit> {
        // The dry run sees 2 rows; the confirm callback makes 20 more match before the real write runs.
        SqlWriteFixture(pageCount = 40).use { f ->
            val ctx = context {
                (10..29).forEach { f.exec("UPDATE pages SET name = 'Hit $it' WHERE uuid = 'page-$it'") }
                ConfirmAnswer.Proceed()
            }
            val result = command(f).runSql("update pages set is_favorite=1 where name like 'Hit %' or uuid in ('page-0','page-1')", ctx)
            val message = result.leftOrNull()?.message.orEmpty()
            assertEquals(
                "More rows would change than you confirmed (22 now, 2 when you confirmed). Nothing was changed. Run it again to re-confirm. The backup that was taken is kept.",
                message,
            )
            assertEquals(0, f.scalar("SELECT COUNT(*) FROM pages WHERE is_favorite = 1"))
            assertEquals(1, f.backupFiles().size, "the backup stays after a stale rollback")
        }
    }

    @Test
    fun diag_should_ListConfirmedWrites_When_WriteSucceeded() = runBlocking<Unit> {
        SqlWriteFixture(pageCount = 5).use { f ->
            val log = ConsoleWriteLog()
            command(f, log).runSql("update pages set is_favorite=1 where uuid = 'page-1'", context())
            assertEquals(1, log.entries.size)
            val out = CapturedOutput()
            DiagCommand(log) { "REPORT" }.run(emptyList(), context(), out)
            assertEquals("REPORT", out.lines.first())
            assertTrue(out.lines.any { it.contains("Console writes this session") })
            assertTrue(out.lines.any { it.contains("UPDATE pages: 1 rows") }, out.lines.toString())
            val quiet = CapturedOutput()
            DiagCommand(ConsoleWriteLog()) { "REPORT" }.run(emptyList(), context(), quiet)
            assertEquals(listOf("REPORT"), quiet.lines)
        }
    }

    @Test
    fun run_should_ReportNoWriterOrUsage_When_PlatformHasNoWritableDatabaseOrNoArgs() = runBlocking<Unit> {
        val cmd = SqlWriteCommand({ null }, { null }, ConsoleWriteLog(), writesEnabled = { true })
        assertEquals("no writable database on this platform", cmd.disabledReason)
        assertIs<ConsoleError.Unsupported>(cmd.run(listOf("update pages set a=1"), context(), CapturedOutput()).leftOrNull())
        assertIs<ConsoleError.Usage>(cmd.run(emptyList(), context(), CapturedOutput()).leftOrNull())
    }
}
