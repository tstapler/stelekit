// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console.commands

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.console.ConfirmRequest
import dev.stapler.stelekit.console.ConfirmTier
import dev.stapler.stelekit.console.ConsoleBuildFlags
import dev.stapler.stelekit.console.ConsoleCommand
import dev.stapler.stelekit.console.ConsoleContext
import dev.stapler.stelekit.console.ConsoleError
import dev.stapler.stelekit.console.ConsoleOutput
import dev.stapler.stelekit.console.ConsoleWriteLog
import dev.stapler.stelekit.console.DbOnlyPage
import dev.stapler.stelekit.console.DbOnlyRowsGuard
import dev.stapler.stelekit.console.satisfies
import dev.stapler.stelekit.db.DbRestore
import dev.stapler.stelekit.db.RestoreCatalog
import dev.stapler.stelekit.db.RestoreInspector
import dev.stapler.stelekit.db.RestoreLayout
import dev.stapler.stelekit.db.RestoreOutcome
import dev.stapler.stelekit.db.RestoreSource
import dev.stapler.stelekit.db.RowCounts
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** Everything a restore of the open graph's database needs; built per run because the active graph can change. */
class RestoreToolkit(
    val dbPath: String,
    val backupsDirectory: String,
    val catalog: RestoreCatalog,
    val inspector: RestoreInspector,
    val restore: DbRestore,
    val guard: DbOnlyRowsGuard,
)

/** The restore flow shared by `graph restore-backup`, `graph undo-restore` and `backups restore`. */
class RestoreRunner(
    private val toolkit: () -> RestoreToolkit?,
    private val writeLog: ConsoleWriteLog,
    private val writesEnabled: () -> Boolean,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    val available: Boolean get() = toolkit() != null

    suspend fun run(source: RestoreSource, context: ConsoleContext, out: ConsoleOutput): Either<ConsoleError, Unit> {
        if (!writesEnabled()) return ConsoleError.Unsupported(ConsoleBuildFlags.WRITES_DISABLED_REASON).left()
        val kit = toolkit() ?: return ConsoleError.Unsupported(NO_DATABASE_FILE).left()
        val preflight = when (val checked = preflight(kit, source)) {
            is Either.Left -> return ConsoleError.Failed(checked.value).left()
            is Either.Right -> checked.value
        }
        val answer = context.confirmer.confirm(RestoreConfirm.request(preflight, nowMs()))
        if (!answer.satisfies(RestoreConfirm.TIER)) {
            out.info("Cancelled. Nothing was changed.")
            return Unit.right()
        }
        // The session scope is cancelled when GraphContent leaves composition, which the swap itself triggers.
        val outcome = withContext(NonCancellable) {
            kit.restore.restore(
                source,
                onStep = { out.info("${it.label}...") },
                exportRows = { exportLocalRows(kit, preflight, out) },
            )
        }
        return when (outcome) {
            is RestoreOutcome.Restored -> {
                writeLog.record("RESTORE ${preflight.sourceLabel}")
                out.info("Restored. Pages: ${outcome.counts.pages}, blocks: ${outcome.counts.blocks} - matches the backup.")
                out.info(if (outcome.undoKept) "Your previous data is kept as ${RestoreLayout(kit.dbPath).preRestore("")}. Run `graph undo-restore` to swap back." else "No undo copy was kept.")
                Unit.right()
            }
            is RestoreOutcome.Failed -> ConsoleError.Failed(outcome.message).left()
            is RestoreOutcome.RollbackFailed -> ConsoleError.Failed(outcome.message).left()
        }
    }

    private suspend fun preflight(kit: RestoreToolkit, source: RestoreSource): Either<String, RestorePreflight> {
        val (sourcePath, entry) = when (source) {
            is RestoreSource.Backup -> "${kit.backupsDirectory}/${source.fileName}" to kit.catalog.backups().firstOrNull { it.name == source.fileName }
            RestoreSource.UndoCopy -> RestoreLayout(kit.dbPath).preRestore("") to kit.catalog.undoCopy()
        }
        if (entry == null) return "No ${if (source is RestoreSource.Backup) "backup named ${source.fileName}" else "undo copy"} found. Run `backups` to list them.".left()
        val backupCounts = kit.inspector.counts(sourcePath) ?: return "Can't read ${source.describe()} at $sourcePath.".left()
        return RestorePreflight(
            source = source,
            sourceLabel = sourcePath,
            ageMs = (nowMs() - entry.modifiedMs).coerceAtLeast(0L),
            backupCounts = backupCounts,
            liveCounts = kit.inspector.counts(kit.dbPath),
            dbOnly = kit.guard.scan(),
            exportDirectory = kit.guard.recoveryDirectory,
            replacesUndoFrom = kit.catalog.undoCopy()?.takeIf { source is RestoreSource.Backup }?.modifiedMs,
        ).right()
    }

    private fun exportLocalRows(kit: RestoreToolkit, preflight: RestorePreflight, out: ConsoleOutput): Either<String, Unit> =
        kit.guard.export(preflight.dbOnly).map { dir ->
            if (preflight.dbOnly.isNotEmpty()) out.info("Local-only pages saved to $dir")
        }

    private fun RestoreSource.describe() = if (this is RestoreSource.Backup) "backup $fileName" else "the undo copy"
}

