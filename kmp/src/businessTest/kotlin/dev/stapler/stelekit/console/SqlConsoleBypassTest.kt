// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import dev.stapler.stelekit.console.views.SqlConsoleError
import dev.stapler.stelekit.console.views.SqlEnd
import dev.stapler.stelekit.console.views.SqlResultBuffer
import dev.stapler.stelekit.security.Redactor
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Story 3.2 acceptance gate: plain `sql` cannot change the database. Runs against a real file DB
 * through the real read-only handle, not a fake. These tests use `runBlocking` (not `runTest`)
 * because the engine runs on real threads and the 10 s deadline must elapse in real time.
 */
class SqlConsoleBypassTest {
    private val writeDenied = "Denied: plain sql is read-only. Use sql! for writes."

    private fun mutations(dir: File): List<String> = listOf(
        "UPDATE pages SET name='x'",
        "DELETE FROM blocks",
        "WITH c AS (SELECT 1) DELETE FROM pages",
        "INSERT INTO pages(uuid,name,created_at,updated_at) VALUES('u','n',1,1) RETURNING uuid",
        "SELECT 1; DELETE FROM pages",
        "PRAGMA writable_schema=1",
        "PRAGMA query_only=0",
        "PRAGMA journal_mode=DELETE",
        "ATTACH DATABASE '${dir.resolve("x.db").absolutePath}' AS x",
        "VACUUM INTO '${dir.resolve("y.db").absolutePath}'",
        "SELECT load_extension('x')",
        "CREATE TABLE t(a)",
        "DROP TABLE pages",
        "REPLACE INTO pages(uuid,name,created_at,updated_at) VALUES('u','n',1,1)",
        "/**/dElEtE/**/ FROM pages",
    )

    @Test
    fun sqlRead_should_ReturnGridWithHeaderAndThreeRows_When_ThreePageFixture() = runBlocking<Unit> {
        SqlConsoleFixture().use { fx ->
            fx.reader().using { reader ->
                val out = CapturedOutput()
                val result = runSql(reader, "select name from pages order by name", out)
                assertTrue(result.isRight(), "$result")
                assertEquals(listOf("name", "----", "Page 0", "Page 1", "Page 2"), out.lines.dropLast(1))
                assertTrue(out.lines.last().startsWith("OK: 3 rows"), out.lines.last())
            }
        }
    }

    @Test
    fun bypassSuite_should_RejectEveryMutatingStatementAndKeepRowCounts_When_RunThroughSql() = runBlocking<Unit> {
        SqlConsoleFixture().use { fx ->
            fx.reader().using { reader ->
                val before = fx.tableCounts()
                val schemaBefore = fx.schemaObjectCount()
                for (statement in mutations(fx.dir)) {
                    val result = runSql(reader, statement)
                    val error = result.leftOrNull()
                    assertTrue(error != null, "statement was accepted: $statement")
                    assertEquals(before, fx.tableCounts(), "row counts changed after: $statement")
                }
                assertEquals(schemaBefore, fx.schemaObjectCount())
                assertFalse(fx.dir.resolve("x.db").exists() || fx.dir.resolve("y.db").exists(), "a side file was created")
                // DML/DDL carry exactly the one agreed string.
                for (statement in listOf("UPDATE pages SET name='x'", "/**/dElEtE/**/ FROM pages", "CREATE TABLE t(a)", "DROP TABLE pages")) {
                    assertEquals(writeDenied, runSql(reader, statement).leftOrNull()?.message, statement)
                }
                // A write hidden behind a CTE passes the verb allowlist, so the engine must be the one to refuse it.
                assertEquals(writeDenied, runSql(reader, "WITH c AS (SELECT 1) DELETE FROM pages").leftOrNull()?.message)
            }
        }
    }

