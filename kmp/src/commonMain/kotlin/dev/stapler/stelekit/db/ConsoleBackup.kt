// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Instant

/** The two engine operations a backup needs; [ReadHandleBackupEngine] is the production implementation. */
interface BackupEngine {
    /** Writes a consistent snapshot of the live database to the not-yet-existing file [target]. */
    suspend fun vacuumInto(target: String)

    /** The rows of `PRAGMA integrity_check` for the database file at [path]. */
    suspend fun integrityCheck(path: String): List<String>
}

/**
 * The automatic backup before a console write. `VACUUM INTO` (Spike 0.3b: works from the read-only
 * handle on JVM and Android, 1.3 s for a 431 MB database on JVM) into `console-backups/` beside the
 * database, verified with `integrity_check`, newest [retention] kept. A failure of any step blocks
 * the write; the message is the one failure copy everywhere.
 *
 * Policy: a backup precedes the first write of a session, every destructive write (DELETE, REPLACE), and any
 * write more than [FRESH_WINDOW_MS] after the last backup.
 */
class ConsoleBackup(
    val dbPath: String,
    private val files: BackupFiles,
    private val engine: BackupEngine,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    retention: Int = DEFAULT_RETENTION,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    val retention: Int = retention.coerceIn(1, MAX_RETENTION)
    val directory: String = siblingPath(dbPath, DIRECTORY_NAME)

    @Volatile private var lastBackupMs: Long? = null

    /** What the confirm modal shows: the snapshot will be about [dbBytes] in [directory]. */
    data class Plan(val dbBytes: Long, val directory: String)

    /** Left is the full "Backup failed: ..." message when there is not enough room for 1.2x the database. */
    fun plan(): Either<String, Plan> {
        val size = files.sizeOf(dbPath) ?: return failure("the database file can't be found").left()
        files.ensureDir(directory)
        val available = files.usableSpace(directory)
        val needed = size + size / FREE_SPACE_DIVISOR
        if (available != null && available < needed) {
            return failure("not enough free space (need ${megabytes(needed, up = true)} MB, have ${megabytes(available, up = false)} MB)").left()
        }
        return Plan(size, directory).right()
    }

    fun needed(destructive: Boolean): Boolean {
        val last = lastBackupMs ?: return true
        return destructive || nowMs() - last > FRESH_WINDOW_MS
    }

    /** The path of the verified snapshot, or the "Backup failed: ..." message. */
    suspend fun backup(): Either<String, String> {
        plan().onLeft { return it.left() }
        removeStalePartials()
        val started = nowMs()
        val finalPath = freshName("$directory${separatorOf(dbPath)}${stem(dbPath)}-${stamp(started)}")
        val partial = "$finalPath$PARTIAL_SUFFIX"
        return try {
            engine.vacuumInto(partial)
            val verdict = engine.integrityCheck(partial)
            if (verdict != listOf("ok")) {
                files.delete(partial)
                return failure("integrity_check reported ${verdict.firstOrNull() ?: "no result"}").left()
            }
            if (!files.rename(partial, finalPath)) {
                files.delete(partial)
                return failure("couldn't move the snapshot into place").left()
            }
            lastBackupMs = started
            prune()
            finalPath.right()
        } catch (e: CancellationException) {
            files.delete(partial)
            throw e
        } catch (e: Exception) {
            files.delete(partial)
            failure(e.message ?: e::class.simpleName.orEmpty()).left()
        }
    }

    /** Two backups in the same second must not overwrite each other; `_2` sorts after `.db`, so name order stays age order. */
    private fun freshName(base: String): String {
        var candidate = "$base.db"
        var n = 2
        while (files.sizeOf(candidate) != null) candidate = "${base}_${n++}.db"
        return candidate
    }

    private fun removeStalePartials() {
        files.list(directory).filter { it.name.endsWith(PARTIAL_SUFFIX) }
            .forEach { files.delete("$directory${separatorOf(dbPath)}${it.name}") }
    }

    private fun prune() {
        files.list(directory).filter { it.name.endsWith(".db") }
            .sortedWith(compareByDescending<BackupFileInfo> { it.modifiedMs }.thenByDescending { it.name })
            .drop(retention)
            .forEach { files.delete("$directory${separatorOf(dbPath)}${it.name}") }
    }

    private fun stamp(ms: Long): String {
        val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "${t.year}${two(t.monthNumber)}${two(t.dayOfMonth)}-${two(t.hour)}${two(t.minute)}${two(t.second)}"
    }

    companion object {
        const val DIRECTORY_NAME = "console-backups"
        const val DEFAULT_RETENTION = 2
        const val MAX_RETENTION = 5
        const val FRESH_WINDOW_MS = 10 * 60 * 1_000L
        private const val FREE_SPACE_DIVISOR = 5L
        private const val PARTIAL_SUFFIX = ".partial"
        private const val BYTES_PER_MB = 1_048_576L

        fun failure(reason: String) = "Backup failed: $reason. Nothing was changed."

        fun megabytes(bytes: Long, up: Boolean): Long = if (up) (bytes + BYTES_PER_MB - 1) / BYTES_PER_MB else bytes / BYTES_PER_MB

        /** A backup for the database behind [jdbcUrl], or null where the platform has no database file. */
        fun forJdbcUrl(jdbcUrl: String, retention: Int = DEFAULT_RETENTION): ConsoleBackup? {
            val path = resolveDatabasePath(jdbcUrl) ?: return null
            val files = platformBackupFiles() ?: return null
            return ConsoleBackup(path, files, ReadHandleBackupEngine(jdbcUrl), retention = retention)
        }

        private fun separatorOf(path: String) = if (path.contains('\\') && !path.contains('/')) '\\' else '/'

        private fun siblingPath(path: String, name: String): String {
            val cut = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
            return if (cut < 0) name else path.substring(0, cut + 1) + name
        }

        private fun stem(path: String): String =
            path.substring(maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1).substringBeforeLast('.')
    }
}

/** Runs `VACUUM INTO` and `integrity_check` over console-owned read-only handles; never the app pool. */
internal class ReadHandleBackupEngine(private val sourceUrl: String) : BackupEngine {
    override suspend fun vacuumInto(target: String) = withContext(PlatformDispatcher.DB) {
        val handle = openSqlReadHandle(sourceUrl) ?: error("no read-only connection on this platform")
        try {
            handle.prepare()
            handle.execute("VACUUM INTO '${target.replace("'", "''")}'", SMALL_CELL, DiscardingSink)
        } finally {
            handle.close()
        }
    }

    override suspend fun integrityCheck(path: String): List<String> = withContext(PlatformDispatcher.DB) {
        val handle = openSqlReadHandle("jdbc:sqlite:$path") ?: error("no read-only connection on this platform")
        try {
            val rows = mutableListOf<String>()
            handle.prepare()
            handle.execute("PRAGMA integrity_check", SMALL_CELL, object : SqlRowSink {
                override fun columns(names: List<String>) = Unit
                override fun hasCapacity() = rows.size < MAX_CHECK_ROWS
                override fun row(cells: List<String?>) { rows += cells.firstOrNull().orEmpty() }
                override fun overflow() = Unit
            })
            rows
        } finally {
            handle.close()
        }
    }

    private object DiscardingSink : SqlRowSink {
        override fun columns(names: List<String>) = Unit
        override fun hasCapacity() = true
        override fun row(cells: List<String?>) = Unit
        override fun overflow() = Unit
    }

    private companion object {
        const val SMALL_CELL = 256
        const val MAX_CHECK_ROWS = 20
    }
}
