// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.console.views.SqlConsoleError
import dev.stapler.stelekit.console.views.SqlConsoleView
import dev.stapler.stelekit.console.views.SqlEnd
import dev.stapler.stelekit.console.views.SqlLimits
import dev.stapler.stelekit.console.views.SqlResultBuffer
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Engine-enforced read-only SQL for the developer console. Owns one lazily opened [SqlReadHandle]
 * (opened on first use so a user who never opens the console pays nothing) and runs one query at a time.
 *
 * Cancellation and the deadline stop the statement in the engine via [SqlReadHandle.cancel]; neither
 * `Thread.interrupt` nor abandoning and closing the connection stops a running SQLite step (Story 0.3d).
 *
 * @param open null result means this platform has no read-only connection.
 * @param useStatementGuard false only in tests that need to prove the engine rejects writes on its own.
 */
class SqlConsoleReader internal constructor(
    private val open: () -> SqlReadHandle?,
    override val unavailableReason: String? = null,
    private val useStatementGuard: Boolean = true,
) : SqlConsoleView {

    constructor(jdbcUrl: String) : this(
        open = { openSqlReadHandle(jdbcUrl) },
        unavailableReason = if (!isFileDatabase(jdbcUrl)) UNAVAILABLE_NO_FILE_DB else null,
    )

    private val gate = Mutex()
    @Volatile private var handle: SqlReadHandle? = null
    @Volatile private var closed = false

    override suspend fun query(sql: String, into: SqlResultBuffer, limits: SqlLimits): Either<SqlConsoleError, SqlEnd> {
        unavailableReason?.let { return SqlConsoleError.Unavailable(it).left() }
        if (useStatementGuard) {
            val verdict = SqlStatementGuard.check(sql)
            if (!verdict.allowed) return SqlConsoleError.StatementNotAllowed(verdict.verb, verdict.isWrite).left()
        }
        return gate.withLock { runLocked(sql, into, limits) }
    }

    private suspend fun runLocked(sql: String, into: SqlResultBuffer, limits: SqlLimits): Either<SqlConsoleError, SqlEnd> {
        if (closed) return SqlConsoleError.GraphClosed.left()
        val opened = withContext(PlatformDispatcher.DB) { runCatching { handle ?: openHandle() } }
        opened.exceptionOrNull()?.let { return mapFailure(it, deadlineHit = false).map { SqlEnd.Completed } }
        val h = opened.getOrNull() ?: return SqlConsoleError.Unavailable(UNAVAILABLE_NO_READONLY).left()
        h.prepare()
        var deadlineHit = false
        val outcome = coroutineScope {
            val work = async(PlatformDispatcher.DB) { runCatching { h.execute(sql, limits.maxCellChars, into) } }
            val watchdog = launch(PlatformDispatcher.Default) {
                delay(limits.deadlineMs)
                deadlineHit = true
                h.cancel()
            }
            try {
                work.await()
            } catch (e: CancellationException) {
                // work.await() throws as soon as the caller is cancelled, but the blocking step is still running.
                h.cancel()
                withContext(NonCancellable) { work.join() }
                throw e
            } finally {
                watchdog.cancelAndJoin()
            }
        }
        return outcome.fold(
            onSuccess = { (if (into.truncated) SqlEnd.RowCap else SqlEnd.Completed).right() },
            onFailure = { mapFailure(it, deadlineHit) },
        )
    }

    private fun mapFailure(t: Throwable, deadlineHit: Boolean): Either<SqlConsoleError, SqlEnd> {
        if (t is Error) throw t
        val message = t.message.orEmpty()
        return when {
            closed -> SqlConsoleError.GraphClosed.left()
            deadlineHit -> SqlEnd.DeadlineExceeded.right()
            message.contains("readonly", ignoreCase = true) || message.contains("read-only", ignoreCase = true) ->
                SqlConsoleError.WriteRefused(message).left()
            else -> SqlConsoleError.Failed(message.ifEmpty { t::class.simpleName.orEmpty() }).left()
        }
    }

    private fun openHandle(): SqlReadHandle? = open()?.also { handle = it }

    override fun close() {
        closed = true
        val h = handle ?: return
        handle = null
        // close() blocks behind a running step on sqlite-jdbc, so stop the statement first.
        h.cancel()
        h.close()
    }

    companion object {
        const val UNAVAILABLE_NO_READONLY = "no read-only connection on this platform"
        private const val UNAVAILABLE_NO_FILE_DB = "no read-only connection for an in-memory database"

        private fun isFileDatabase(jdbcUrl: String) = jdbcUrl.startsWith("jdbc:sqlite:") && !jdbcUrl.contains(":memory:")
    }
}
