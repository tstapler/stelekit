// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.console.ConfirmDecision
import dev.stapler.stelekit.console.ConfirmPolicy
import dev.stapler.stelekit.console.ConsoleMode
import dev.stapler.stelekit.console.DryRunMeasurement
import dev.stapler.stelekit.console.TableDelta
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** What the console's `sql!` needs from the write path, so commands depend on this and not on the actor. */
interface SqlWriteView {
    val mode: StateFlow<ConsoleMode>

    /** Runs [block] in [ConsoleMode.GuardedWrite] and restores [ConsoleMode.ReadOnly] however it ends. */
    suspend fun <T> guarded(block: suspend () -> T): T

    /** Executes [sql] and rolls it back, returning what it would have changed. */
    suspend fun dryRun(sql: String, targetTable: String): DryRunResult

    /** Executes [sql] for real, or rolls back when it no longer matches [confirmed]. */
    suspend fun write(sql: String, targetTable: String, targetRows: Long?, confirmed: ConfirmDecision.Confirm): WriteResult
}

sealed interface DryRunResult {
    data class Measured(val measurement: DryRunMeasurement) : DryRunResult
    data object TimedOut : DryRunResult
    data class Failed(val engineMessage: String) : DryRunResult
}

sealed interface WriteResult {
    data class Done(val changes: Long) : WriteResult

    /** Rolled back before commit: [now] rows would change, [confirmed] were announced. */
    data class Stale(val now: Long, val confirmed: Long) : WriteResult
    data class Failed(val engineMessage: String) : WriteResult
}

/**
 * Runs one `sql!` statement through [DatabaseWriteActor] (so the single writer serialises it with app
 * writes), twice: first as a dry run that always ends by throwing [DryRunRollback], then for real.
 *
 * [execute] is `actor.execute` (or a direct call in tests); the dry run passes `invalidate = false`
 * because nothing it does survives. The dry run's deadline bounds how long the console waits: a statement
 * the engine is already stepping cannot be interrupted through the SQLDelight driver, so on timeout the
 * console refuses to proceed and the statement still finishes and rolls back inside the actor.
 */
class SqlConsoleWriter(
    private val queries: RestrictedDatabaseQueries,
    private val execute: suspend (invalidate: Boolean, op: suspend () -> Either<DomainError, Unit>) -> Either<DomainError, Unit>,
    private val deadlineMs: Long = DEFAULT_DEADLINE_MS,
) : SqlWriteView {
    private val _mode = MutableStateFlow(ConsoleMode.ReadOnly)
    override val mode: StateFlow<ConsoleMode> = _mode.asStateFlow()

    private object DryRunRollback : RuntimeException("dry run rollback")

    private class StaleWrite(val now: Long, val confirmed: Long) : RuntimeException("stale confirm")

    override suspend fun <T> guarded(block: suspend () -> T): T {
        _mode.value = ConsoleMode.GuardedWrite
        try {
            return block()
        } finally {
            _mode.value = ConsoleMode.ReadOnly
        }
    }

    override suspend fun dryRun(sql: String, targetTable: String): DryRunResult {
        var result: DryRunResult = DryRunResult.Failed("dry run did not run")
        val finished = withTimeoutOrNull(deadlineMs) {
            execute(false) {
                result = try {
                    measureAndRollBack(sql, targetTable)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DryRunResult.Failed(e.message ?: e::class.simpleName.orEmpty())
                }
                Unit.right()
            }
        }
        return if (finished == null) DryRunResult.TimedOut else result
    }

    private suspend fun measureAndRollBack(sql: String, targetTable: String): DryRunResult {
        var measured: DryRunResult? = null
        try {
            rawExec { session ->
                val before = session.rowCounts()
                val counts = session.exec(sql)
                val after = session.rowCounts()
                val others = (before.keys + after.keys).filter { it != targetTable }
                    .map { TableDelta(it, (after[it] ?: NONE) - (before[it] ?: NONE)) }
                    .filter { it.delta != NONE }
                measured = DryRunResult.Measured(
                    DryRunMeasurement(targetTable, before[targetTable], counts.changes, counts.totalChanges, others),
                )
                throw DryRunRollback
            }
        } catch (e: DryRunRollback) {
            // The transaction rolled back as intended.
        }
        return checkNotNull(measured) { "transaction ended without measuring" }
    }

    override suspend fun write(sql: String, targetTable: String, targetRows: Long?, confirmed: ConfirmDecision.Confirm): WriteResult {
        var result: WriteResult = WriteResult.Failed("write did not run")
        val outcome = execute(true) {
            result = try {
                runConfirmed(sql, targetTable, targetRows, confirmed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: StaleWrite) {
                WriteResult.Stale(e.now, e.confirmed)
            } catch (e: Exception) {
                WriteResult.Failed(e.message ?: e::class.simpleName.orEmpty())
            }
            Unit.right()
        }
        return outcome.fold({ WriteResult.Failed(it.message) }, { result })
    }

    private suspend fun runConfirmed(sql: String, targetTable: String, targetRows: Long?, confirmed: ConfirmDecision.Confirm): WriteResult {
        var done = NONE
        rawExec { session ->
            val counts = session.exec(sql)
            if (ConfirmPolicy.isStale(confirmed, targetTable, targetRows, counts.changes, counts.totalChanges)) {
                throw StaleWrite(ConfirmPolicy.actualCount(confirmed, counts.changes, counts.totalChanges), confirmed.count)
            }
            done = counts.changes
        }
        return WriteResult.Done(done)
    }

    private interface RawSession {
        fun exec(sql: String): RestrictedDatabaseQueries.RawWriteCounts
        fun rowCounts(): Map<String, Long>
    }

    /**
     * The single site that sends owner-typed SQL to the database, inside one actor-owned transaction;
     * `DirectSqlWriteOptInAllowlistTest` pins it.
     */
    @OptIn(DirectSqlWrite::class)
    private suspend fun rawExec(body: suspend (RawSession) -> Unit) {
        queries.transaction {
            body(object : RawSession {
                override fun exec(sql: String) = queries.rawExecWithCounts(sql)
                override fun rowCounts() = queries.rawTableRowCounts()
            })
        }
    }

    companion object {
        const val DEFAULT_DEADLINE_MS = 10_000L
        private const val NONE = 0L
    }
}
