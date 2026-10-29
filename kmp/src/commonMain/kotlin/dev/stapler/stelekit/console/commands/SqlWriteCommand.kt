// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console.commands

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.console.ConfirmDecision
import dev.stapler.stelekit.console.ConfirmPolicy
import dev.stapler.stelekit.console.ConfirmRequest
import dev.stapler.stelekit.console.ConfirmTier
import dev.stapler.stelekit.console.ConsoleBuildFlags
import dev.stapler.stelekit.console.ConsoleCommand
import dev.stapler.stelekit.console.ConsoleContext
import dev.stapler.stelekit.console.ConsoleError
import dev.stapler.stelekit.console.ConsoleOutput
import dev.stapler.stelekit.console.ConsoleWriteLog
import dev.stapler.stelekit.console.satisfies
import dev.stapler.stelekit.db.ConsoleBackup
import dev.stapler.stelekit.db.DryRunResult
import dev.stapler.stelekit.db.SqlWriteView
import dev.stapler.stelekit.db.WriteResult

/**
 * `sql! "<statement>"`: the only console path that writes. In order: hard-deny check, a rolled-back dry
 * run for the row count, the owner's confirm, an automatic backup (a failure blocks the write), then the
 * write itself, which rolls back if it no longer matches what was confirmed. The result is DB-only: no
 * markdown file is written, and `diag` lists the write for the rest of the session.
 *
 * [writer] and [backup] are resolved per run because the active graph can change. Quote the statement:
 * the console tokenizer strips unquoted `'`.
 */
class SqlWriteCommand(
    private val writer: () -> SqlWriteView?,
    private val backup: () -> ConsoleBackup?,
    private val writeLog: ConsoleWriteLog,
    private val writesEnabled: () -> Boolean = { ConsoleBuildFlags.WRITES_ENABLED },
) : ConsoleCommand {
    override val name = "sql!"
    override val summary = "Run a guarded SQL write (confirm + backup first)"
    override val usage = "sql! \"<insert|update|delete statement>\""
    override val disabledReason: String?
        get() = when {
            !writesEnabled() -> ConsoleBuildFlags.WRITES_DISABLED_REASON
            writer() == null -> "no writable database on this platform"
            backup() == null -> "no backup location on this platform"
            else -> null
        }

    override suspend fun run(args: List<String>, context: ConsoleContext, out: ConsoleOutput): Either<ConsoleError, Unit> {
        if (!writesEnabled()) return ConsoleError.Unsupported(ConsoleBuildFlags.WRITES_DISABLED_REASON).left()
        if (args.isEmpty()) return ConsoleError.Usage("Usage: $usage").left()
        val view = writer() ?: return ConsoleError.Unsupported("no writable database on this platform").left()
        val backups = backup() ?: return ConsoleError.Unsupported("no backup location on this platform").left()
        val statement = when (val verdict = SqlWriteGuard.check(args.joinToString(" "))) {
            is SqlWriteGuard.Verdict.Denied -> return ConsoleError.Denied(verdict.reason).left()
            is SqlWriteGuard.Verdict.Allowed -> verdict
        }
        return view.guarded { runGuarded(view, backups, statement, context, out) }
    }

    private suspend fun runGuarded(
        view: SqlWriteView,
        backups: ConsoleBackup,
        statement: SqlWriteGuard.Verdict.Allowed,
        context: ConsoleContext,
        out: ConsoleOutput,
    ): Either<ConsoleError, Unit> {
        val plan = backups.plan().getOrElse { return ConsoleError.Failed(it).left() }
        val measurement = when (val dry = view.dryRun(statement.statement, statement.table)) {
            DryRunResult.TimedOut -> return ConsoleError.Failed(ConfirmPolicy.TIMED_OUT).left()
            is DryRunResult.Failed -> return ConsoleError.Failed("${dry.engineMessage}. Nothing was changed.").left()
            is DryRunResult.Measured -> dry.measurement
        }
        val confirm = when (val decision = ConfirmPolicy.decide(measurement, statement.nondeterministic)) {
            is ConfirmDecision.Refuse -> return ConsoleError.Failed(decision.reason).left()
            is ConfirmDecision.Confirm -> decision
        }
        val answer = context.confirmer.confirm(request(statement, confirm, plan, backups, measurement.targetRows))
        if (!answer.satisfies(confirm.tier)) {
            out.info("Cancelled. Nothing was changed.")
            return Unit.right()
        }
        if (backups.needed(statement.destructive)) {
            val path = backups.backup().getOrElse { return ConsoleError.Failed(it).left() }
            out.info("Backup: $path")
        }
        return finish(view.write(statement.statement, statement.table, measurement.targetRows, confirm), statement, out)
    }

    private fun finish(result: WriteResult, statement: SqlWriteGuard.Verdict.Allowed, out: ConsoleOutput): Either<ConsoleError, Unit> =
        when (result) {
            is WriteResult.Done -> {
                writeLog.record("${statement.verb.name.uppercase()} ${statement.table}: ${result.changes} rows: ${statement.statement.take(LOG_STATEMENT_CHARS)}")
                out.info("OK: ${result.changes} rows changed in ${statement.table}. DB-only, not written to markdown.")
                Unit.right()
            }
            is WriteResult.Stale -> ConsoleError.Failed(
                "More rows would change than you confirmed (${result.now} now, ${result.confirmed} when you confirmed). " +
                    "Nothing was changed. Run it again to re-confirm. The backup that was taken is kept.",
            ).left()
            is WriteResult.Failed -> ConsoleError.Failed("${result.engineMessage}. Nothing was changed.").left()
        }

    private fun request(
        statement: SqlWriteGuard.Verdict.Allowed,
        confirm: ConfirmDecision.Confirm,
        plan: ConsoleBackup.Plan,
        backups: ConsoleBackup,
        tableRows: Long?,
    ): ConfirmRequest {
        val noun = if (confirm.count == 1L) "row" else "rows"
        val lines = buildList {
            add("${confirm.count} $noun will change in ${statement.table}.")
            if (confirm.tier is ConfirmTier.TypedCount && tableRows != null && !confirm.triggered) {
                add("This changes ${confirm.count} of $tableRows rows in ${statement.table}.")
            }
            confirm.tables.forEach { add("Also changes ${it.table}: ${if (it.delta > 0) "+" else ""}${it.delta} rows (triggers or cascades).") }
            if (statement.nondeterministic) add("The statement uses random() or the current time, so the real count may differ.")
            add(
                if (backups.needed(statement.destructive)) "A backup (about ${ConsoleBackup.megabytes(plan.dbBytes, up = true)} MB) is saved to ${plan.directory} first."
                else "A recent backup already exists in ${plan.directory}.",
            )
            add("DB-only: markdown files are not changed.")
        }
        val verb = statement.verb.name.lowercase().replaceFirstChar { it.uppercase() }
        return ConfirmRequest("Run this write?", statement.statement, lines, confirm.tier, "$verb ${confirm.count} $noun")
    }

    private companion object {
        const val LOG_STATEMENT_CHARS = 120
    }
}
