// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console.commands

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.console.ConsoleCommand
import dev.stapler.stelekit.console.ConsoleContext
import dev.stapler.stelekit.console.ConsoleError
import dev.stapler.stelekit.console.ConsoleOutput
import dev.stapler.stelekit.console.ResultTable
import dev.stapler.stelekit.console.views.SqlConsoleError
import dev.stapler.stelekit.console.views.SqlConsoleView
import dev.stapler.stelekit.console.views.SqlEnd
import dev.stapler.stelekit.console.views.SqlLimits
import dev.stapler.stelekit.console.views.SqlResultBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

internal const val NO_READ_ONLY_CONNECTION = "no read-only connection on this platform"
private const val WRITE_DENIED = "plain sql is read-only. Use sql! for writes."

/**
 * `sql <statement>`: read-only SQL on the console's own read-only connection. Plain `sql` can never
 * write: the connection is opened read-only, and a leading-verb allowlist rejects statements the
 * engine would not (see `SqlStatementGuard`).
 *
 * Nothing here denies reading credential-alias columns: no SQLite authorizer is reachable on any
 * driver (Spike 0.3e = NO). Output passes through the session's single `Redactor`.
 *
 * [sqlConsole] is resolved per run because the active graph, and so its connection, can change.
 */
class SqlCommand(
    private val limits: SqlLimits = SqlLimits(),
    private val sqlConsole: () -> SqlConsoleView?,
) : ConsoleCommand {
    override val name = "sql"
    override val summary = "Run a read-only SQL query (500 rows, 10 s limit)"
    override val usage = "sql <statement>"
    override val disabledReason: String? get() = sqlConsole().let { if (it == null) NO_READ_ONLY_CONNECTION else it.unavailableReason }

    override suspend fun run(args: List<String>, context: ConsoleContext, out: ConsoleOutput): Either<ConsoleError, Unit> {
        if (args.isEmpty()) return ConsoleError.Usage("Usage: $usage\n$CREDENTIAL_NOTE").left()
        return SqlRunner.run(sqlConsole(), args.joinToString(" "), out, limits)
    }

    private companion object {
        const val CREDENTIAL_NOTE =
            "Results are redacted by pattern only. Columns such as git_config.https_token_key are not access-restricted; " +
                "they hold keystore aliases, not secret values."
    }
}

/** `tables`: the tables and views in the database. */
class TablesCommand(private val sqlConsole: () -> SqlConsoleView?) : ConsoleCommand {
    override val name = "tables"
    override val summary = "List tables and views"
    override val disabledReason: String? get() = sqlConsole().let { if (it == null) NO_READ_ONLY_CONNECTION else it.unavailableReason }

    override suspend fun run(args: List<String>, context: ConsoleContext, out: ConsoleOutput): Either<ConsoleError, Unit> =
        SqlRunner.run(sqlConsole(), "SELECT name, type FROM sqlite_master WHERE type IN ('table','view') ORDER BY name", out)
}

/** `schema <table>`: the CREATE statement for one table, view or index. */
class SchemaCommand(private val sqlConsole: () -> SqlConsoleView?) : ConsoleCommand {
    override val name = "schema"
    override val summary = "Show the CREATE statement for a table"
    override val usage = "schema <table>"
    override val disabledReason: String? get() = sqlConsole().let { if (it == null) NO_READ_ONLY_CONNECTION else it.unavailableReason }

    override suspend fun run(args: List<String>, context: ConsoleContext, out: ConsoleOutput): Either<ConsoleError, Unit> {
        val table = args.singleOrNull()?.takeIf { IDENTIFIER.matches(it) }
            ?: return ConsoleError.Usage("Usage: $usage (letters, digits and underscores)").left()
        return SqlRunner.run(sqlConsole(), "SELECT sql FROM sqlite_master WHERE name = '$table'", out)
    }

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z0-9_]+")
    }
}

/** Runs one statement and prints it as a text grid with a status footer. Shared by the SQL verbs. */
internal object SqlRunner {
    suspend fun run(console: SqlConsoleView?, sql: String, out: ConsoleOutput, limits: SqlLimits = SqlLimits()): Either<ConsoleError, Unit> {
        if (console == null) return ConsoleError.Unsupported(NO_READ_ONLY_CONNECTION).left()
        val buffer = SqlResultBuffer(limits.maxRows)
        val started = TimeSource.Monotonic.markNow()
        val result = try {
            console.query(sql, buffer, limits)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { render(buffer, sql, explain = true, limits, out) }
            throw e
        }
        val elapsedMs = started.elapsedNow().inWholeMilliseconds
        return result.fold(
            ifLeft = { it.toConsoleError().left() },
            ifRight = { end -> finish(end, buffer, sql, elapsedMs, limits, out) },
        )
    }

    private fun finish(end: SqlEnd, buffer: SqlResultBuffer, sql: String, elapsedMs: Long, limits: SqlLimits, out: ConsoleOutput): Either<ConsoleError, Unit> {
        render(buffer, sql, explain = end != SqlEnd.Completed, limits, out)
        return when (end) {
            SqlEnd.Completed -> out.info("OK: ${buffer.rows.size} rows in $elapsedMs ms").let { Unit.right() }
            SqlEnd.RowCap -> out.info("TRUNCATED at ${limits.maxRows} rows. OK in $elapsedMs ms").let { Unit.right() }
            SqlEnd.DeadlineExceeded -> ConsoleError.Failed("Stopped after ${limits.deadlineMs / 1_000} s. Partial rows kept.").left()
        }
    }

    /** Hands the rows to the output as one grid; [explain] adds the EXPLAIN QUERY PLAN chip for a stopped query. */
    private fun render(buffer: SqlResultBuffer, sql: String, explain: Boolean, limits: SqlLimits, out: ConsoleOutput) {
        if (buffer.columns.isEmpty()) return
        val table = ResultTable(
            columns = buffer.columns,
            rows = buffer.rows.map { row -> row.map { ResultTable.cappedCell(it, limits.maxCellChars) } },
            truncatedAtRows = limits.maxRows.takeIf { buffer.truncated },
            explainSql = if (explain) ResultTable.explainTarget(sql) else null,
        )
        out.table(table)
    }

    private fun SqlConsoleError.toConsoleError(): ConsoleError = when (this) {
        is SqlConsoleError.WriteRefused -> ConsoleError.Denied(WRITE_DENIED)
        is SqlConsoleError.StatementNotAllowed ->
            if (isWrite) ConsoleError.Denied(WRITE_DENIED)
            else ConsoleError.Denied("plain sql accepts SELECT, WITH, VALUES, EXPLAIN and read-only PRAGMA; '$verb' is not allowed.")
        is SqlConsoleError.Unavailable -> ConsoleError.Unsupported(reason)
        SqlConsoleError.GraphClosed -> ConsoleError.Failed("graph closed during command")
        is SqlConsoleError.Failed -> ConsoleError.Failed(engineMessage)
    }
}
