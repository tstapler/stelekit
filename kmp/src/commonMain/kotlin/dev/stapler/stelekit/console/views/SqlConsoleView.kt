// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console.views

import arrow.core.Either
import dev.stapler.stelekit.db.SqlRowSink

/** Bounds applied to one console query. Defaults are the Story 3.2 contract (500 rows, 2 KB cells, 10 s). */
data class SqlLimits(
    val maxRows: Int = 500,
    val maxCellChars: Int = 2_048,
    val deadlineMs: Long = 10_000,
)

/** Why a query that started stopped. A query that reads its last row is [Completed]. */
enum class SqlEnd { Completed, RowCap, DeadlineExceeded }

sealed interface SqlConsoleError {
    /** The engine refused to write: the console handle is open read-only. */
    data class WriteRefused(val engineMessage: String) : SqlConsoleError

    /** Rejected before reaching the engine; see `SqlStatementGuard`. */
    data class StatementNotAllowed(val verb: String, val isWrite: Boolean) : SqlConsoleError

    /** No read-only connection on this platform, or the database file cannot be opened for reading. */
    data class Unavailable(val reason: String) : SqlConsoleError

    data object GraphClosed : SqlConsoleError

    data class Failed(val engineMessage: String) : SqlConsoleError
}

/**
 * Rows collected so far for one query. The caller owns it, so rows streamed before a cancel survive
 * the cancellation (the query itself throws `CancellationException`).
 */
class SqlResultBuffer(private val maxRows: Int) : SqlRowSink {
    var columns: List<String> = emptyList()
        private set
    private val collected = ArrayList<List<String?>>()
    val rows: List<List<String?>> get() = collected
    var truncated: Boolean = false
        private set

    override fun columns(names: List<String>) { columns = names }
    override fun hasCapacity(): Boolean = collected.size < maxRows
    override fun row(cells: List<String?>) { collected += cells }
    override fun overflow() { truncated = true }
}

/**
 * What `RepositorySet.sqlConsole` exposes to console commands: one read-only query at a time on a
 * connection the app never shares. Writes are impossible through it (the handle is opened read-only).
 */
interface SqlConsoleView {
    /** Non-null when the verb is listed but cannot run here (no read-only connection on this platform). */
    val unavailableReason: String?

    /**
     * Runs [sql] and streams rows into [into]. Cancelling the calling coroutine stops the running
     * statement in the engine (not just the coroutine), then rethrows. Reaching [SqlLimits.deadlineMs]
     * returns [SqlEnd.DeadlineExceeded] with the rows read so far kept.
     */
    suspend fun query(sql: String, into: SqlResultBuffer, limits: SqlLimits = SqlLimits()): Either<SqlConsoleError, SqlEnd>

    /** Cancels any running statement, then closes the connection. Idempotent. */
    fun close()
}