    @Test
    fun engineAlone_should_RejectDmlAndDdl_When_VerbGuardIsOff() = runBlocking<Unit> {
        SqlConsoleFixture().use { fx ->
            fx.reader(useStatementGuard = false).using { reader ->
                val before = fx.tableCounts()
                for (statement in mutations(fx.dir).filter { it.substringBefore(' ').lowercase().trim('/') in setOf("update", "delete", "insert", "replace", "create", "drop") } +
                    "WITH c AS (SELECT 1) DELETE FROM pages") {
                    val result = reader.query(statement, SqlResultBuffer(500))
                    assertIs<SqlConsoleError.WriteRefused>(result.leftOrNull(), "engine accepted: $statement -> $result")
                }
                assertEquals(before, fx.tableCounts())
            }
        }
    }

    // Documents why SqlStatementGuard exists: on a read-only handle the engine alone does not stop these.
    @Test
    fun engineAlone_should_NotStopVacuumIntoOrPragmaAssignment_WhichIsWhyTheVerbGuardExists() = runBlocking<Unit> {
        SqlConsoleFixture().use { fx ->
            fx.reader(useStatementGuard = false).using { reader ->
                val copy = fx.dir.resolve("copy.db")
                val vacuum = reader.query("VACUUM INTO '${copy.absolutePath}'", SqlResultBuffer(500))
                val pragma = reader.query("PRAGMA query_only=0", SqlResultBuffer(500))
                println("ENGINE-ALONE vacuum_into=$vacuum file_created=${copy.exists()} pragma_assign=$pragma")
                assertTrue(copy.exists() || vacuum.isLeft(), "unexpected state")
                assertTrue(copy.exists(), "if the engine refuses VACUUM INTO now, the allowlist can be narrowed")
                assertTrue(pragma.isRight(), "if the engine reports PRAGMA assignments now, update the guard rationale")
            }
            // And the guard (default) blocks both.
            fx.reader().using { reader ->
                assertIs<SqlConsoleError.StatementNotAllowed>(reader.query("VACUUM INTO '${fx.dir.resolve("g.db").absolutePath}'", SqlResultBuffer(5)).leftOrNull())
                assertIs<SqlConsoleError.StatementNotAllowed>(reader.query("PRAGMA query_only=0", SqlResultBuffer(5)).leftOrNull())
                assertFalse(fx.dir.resolve("g.db").exists())
            }
        }
    }

    @Test
    fun generatedMutations_should_NeverChangeAnyTableRowCount_When_WrappedInCtesCommentsQuotingAndCase() = runBlocking<Unit> {
        val bodies = listOf(
            "UPDATE pages SET name='x'", "DELETE FROM blocks", "DELETE FROM pages",
            "INSERT INTO pages(uuid,name,created_at,updated_at) VALUES('u','n',1,1)",
            "REPLACE INTO pages(uuid,name,created_at,updated_at) VALUES('u2','n',1,1)",
            "DROP TABLE pages", "CREATE TABLE t(a)", "ALTER TABLE pages ADD COLUMN zz TEXT",
            "UPDATE blocks SET content='zz'",
        )
        val prefixes = listOf("", "/**/", "-- c\n", " \n\t", "/* x */ -- y\n", "WITH c AS (SELECT 1) ", "WITH c(a) AS (VALUES(1)) /* k */ ", "SELECT 1; ", ";")
        val suffixes = listOf("", ";", " -- tail", " /* tail */ ;")
        SqlConsoleFixture().use { fx ->
            val readers = listOf(fx.reader(), fx.reader(useStatementGuard = false))
            try {
                val before = fx.tableCounts()
                val schemaBefore = fx.schemaObjectCount()
                checkAll(150, Arb.element(bodies), Arb.element(prefixes), Arb.element(suffixes), Arb.boolean(), Arb.int(0..1)) { body, prefix, suffix, shout, readerIndex ->
                    var statement = prefix + body + suffix
                    // Mixed case, kept deterministic per input so a failure reproduces.
                    statement = statement.mapIndexed { i, c -> if ((i + body.length) % 2 == 0 == shout) c.uppercaseChar() else c.lowercaseChar() }.joinToString("")
                    runSql(readers[readerIndex], statement)
                    assertEquals(before, fx.tableCounts(), "row counts changed after: $statement")
                    assertEquals(schemaBefore, fx.schemaObjectCount(), "schema changed after: $statement")
                }
            } finally {
                readers.forEach { it.close() }
            }
        }
    }

