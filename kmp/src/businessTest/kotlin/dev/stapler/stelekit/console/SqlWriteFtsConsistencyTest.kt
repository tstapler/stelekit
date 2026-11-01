// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import dev.stapler.stelekit.console.commands.SqlWriteCommand
import dev.stapler.stelekit.console.commands.SqlWriteGuard
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Task 3.3g (pitfalls B4): a raw `sql!` write on `blocks` must not desync `blocks_fts`. The decision
 * recorded in ADR-006 addendum 5: UPDATE and DELETE go through the existing triggers and stay in sync;
 * REPLACE (including `INSERT OR REPLACE`) on `blocks`/`pages` is rejected before it runs, because the
 * engine removes the replaced row without firing the delete trigger.
 */
class SqlWriteFtsConsistencyTest {
    private fun SqlWriteFixture.hits(word: String) = scalar("SELECT COUNT(*) FROM blocks_fts WHERE blocks_fts MATCH '$word'")

    private fun SqlWriteFixture.run(sql: String): Pair<ConsoleError?, List<String>> {
        val out = CapturedOutput()
        val cmd = SqlWriteCommand({ writer }, { backup }, ConsoleWriteLog(), writesEnabled = { true })
        val ctx = ConsoleContext(ConsoleRegistry(emptyList()), confirmer = ConsoleConfirmer { ConfirmAnswer.Proceed() })
        return runBlocking { cmd.run(listOf(sql), ctx, out) }.leftOrNull() to out.lines
    }

    @Test
    fun update_should_KeepFtsInSync_When_BlockContentChangedThroughSqlBang() {
        SqlWriteFixture(pageCount = 1).use { f ->
            f.addBlock("b1", "page-0", "zebra crossing")
            assertEquals(1, f.hits("zebra"))
            val (error, _) = f.run("update blocks set content = 'quokka crossing' where uuid = 'b1'")
            assertEquals(null, error)
            assertEquals(1, f.hits("quokka"), "the new content is searchable")
            assertEquals(0, f.hits("zebra"), "no stale index row for the old content")
        }
    }

    @Test
    fun delete_should_KeepFtsInSync_When_BlockDeletedThroughSqlBang() {
        SqlWriteFixture(pageCount = 1).use { f ->
            f.addBlock("b1", "page-0", "zebra crossing")
            val (error, _) = f.run("delete from blocks where uuid = 'b1'")
            assertEquals(null, error)
            assertEquals(0, f.hits("zebra"))
        }
    }

    @Test
    fun insertOrReplace_should_BeRejectedBeforeItRuns_When_TargetIsBlocks() {
        SqlWriteFixture(pageCount = 1).use { f ->
            f.addBlock("b1", "page-0", "zebra crossing")
            val (error, lines) = f.run("insert or replace into blocks(uuid,page_uuid,content,position,created_at,updated_at) values ('b1','page-0','quokka','a0',1,1)")
            assertIs<ConsoleError.Denied>(error)
            assertTrue(error.reason.contains("stale full-text"), error.reason)
            assertEquals(1, f.hits("zebra"), "the rejected statement changed nothing")
            assertEquals(0, f.hits("quokka"))
            assertEquals(1, f.scalar("SELECT COUNT(*) FROM blocks"))
            assertTrue(lines.none { it.startsWith("Backup:") }, "no backup for a rejected statement")
        }
    }

    @Test
    fun engine_should_LeaveStaleIndexRow_When_ReplaceRunsWithoutTheGuard() {
        SqlWriteFixture(pageCount = 1).use { f ->
            f.addBlock("b1", "page-0", "zebra crossing")
            // The root cause the guard exists for: REPLACE removes the old row without firing blocks_ad.
            f.exec("INSERT OR REPLACE INTO blocks(uuid,page_uuid,content,position,created_at,updated_at) VALUES ('b1','page-0','quokka','a0',1,1)")
            assertEquals(1, f.scalar("SELECT COUNT(*) FROM blocks"))
            assertEquals(1, f.hits("zebra"), "a stale row for content that no longer exists")
            val verdict = SqlWriteGuard.check("insert or replace into blocks(uuid,page_uuid,content,position,created_at,updated_at) values ('b1','page-0','quokka','a0',1,1)")
            assertIs<SqlWriteGuard.Verdict.Denied>(verdict)
        }
    }
}