/** What the restore confirm quantifies (plan Task 3.4d). */
data class RestorePreflight(
    val source: RestoreSource,
    val sourceLabel: String,
    val ageMs: Long,
    val backupCounts: RowCounts,
    val liveCounts: RowCounts?,
    val dbOnly: List<DbOnlyPage>,
    val exportDirectory: String,
    val replacesUndoFrom: Long?,
)

private const val NO_DATABASE_FILE = "no database file on this platform"

object RestoreConfirm {
    const val PHRASE = "RESTORE"
    val TIER = ConfirmTier.TypedPhrase(PHRASE)
    private const val MS_PER_MINUTE = 60_000L
    private const val MINUTES_PER_HOUR = 60L
    private const val HOURS_PER_DAY = 24L

    /** "just now", "5 minutes ago", "3 hours ago", "2 days ago". */
    fun age(ms: Long): String {
        val minutes = ms / MS_PER_MINUTE
        val hours = minutes / MINUTES_PER_HOUR
        val days = hours / HOURS_PER_DAY
        return when {
            minutes < 1 -> "just now"
            hours < 1 -> "$minutes ${plural(minutes, "minute")} ago"
            days < 1 -> "$hours ${plural(hours, "hour")} ago"
            else -> "$days ${plural(days, "day")} ago"
        }
    }

    private fun ageAdjective(ms: Long): String {
        val minutes = ms / MS_PER_MINUTE
        val hours = minutes / MINUTES_PER_HOUR
        val days = hours / HOURS_PER_DAY
        return when {
            minutes < 1 -> "new"
            hours < 1 -> "$minutes-minute-old"
            days < 1 -> "$hours-hour-old"
            else -> "$days-day-old"
        }
    }

    private fun plural(n: Long, word: String) = if (n == 1L) word else "${word}s"

