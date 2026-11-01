// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/** Where a restore reads the replacement database from. */
sealed interface RestoreSource {
    /** A snapshot in `console-backups/`, by file name. */
    data class Backup(val fileName: String) : RestoreSource

    /** The `*.pre-restore` set a previous restore left; restoring it swaps the two states. */
    data object UndoCopy : RestoreSource
}

enum class RestoreStep(val label: String) {
    CheckingBackup("Checking backup"),
    ExportingLocalRows("Exporting local-only rows"),
    SwappingDatabase("Swapping database"),
    ReloadingGraph("Reloading graph"),
}

data class RowCounts(val pages: Long, val blocks: Long)

/** Read-only questions about a database file the restore asks before and after the swap. */
interface RestoreInspector {
    suspend fun integrityCheck(path: String): List<String>

    /** Null when the counts cannot be read (not a SteleKit database). */
    suspend fun counts(path: String): RowCounts?
}

/** What the restore needs from the running app; [GraphManagerRestoreHost] is the production implementation. */
interface RestoreHost {
    /** Drains the write actor and closes the connection pool and the console reader; takes the graph lock itself. */
    suspend fun closeDatabase()

    /** Runs [block] under `GraphWriteLock(graph)`. Never call [closeDatabase] or [reopenDatabase] inside it. */
    suspend fun <T> underSwapLock(block: suspend () -> T): T

    /** Reopens the graph and waits for readiness (without holding the lock); false if it did not come up. */
    suspend fun reopenDatabase(): Boolean
}

sealed interface RestoreOutcome {
    /** The database now holds the snapshot. [undoKept] is true when the previous state is available to undo. */
    data class Restored(val counts: RowCounts, val undoKept: Boolean) : RestoreOutcome

    /** Nothing was restored; the previous database is in place and open. */
    data class Failed(val message: String) : RestoreOutcome

    /** The swap failed and the files could not be put back; [paths] hold the data. The journal stays so the next launch retries. */
    data class RollbackFailed(val message: String, val paths: List<String>) : RestoreOutcome
}

/** The file names a restore uses beside the database. */
class RestoreLayout(val dbPath: String) {
    val staged: String = "$dbPath.restoring"
    val journal: String = "$dbPath.restore-journal"
    val notice: String = "$dbPath.restore-notice"
    val sidecarSuffixes: List<String> = listOf("", "-wal", "-shm")
    fun live(suffix: String) = dbPath + suffix
    fun preRestore(suffix: String) = "$dbPath$suffix$PRE_RESTORE"

    companion object {
        const val PRE_RESTORE = ".pre-restore"
    }
}

/** Graphs whose restore is running in this process; next-launch recovery must not touch their journal. */
object RestoreInFlight {
    private val active = MutableStateFlow<Set<String>>(emptySet())
    fun begin(dbPath: String) = active.update { it + dbPath }
    fun end(dbPath: String) = active.update { it - dbPath }
    fun contains(dbPath: String) = dbPath in active.value
}

/**
 * The verified swap behind `graph restore-backup` (plan Story 3.4). In order: copy the snapshot beside
 * the database and `integrity_check` that copy, export DB-only rows, write the journal, close the app's
 * database, then under the graph lock rename the live database and its `-wal`/`-shm` to `*.pre-restore`
 * and rename the verified copy into place, reopen, and compare row counts with the snapshot. Any failure
 * renames the `*.pre-restore` files back. One undo copy is kept; the next restore replaces it.
 */