    @Test
    fun plainSql_should_UseNoTableClassifier_And_LetSelectContainingCreateTableLiteralRun() = runBlocking<Unit> {
        SqlConsoleFixture().use { fx ->
            fx.reader().using { reader ->
                val out = CapturedOutput()
                assertTrue(runSql(reader, "select 'create table x' as s", out).isRight())
                assertTrue(out.lines.contains("create table x"), "$out")
            }
        }
    }

    @Test
    fun selectGitConfig_should_ReturnAliasRowsThroughRedactorAndNoDenialString_When_NoAuthorizerOnDriver() = runBlocking<Unit> {
        SqlConsoleFixture().use { fx ->
            val tokenShapedAlias = "ghp_" + "a1B2".repeat(9)
            fx.app.execute(
                null,
                "INSERT INTO git_config(graph_id, repo_root, https_token_key) VALUES ('g1','/r','$tokenShapedAlias')",
                0,
            ).value
            fx.reader().using { reader ->
                val queries = listOf(
                    "select * from git_config",
                    "with c as (select * from git_config) select * from c",
                    "select https_token_key as x from git_config",
                    "select x from (select https_token_key as x from git_config)",
                    "select a.https_token_key from git_config a join git_config b on a.graph_id = b.graph_id",
                )
                for (q in queries) {
                    val out = CapturedOutput()
                    assertTrue(runSql(reader, q, out).isRight(), q)
                    val text = out.lines.joinToString("\n")
                    assertTrue(text.contains(tokenShapedAlias), "the engine returns the stored alias: $q")
                    assertFalse(text.contains("Denied"), "no denial claim: $q")
                    assertFalse(Redactor.redact(text).contains(tokenShapedAlias), "token-shaped value must be masked by the single Redactor: $q")
                }
            }
        }
    }

    @Test
    fun consoleReader_should_BeSeparateHandle_And_QueryOnlyPragmaMustNotLeakIntoAppWrites() = runBlocking<Unit> {
        SqlConsoleFixture().use { fx ->
            fx.reader(useStatementGuard = false).using { reader ->
                reader.query("PRAGMA query_only=1", SqlResultBuffer(5))
                repeat(24) { fx.insertPage("after-$it", "After $it") } // more than the 8 pooled connections
                assertEquals(3L + 24L, fx.tableCounts().getValue("pages"))
            }
        }
    }

    @Test
    fun close_should_StopRunningStatementPromptly_And_LeaveAppPoolUsable_When_GraphClosesMidQuery() = runBlocking<Unit> {
        SqlConsoleFixture().use { fx ->
            val reader = fx.reader()
            val outcome = kotlinx.coroutines.CompletableDeferred<arrow.core.Either<SqlConsoleError, SqlEnd>>()
            val buffer = SqlResultBuffer(5)
            launch(Dispatchers.Default) { outcome.complete(reader.query(ConsoleBoundedStreamingTest.PARTIAL_THEN_RUNAWAY, buffer)) }
            withTimeout(10_000) { while (buffer.rows.isEmpty()) kotlinx.coroutines.yield() }
            val started = System.nanoTime()
            reader.close()
            val result = withTimeout(5_000) { outcome.await() }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(SqlConsoleError.GraphClosed, result.leftOrNull(), "$result")
            assertTrue(elapsedMs < 2_000, "closing took $elapsedMs ms")
            fx.insertPage("still-writable", "Still writable")
            assertEquals(SqlConsoleError.GraphClosed, reader.query("select 1", SqlResultBuffer(5)).leftOrNull())
        }
    }
}