    fun request(p: RestorePreflight, nowMs: Long): ConfirmRequest {
        val what = if (p.source is RestoreSource.UndoCopy) "the undo copy" else "backup"
        val lines = buildList {
            add("Later changes are lost: everything written after this ${if (p.source is RestoreSource.UndoCopy) "copy was made" else "backup was taken"} ${age(p.ageMs)}.")
            val live = p.liveCounts?.let { "${it.pages} pages and ${it.blocks} blocks" } ?: "an unreadable count"
            add("The $what has ${p.backupCounts.pages} pages and ${p.backupCounts.blocks} blocks; the live database has $live.")
            p.liveCounts?.let { add("Rows that differ: ${kotlin.math.abs(it.pages - p.backupCounts.pages)} pages and ${kotlin.math.abs(it.blocks - p.backupCounts.blocks)} blocks (net).") }
            if (p.dbOnly.isNotEmpty()) {
                add("${p.dbOnly.size} local-only journal ${if (p.dbOnly.size == 1) "page" else "pages"} (database only, no markdown file) will be saved to ${p.exportDirectory} first.")
            }
            p.replacesUndoFrom?.let { add("This replaces the earlier undo copy from ${age(nowMs - it)}.") }
            add("Your current database is kept as an undo copy until the next restore. Keep SteleKit open while it runs.")
        }
        val label = if (p.source is RestoreSource.UndoCopy) "Restore undo copy" else "Restore ${ageAdjective(p.ageMs)} backup"
        return ConfirmRequest("Restore this database?", "graph restore-backup ${p.sourceLabel.substringAfterLast('/')}", lines, TIER, label)
    }
}

/**
 * `graph restore-backup <name>` and `graph undo-restore`: replace the open graph's database with a console backup,
 * after a typed `RESTORE` confirm. Compiled out of release builds with `sql!` ([ConsoleBuildFlags.WRITES_ENABLED]).
 */
class GraphRestoreCommand(
    private val runner: RestoreRunner,
    private val writesEnabled: () -> Boolean = { ConsoleBuildFlags.WRITES_ENABLED },
) : ConsoleCommand {
    override val name = "graph"
    override val summary = "Restore the database from a console backup (graph restore-backup <name> | graph undo-restore)"
    override val usage = "graph restore-backup <name> | graph undo-restore"
    override val disabledReason: String?
        get() = when {
            !writesEnabled() -> ConsoleBuildFlags.WRITES_DISABLED_REASON
            !runner.available -> NO_DATABASE_FILE
            else -> null
        }

    override suspend fun run(args: List<String>, context: ConsoleContext, out: ConsoleOutput): Either<ConsoleError, Unit> {
        if (!writesEnabled()) return ConsoleError.Unsupported(ConsoleBuildFlags.WRITES_DISABLED_REASON).left()
        return when {
            args.size == 2 && args[0] == "restore-backup" -> runner.run(RestoreSource.Backup(args[1]), context, out)
            args.size == 1 && args[0] == "undo-restore" -> runner.run(RestoreSource.UndoCopy, context, out)
            else -> ConsoleError.Usage("Usage: $usage").left()
        }
    }
}

/** `backups` lists snapshots and the undo copy (read-only, always available); `backups restore <name>` is `graph restore-backup`. */
class BackupsCommand(
    private val toolkit: () -> RestoreToolkit?,
    private val runner: RestoreRunner,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ConsoleCommand {
    override val name = "backups"
    override val summary = "List console backups (backups restore <name> restores one)"
    override val usage = "backups [restore <name>]"
    override val disabledReason: String? get() = if (toolkit() == null) NO_DATABASE_FILE else null

    override suspend fun run(args: List<String>, context: ConsoleContext, out: ConsoleOutput): Either<ConsoleError, Unit> {
        if (args.size == 2 && args[0] == "restore") return runner.run(RestoreSource.Backup(args[1]), context, out)
        if (args.isNotEmpty()) return ConsoleError.Usage("Usage: $usage").left()
        val kit = toolkit() ?: return ConsoleError.Unsupported(NO_DATABASE_FILE).left()
        val backups = kit.catalog.backups()
        val undo = kit.catalog.undoCopy()
        if (backups.isEmpty() && undo == null) out.info("No backups yet. `sql!` takes one before it writes.")
        backups.forEach { out.info("${it.name}  ${RestoreConfirm.age(nowMs() - it.modifiedMs)}  ${it.bytes / BYTES_PER_MB} MB") }
        undo?.let { out.info("Before restore  ${RestoreConfirm.age(nowMs() - it.modifiedMs)}  ${it.bytes / BYTES_PER_MB} MB  (graph undo-restore)") }
        return Unit.right()
    }

    private companion object {
        const val BYTES_PER_MB = 1_048_576L
    }
}
