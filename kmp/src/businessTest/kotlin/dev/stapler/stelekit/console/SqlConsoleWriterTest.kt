// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import dev.stapler.stelekit.db.DryRunResult
import dev.stapler.stelekit.db.SqlConsoleWriter
import dev.stapler.stelekit.db.WriteResult
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SqlConsoleWriterTest {
    private fun SqlWriteFixture.measured(sql: String, table: String) =
        assertIs<DryRunResult.Measured>(runBlocking { writer.dryRun(sql, table) }).measurement

    private fun confirmFor(m: DryRunMeasurement, nondeterministic: Boolean = false) =
        assertIs<ConfirmDecision.Confirm>(ConfirmPolicy.decide(m, nondeterministic))

    @Test
    fun dryRun_should_CountRowsAndLeaveTableUnchanged_When_UpdateRolledBack() {
        SqlWriteFixture(pageCount = 4).use { f ->
            val m = f.measured("update pages set is_favorite = 1 where name like 'Page %'", "pages")
            assertEquals(4, m.changes)
            assertEquals(4, m.targetRows)
            assertEquals(0, f.scalar("SELECT COUNT(*) FROM pages WHERE is_favorite = 1"), "the dry run must roll back")
        }
    }

    @Test
    fun dryRun_should_EmitNoInvalidation_When_RealWriteEmitsOne() = runTest(UnconfinedTestDispatcher()) {
        SqlWriteFixture(pageCount = 2, actorScope = backgroundScope).use { f ->
            val seen = AtomicInteger()
            backgroundScope.launch { f.actor.blockInvalidations.collect { seen.incrementAndGet() } }
            val m = assertIs<DryRunResult.Measured>(f.writer.dryRun("update pages set is_favorite = 1", "pages")).measurement
            assertEquals(0, seen.get(), "a rolled-back dry run must not tell observers to re-query")
            val done = f.writer.write("update pages set is_favorite = 1", "pages", 2, confirmFor(m))
            assertEquals(WriteResult.Done(2), done)
            assertEquals(1, seen.get(), "the real write goes through the actor and invalidates once")
        }
    }

    @Test
    fun dryRun_should_ListCascadedTablesAndRollBackFtsToo_When_PageDeleteCascades() {
        SqlWriteFixture(pageCount = 2).use { f ->
            f.addBlock("b1", "page-0", "alpha beta")
            f.addBlock("b2", "page-0", "gamma")
            val ftsBefore = f.scalar("SELECT COUNT(*) FROM blocks_fts WHERE blocks_fts MATCH 'alpha'")
            val m = f.measured("delete from pages where uuid = 'page-0'", "pages")
            assertEquals(1, m.changes)
            assertEquals(listOf(TableDelta("blocks", -2)), m.otherTables)
            val c = confirmFor(m)
            assertEquals(ConfirmTier.TypedCount(m.totalChanges), c.tier, "a cascade escalates whatever the percentage")
            assertEquals(2, f.scalar("SELECT COUNT(*) FROM pages"))
            assertEquals(2, f.scalar("SELECT COUNT(*) FROM blocks"))
            assertEquals(ftsBefore, f.scalar("SELECT COUNT(*) FROM blocks_fts WHERE blocks_fts MATCH 'alpha'"))
        }
    }

    @Test
    fun dryRun_should_NotEscalate_When_BlockUpdateOnlyTouchesItsFtsIndex() {
        SqlWriteFixture(pageCount = 1).use { f ->
            f.addBlock("b1", "page-0", "alpha")
            val m = f.measured("update blocks set content = 'omega' where uuid = 'b1'", "blocks")
            assertEquals(1, m.changes)
            assertTrue(m.totalChanges > m.changes, "FTS triggers add to total_changes")
            assertEquals(emptyList(), m.otherTables)
            assertEquals(ConfirmTier.Standard, confirmFor(m).tier)
            assertEquals("alpha", f.text("SELECT content FROM blocks WHERE uuid = 'b1'"))
        }
    }

    @Test
    fun dryRun_should_ReportEngineMessageAndChangeNothing_When_StatementFails() {
        SqlWriteFixture(pageCount = 1).use { f ->
            val result = runBlocking { f.writer.dryRun("insert into pages(uuid,name,created_at,updated_at) values ('page-0','dup',1,1)", "pages") }
            val failed = assertIs<DryRunResult.Failed>(result)
            assertTrue(failed.engineMessage.contains("UNIQUE", ignoreCase = true) || failed.engineMessage.contains("constraint", ignoreCase = true), failed.engineMessage)
            assertEquals(1, f.scalar("SELECT COUNT(*) FROM pages"))
        }
    }

    @Test
    fun dryRun_should_TimeOut_When_ActorDoesNotFinishWithinTheDeadline() = runTest {
        SqlWriteFixture(pageCount = 1).use { f ->
            val slow = SqlConsoleWriter(f.queries, { _, _ -> awaitCancellation() }, deadlineMs = 100)
            assertEquals(DryRunResult.TimedOut, slow.dryRun("update pages set is_favorite = 1", "pages"))
        }
    }

    @Test
    fun write_should_RollBackAndKeepData_When_StandardConfirmNowNeedsTypedCount() = runBlocking<Unit> {
        SqlWriteFixture(pageCount = 12).use { f ->
            val stale = ConfirmDecision.Confirm(ConfirmTier.Standard, count = 2, triggered = false, tables = emptyList())
            val result = f.writer.write("update pages set is_favorite = 1", "pages", 12, stale)
            assertEquals(WriteResult.Stale(now = 12, confirmed = 2), result)
            assertEquals(0, f.scalar("SELECT COUNT(*) FROM pages WHERE is_favorite = 1"))
        }
    }

    @Test
    fun write_should_RollBack_When_TypedCountNoLongerMatches() = runBlocking<Unit> {
        SqlWriteFixture(pageCount = 12).use { f ->
            val confirmed = ConfirmDecision.Confirm(ConfirmTier.TypedCount(5), count = 5, triggered = false, tables = emptyList())
            val result = f.writer.write("update pages set is_favorite = 1", "pages", 12, confirmed)
            assertEquals(WriteResult.Stale(now = 12, confirmed = 5), result)
            assertEquals(0, f.scalar("SELECT COUNT(*) FROM pages WHERE is_favorite = 1"))
        }
    }

    @Test
    fun write_should_ReportEngineFailureAndChangeNothing_When_ConstraintViolated() = runBlocking<Unit> {
        SqlWriteFixture(pageCount = 2).use { f ->
            val confirm = ConfirmDecision.Confirm(ConfirmTier.Standard, 2, false, emptyList())
            val result = f.writer.write("update pages set uuid = 'same'", "pages", 2, confirm)
            assertIs<WriteResult.Failed>(result)
            assertEquals(0, f.scalar("SELECT COUNT(*) FROM pages WHERE uuid = 'same'"))
        }
    }

    @Test
    fun guarded_should_RestoreReadOnly_When_BlockEndsNormallyOrThrowsOrIsCancelled() = runTest {
        SqlWriteFixture(pageCount = 1).use { f ->
            assertEquals(ConsoleMode.ReadOnly, f.writer.mode.value)
            f.writer.guarded { assertEquals(ConsoleMode.GuardedWrite, f.writer.mode.value) }
            assertEquals(ConsoleMode.ReadOnly, f.writer.mode.value)
            runCatching { f.writer.guarded<Unit> { error("boom") } }
            assertEquals(ConsoleMode.ReadOnly, f.writer.mode.value)
            val cancelled = launch { f.writer.guarded<Unit> { awaitCancellation() } }
            runCurrent()
            assertEquals(ConsoleMode.GuardedWrite, f.writer.mode.value)
            cancelled.cancelAndJoin()
            assertEquals(ConsoleMode.ReadOnly, f.writer.mode.value)
        }
    }

    @Test
    fun write_should_NotTouchMarkdownFiles_When_RawWriteCommits() = runBlocking<Unit> {
        SqlWriteFixture(pageCount = 2).use { f ->
            val before = f.strayFiles()
            val confirm = ConfirmDecision.Confirm(ConfirmTier.Standard, 2, false, emptyList())
            assertEquals(WriteResult.Done(2), f.writer.write("update pages set name = name || '!'", "pages", 2, confirm))
            assertEquals(2, f.scalar("SELECT COUNT(*) FROM pages WHERE name LIKE '%!'"))
            assertEquals(before, f.strayFiles(), "a raw write alone writes no markdown or other file")
        }
    }
}