class DbRestore(
    private val dbPath: String,
    private val files: BackupFiles,
    private val inspector: RestoreInspector,
    private val host: RestoreHost,
    private val backupsDirectory: String,
) {
    private val layout = RestoreLayout(dbPath)
    private val logger = Logger("DbRestore")

    suspend fun restore(
        source: RestoreSource,
        onStep: (RestoreStep) -> Unit = {},
        exportRows: suspend () -> Either<String, Unit> = { Unit.right() },
    ): RestoreOutcome {
        RestoreInFlight.begin(dbPath)
        try {
            return run(source, onStep, exportRows)
        } finally {
            RestoreInFlight.end(dbPath)
        }
    }

    private suspend fun run(source: RestoreSource, onStep: (RestoreStep) -> Unit, exportRows: suspend () -> Either<String, Unit>): RestoreOutcome {
        onStep(RestoreStep.CheckingBackup)
        val prepared = withContext(PlatformDispatcher.IO) { stage(source) }.getOrElse { return RestoreOutcome.Failed(it) }
        onStep(RestoreStep.ExportingLocalRows)
        exportRows().onLeft {
            files.delete(layout.staged)
            return RestoreOutcome.Failed(it)
        }
        onStep(RestoreStep.SwappingDatabase)
        // The staged copy is verified, so the earlier undo set (possibly this restore's own source) is no longer needed.
        // Deleted before the journal exists so a crash can never roll back onto an older state than the live one.
        layout.sidecarSuffixes.forEach { files.delete(layout.preRestore(it)) }
        val started = RestoreJournal(RestoreJournal.State.Swapping, prepared.sourceLabel, prepared.bytes, Clock.System.now().toEpochMilliseconds())
        if (!files.writeText(layout.journal, started.render())) {
            files.delete(layout.staged)
            return RestoreOutcome.Failed("Couldn't write the restore journal beside $dbPath. Nothing was changed.")
        }
        try {
            host.closeDatabase()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return afterFailedSwap(SwapFailure("Couldn't close the database (${e.message ?: e::class.simpleName}). Nothing was changed.", emptyList()))
        }
        val swapFailure = host.underSwapLock { swapFiles(started) }
        if (swapFailure != null) return afterFailedSwap(swapFailure)
        onStep(RestoreStep.ReloadingGraph)
        val opened = host.reopenDatabase()
        val actual = if (opened) inspector.counts(dbPath) else null
        if (opened && actual == prepared.counts) {
            files.delete(layout.journal)
            return RestoreOutcome.Restored(prepared.counts, undoKept = files.sizeOf(layout.preRestore("")) != null)
        }
        val why = if (!opened) "The restored database didn't open" else "Row counts after the restore ($actual) don't match the backup (${prepared.counts})"
        host.closeDatabase()
        val stuck = host.underSwapLock { RestoreRollback.rollback(layout, files) }
        return afterFailedSwap(SwapFailure(if (stuck.isEmpty()) "$why. Your previous data was put back." else why, stuck))
    }

    /** [stuck] is empty when the previous files are back in place. */
    private class SwapFailure(val message: String, val stuck: List<String>)

    private class Prepared(val sourceLabel: String, val bytes: Long, val counts: RowCounts)

    /** Copies the snapshot to [RestoreLayout.staged] and verifies the copy. Left is the user-facing reason. */
    private suspend fun stage(source: RestoreSource): Either<String, Prepared> {
        val sourcePath = when (source) {
            is RestoreSource.Backup -> {
                if (!isPlainName(source.fileName)) return "\"${source.fileName}\" isn't a backup name.".left()
                "$backupsDirectory${separator()}${source.fileName}"
            }
            RestoreSource.UndoCopy -> {
                if ((files.sizeOf(layout.preRestore("-wal")) ?: 0L) > 0L) {
                    return "The undo copy has unflushed changes in ${layout.preRestore("-wal")}, so it can't be restored safely.".left()
                }
                layout.preRestore("")
            }
        }
        val size = files.sizeOf(sourcePath) ?: return "No backup at $sourcePath.".left()
        val available = files.usableSpace(dbPath)
        val needed = size + size / FREE_SPACE_DIVISOR
        if (available != null && available < needed) {
            return "Not enough free space to check the backup (need ${ConsoleBackup.megabytes(needed, up = true)} MB, have ${ConsoleBackup.megabytes(available, up = false)} MB). Nothing was changed.".left()
        }
        files.delete(layout.staged)
        if (!files.copy(sourcePath, layout.staged)) return "Couldn't copy $sourcePath next to the database. Nothing was changed.".left()
        val verdict = inspector.integrityCheck(layout.staged)
        if (verdict != listOf("ok")) {
            files.delete(layout.staged)
            return "The backup at $sourcePath failed integrity_check (${verdict.firstOrNull() ?: "no result"}). Nothing was changed.".left()
        }
        val counts = inspector.counts(layout.staged)
        if (counts == null) {
            files.delete(layout.staged)
            return "The backup at $sourcePath isn't a SteleKit database. Nothing was changed.".left()
        }
        return Prepared(sourcePath, size, counts).right()
    }

    /** Runs under the graph lock. Null on success; otherwise the failure, with the files already rolled back where possible. */
    private fun swapFiles(started: RestoreJournal): SwapFailure? {
        for (suffix in layout.sidecarSuffixes) {
            if (files.sizeOf(layout.live(suffix)) == null) continue
            if (!files.rename(layout.live(suffix), layout.preRestore(suffix))) {
                return rolledBack("Couldn't move ${layout.live(suffix)} aside")
            }
        }
        if (!files.rename(layout.staged, dbPath)) return rolledBack("Couldn't move the verified copy into place")
        // A failed marker write is safe: a crash now makes recovery roll back to the previous state.
        files.writeText(layout.journal, started.copy(state = RestoreJournal.State.Copied).render())
        return null
    }

    private fun rolledBack(reason: String): SwapFailure {
        val stuck = RestoreRollback.rollback(layout, files)
        return SwapFailure(if (stuck.isEmpty()) "$reason. Your previous data was put back." else reason, stuck)
    }

    private suspend fun afterFailedSwap(failure: SwapFailure): RestoreOutcome {
        files.delete(layout.staged)
        if (failure.stuck.isNotEmpty()) {
            val message = "${failure.message}. Restore failed and your previous data could not be put back automatically. " +
                "Your files are safe at ${failure.stuck.joinToString()}."
            logger.error("restore: $message (journal ${layout.journal} kept; the next launch retries the rollback)")
            return RestoreOutcome.RollbackFailed(message, failure.stuck)
        }
        files.delete(layout.journal)
        try {
            host.reopenDatabase()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("restore: reopening after a failed restore threw: ${e.message}")
        }
        return RestoreOutcome.Failed(failure.message)
    }

    private fun isPlainName(name: String) = name.isNotEmpty() && '/' !in name && '\\' !in name && name != ".." && name != "." && name.endsWith(".db")

    private fun separator() = if (backupsDirectory.contains('\\') && !backupsDirectory.contains('/')) '\\' else '/'

    private companion object {
        const val FREE_SPACE_DIVISOR = 5L
    }
}

