// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.console.commands.HelpCommand
import dev.stapler.stelekit.console.commands.SchemaCommand
import dev.stapler.stelekit.console.commands.SqlCommand
import dev.stapler.stelekit.console.commands.TablesCommand
import dev.stapler.stelekit.console.views.SqlConsoleError
import dev.stapler.stelekit.console.views.SqlConsoleView
import dev.stapler.stelekit.console.views.SqlEnd
import dev.stapler.stelekit.console.views.SqlLimits
import dev.stapler.stelekit.console.views.SqlResultBuffer
import dev.stapler.stelekit.db.SqlConsoleReader
import dev.stapler.stelekit.db.SqlStatementGuard
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SqlCommandTest {
    private val context = ConsoleContext(ConsoleRegistry(emptyList()))

    private class RecordingView(override val unavailableReason: String? = null) : SqlConsoleView {
        val seen = mutableListOf<String>()
        override suspend fun query(sql: String, into: SqlResultBuffer, limits: SqlLimits): Either<SqlConsoleError, SqlEnd> {
            seen += sql
            return SqlEnd.Completed.right()
        }
        override fun close() = Unit
    }

    @Test
    fun sql_should_BeListedDisabledAndReturnUnsupported_When_NoReadOnlyConnection() = runBlocking<Unit> {
        val sql = SqlCommand { null }
        assertEquals("no read-only connection on this platform", sql.disabledReason)
        assertEquals("sql", sql.name)
        val help = CapturedOutput().also { out ->
            HelpCommand().run(emptyList(), ConsoleContext(ConsoleRegistry(listOf(sql, TablesCommand { null }, SchemaCommand { null }))), out)
        }
        assertTrue(help.lines.any { it.contains("sql") && it.contains("disabled: no read-only connection on this platform") }, help.lines.toString())
        val result = sql.run(listOf("select 1"), context, CapturedOutput())
        assertEquals(ConsoleError.Unsupported("no read-only connection on this platform"), result.leftOrNull())
        assertEquals("disabled: no read-only connection on this platform", result.leftOrNull()?.message)
    }

    @Test
    fun sql_should_BeListedDisabledWithReaderReason_When_InMemoryDatabase() {
        val reader = SqlConsoleReader("jdbc:sqlite::memory:")
        assertEquals("no read-only connection for an in-memory database", SqlCommand { reader }.disabledReason)
    }

    @Test
    fun sql_should_PassStatementUnchanged_When_Args_And_NotNameAnyCredentialTable() = runBlocking<Unit> {
        val view = RecordingView()
        SqlCommand { view }.run(listOf("select", "*", "from", "git_config"), context, CapturedOutput())
        assertEquals(listOf("select * from git_config"), view.seen, "the statement must reach the engine as typed (no table-name classifier)")
    }

    @Test
    fun sql_should_ExplainUsage_And_StateCredentialLimit_When_NoArguments() = runBlocking<Unit> {
        val result = SqlCommand { RecordingView() }.run(emptyList(), context, CapturedOutput())
        val message = assertIs<ConsoleError.Usage>(result.leftOrNull()).message
        assertTrue(message.startsWith("Usage: sql <statement>"))
        assertTrue(message.contains("not access-restricted"), message)
        assertFalse(message.contains("unreadable"), message)
    }

    @Test
    fun schema_should_RejectNonIdentifier_When_NameContainsQuoteOrSpace() = runBlocking<Unit> {
        val view = RecordingView()
        assertIs<ConsoleError.Usage>(SchemaCommand { view }.run(listOf("pages' OR 1=1 --"), context, CapturedOutput()).leftOrNull())
        assertTrue(view.seen.isEmpty())
        SchemaCommand { view }.run(listOf("pages"), context, CapturedOutput())
        assertEquals(listOf("SELECT sql FROM sqlite_master WHERE name = 'pages'"), view.seen)
    }

    @Test
    fun guard_should_AllowReadVerbsAndReadPragmas_And_RejectEverythingElse() {
        val allowed = listOf(
            "select 1", "  SELECT 1", "-- c\nselect 1", "/* a */ /* b */ with c as (select 1) select * from c", "values (1)",
            "explain query plan select 1", "pragma table_info(pages)", "PRAGMA main.index_list(pages)", "pragma page_count", "pragma journal_mode;",
            "select ';' as semi", "select 1; -- trailing comment", "select \"a;b\" from pages;",
        )
        for (s in allowed) assertTrue(SqlStatementGuard.check(s).allowed, s)
        val denied = listOf(
            "", "   ", "-- only a comment", "/* unterminated", ";", "; select 1", "update pages set name='x'", "DELETE FROM pages", "vacuum into '/x'", "attach database 'x' as y",
            "pragma query_only=0", "pragma journal_mode(delete)", "pragma journal_mode = delete", "pragma wal_checkpoint", "pragma table_info(pages); delete from pages",
            "select 1; delete from pages", "select 1; /* c */ select 2", "begin", "detach x", "reindex", "analyze",
        )
        for (s in denied) assertFalse(SqlStatementGuard.check(s).allowed, s)
        assertTrue(SqlStatementGuard.check("/**/dElEtE/**/ FROM pages").isWrite)
        assertFalse(SqlStatementGuard.check("vacuum into '/x'").isWrite)
        assertTrue(SqlStatementGuard.check("select 1; delete from pages").isWrite)
    }
}