/** The one rollback used by a failed swap and by next-launch recovery. */
internal object RestoreRollback {
    /** Renames `*.pre-restore` back over the live files. A no-op when no swap reached the first rename. Returns the files still stranded. */
    fun rollback(layout: RestoreLayout, files: BackupFiles): List<String> {
        if (files.sizeOf(layout.preRestore("")) == null) return emptyList()
        // A live sidecar with no saved counterpart is the original one (its move aside failed) unless the verified
        // copy is already in place, in which case it belongs to the restored database and must go.
        val restoredInPlace = files.sizeOf(layout.live("")) != null
        val stuck = mutableListOf<String>()
        for (suffix in layout.sidecarSuffixes) {
            val saved = layout.preRestore(suffix)
            val hasSaved = files.sizeOf(saved) != null
            if (hasSaved || restoredInPlace) files.delete(layout.live(suffix))
            if (hasSaved && !files.rename(saved, layout.live(suffix))) stuck += saved
        }
        return stuck
    }
}

/** The marker written before the first rename and cleared once the outcome is known. */
data class RestoreJournal(val state: State, val source: String, val bytes: Long, val startedMs: Long) {
    enum class State { Swapping, Copied }

    fun render(): String = "state=${state.name}\nsource=$source\nbytes=$bytes\nstartedMs=$startedMs\n"

    companion object {
        fun parse(text: String?): RestoreJournal? {
            val fields = text?.lineSequence()?.mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap()
                ?: return null
            val state = State.entries.firstOrNull { it.name == fields["state"] } ?: return null
            return RestoreJournal(state, fields["source"].orEmpty(), fields["bytes"]?.toLongOrNull() ?: 0L, fields["startedMs"]?.toLongOrNull() ?: 0L)
        }
    }
}

enum class RecoveryKind { PutBack, Finished }

/** What a next-launch recovery did, shown once as a banner and kept for Settings > Developer. */
data class RestoreNotice(val kind: RecoveryKind, val atMs: Long) {
    val headline: String
        get() = when (kind) {
            RecoveryKind.PutBack -> "A restore was interrupted - your previous data was put back. Nothing from the restore was kept."
            RecoveryKind.Finished -> "A restore was interrupted - it was finished. The backup you chose is now your data."
        }
}

/**
 * Runs before the graph's database opens (see `GraphManager.switchGraph`): a journal left by a killed
 * process means the swap may be half done. `Swapping` rolls the `*.pre-restore` files back; `Copied`
 * means the verified copy is already in place, so only the journal is cleared. The `-wal`/`-shm` of a
 * `Copied` database are never deleted: the restored database may already have been opened and written.
 */
object RestoreRecovery {
    private val logger = Logger("RestoreRecovery")

    private val noticeFlow = MutableStateFlow<RestoreNotice?>(null)

    /** The banner source: non-null from recovery until [dismiss]. */
    val notice: StateFlow<RestoreNotice?> get() = noticeFlow

    /** The notice a previous launch left on disk for [dbPath], for Settings. */
    fun persistedNotice(dbPath: String, files: BackupFiles): RestoreNotice? = parseNotice(files.readText(RestoreLayout(dbPath).notice))

    @Volatile
    private var noticeDbPath: String? = null

    /** Dismisses the banner for whichever database the last recovery touched. */
    fun dismiss() = dismiss(noticeDbPath, platformBackupFiles())

    fun dismiss(dbPath: String?, files: BackupFiles?) {
        noticeFlow.value = null
        if (dbPath != null && files != null) files.delete(RestoreLayout(dbPath).notice)
    }

    /** [recoverIfNeeded] for the database behind [jdbcUrl]; never throws, because a recovery problem must not stop the graph opening. */
    fun recoverBeforeOpen(jdbcUrl: String) {
        try {
            val path = resolveDatabasePath(jdbcUrl) ?: return
            val files = platformBackupFiles() ?: return
            recoverIfNeeded(path, files)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("restore recovery failed for $jdbcUrl: ${e.message}")
        }
    }

    /** Null when nothing needed recovering (or a restore is running in this process). */
    fun recoverIfNeeded(dbPath: String, files: BackupFiles, nowMs: Long = Clock.System.now().toEpochMilliseconds()): Either<List<String>, RestoreNotice>? {
        if (RestoreInFlight.contains(dbPath)) return null
        val layout = RestoreLayout(dbPath)
        val journal = RestoreJournal.parse(files.readText(layout.journal))
        if (journal == null) {
            files.delete(layout.staged)
            return null
        }
        val kind = when (journal.state) {
            RestoreJournal.State.Copied -> RecoveryKind.Finished
            RestoreJournal.State.Swapping -> {
                val stuck = RestoreRollback.rollback(layout, files)
                if (stuck.isNotEmpty()) {
                    logger.error("restore recovery: could not put back ${stuck.joinToString()}; journal kept for the next launch")
                    return stuck.left()
                }
                RecoveryKind.PutBack
            }
        }
        files.delete(layout.staged)
        val notice = RestoreNotice(kind, nowMs)
        files.writeText(layout.notice, "kind=${kind.name}\natMs=$nowMs\n")
        files.delete(layout.journal)
        noticeDbPath = dbPath
        noticeFlow.value = notice
        logger.warn("restore recovery: interrupted restore of $dbPath, outcome=${kind.name}")
        return notice.right()
    }

    private fun parseNotice(text: String?): RestoreNotice? {
        val fields = text?.lineSequence()?.mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap() ?: return null
        val kind = RecoveryKind.entries.firstOrNull { it.name == fields["kind"] } ?: return null
        return RestoreNotice(kind, fields["atMs"]?.toLongOrNull() ?: 0L)
    }

    /** Test seam: forget the in-memory banner state. */
    fun resetForTest() {
        noticeFlow.value = null
    }
}

/** The snapshots a restore can use, newest first, and the undo copy. */
class RestoreCatalog(private val dbPath: String, private val files: BackupFiles, private val backupsDirectory: String) {
    data class Entry(val name: String, val modifiedMs: Long, val bytes: Long)

    private val stem = dbPath.substring(maxOf(dbPath.lastIndexOf('/'), dbPath.lastIndexOf('\\')) + 1).substringBeforeLast('.')
    private val dbName = dbPath.substring(maxOf(dbPath.lastIndexOf('/'), dbPath.lastIndexOf('\\')) + 1)

    /** Snapshots of this graph's database only (other graphs' backups share the directory). */
    fun backups(): List<Entry> = files.list(backupsDirectory)
        .filter { it.name.startsWith("$stem-") && it.name.endsWith(".db") }
        .sortedWith(compareByDescending<BackupFileInfo> { it.modifiedMs }.thenByDescending { it.name })
        .map { Entry(it.name, it.modifiedMs, files.sizeOf("$backupsDirectory/${it.name}") ?: 0L) }

    /** The "Before restore" entry, if a previous restore left one. */
    fun undoCopy(): Entry? {
        val dir = dbPath.substringBeforeLast('/', "").ifEmpty { return null }
        val info = files.list(dir).firstOrNull { it.name == "$dbName${RestoreLayout.PRE_RESTORE}" } ?: return null
        return Entry(info.name, info.modifiedMs, files.sizeOf(dbPath + RestoreLayout.PRE_RESTORE) ?: 0L)
    }
}
