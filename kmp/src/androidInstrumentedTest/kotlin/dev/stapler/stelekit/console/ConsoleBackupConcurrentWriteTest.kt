// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import android.content.ContentValues
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.db.ConsoleBackup
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.RestrictedDatabaseQueries
import dev.stapler.stelekit.db.SqlConsoleWriter
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.db.WriteResult
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 3.3f / IT-86: an app write through `DatabaseWriteActor` while `ConsoleBackup` runs `VACUUM INTO` over a
 * synthetic ~450 MB database on the Android driver. The write must finish within [WRITE_BOUND_MS], which is set
 * from the measured run recorded in the PR (not guessed), and no `database is locked` error may surface.
 * Evidence lines go to `Download/console-backup-concurrent-<runId>.txt` (no logcat, no stdout in the result XML).
 */
@RunWith(AndroidJUnit4::class)
class ConsoleBackupConcurrentWriteTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var url: String
    private lateinit var app: SqlDriver
    private lateinit var actor: DatabaseWriteActor

    @Before
    fun setUp() {
        DriverFactory.setContext(context)
        dir = File(context.cacheDir, "console-backup-${System.nanoTime()}").also { it.mkdirs() }
        url = "jdbc:sqlite:${dir.resolve("graph.db").absolutePath}"
        app = DriverFactory().createDriver(url)
        actor = DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository())
    }

    @After
    fun tearDown() {
        actor.close()
        app.close()
        dir.deleteRecursively()
    }

    private fun exec(sql: String) {
        app.execute(null, sql, 0).value
    }

    private fun scalar(sql: String): Long = app.executeQuery(null, sql, { c -> c.next(); QueryResult.Value(c.getLong(0) ?: 0L) }, 0).value

    /** About [megabytes] MB of incompressible rows in a side table, in chunks so the WAL stays small. */
    private fun seed(megabytes: Int) {
        exec("CREATE TABLE console_fill(id INTEGER PRIMARY KEY, payload BLOB NOT NULL)")
        val rowsPerChunk = CHUNK_MB * 1024 * 1024 / ROW_BYTES
        repeat(megabytes / CHUNK_MB) { chunk ->
            val first = chunk * rowsPerChunk + 1
            exec(
                "WITH RECURSIVE c(x) AS (SELECT $first UNION ALL SELECT x+1 FROM c WHERE x < ${first + rowsPerChunk - 1}) " +
                    "INSERT INTO console_fill SELECT x, randomblob($ROW_BYTES) FROM c",
            )
            scalar("PRAGMA wal_checkpoint(TRUNCATE)")
        }
        repeat(PAGES) { exec("INSERT INTO pages(uuid,name,created_at,updated_at) VALUES ('page-$it','Page $it',1,1)") }
    }

    @Test
    fun appWrite_should_CompleteWithinBoundWithoutLockError_When_VacuumIntoRunsOnLargeDatabase() = runBlocking {
        val seedStart = System.nanoTime()
        seed(DB_MB)
        val dbBytes = dir.resolve("graph.db").length()
        record("seed", "db_bytes=$dbBytes seed_ms=${(System.nanoTime() - seedStart) / NANOS_PER_MS}")
        assertTrue(dbBytes >= DB_MB * 1024L * 1024L * 9 / 10, "synthetic database too small: $dbBytes")

        val writer = SqlConsoleWriter(
            RestrictedDatabaseQueries(SteleDatabase(app).steleDatabaseQueries, app),
            { invalidate, op -> actor.execute(invalidate = invalidate, op = op) },
        )
        val backup = checkNotNull(ConsoleBackup.forJdbcUrl(url)) { "Android must support console backups" }
        val confirm = ConfirmDecision.Confirm(ConfirmTier.Standard, count = 1, triggered = false, tables = emptyList())

        val backupStart = System.nanoTime()
        val backupJob = async(Dispatchers.Default) {
            val result = backup.backup()
            result to (System.nanoTime() - backupStart) / NANOS_PER_MS
        }
        val latencies = mutableListOf<Long>()
        val errors = mutableListOf<String>()
        var writesDuringBackup = 0
        var n = 0
        while (backupJob.isActive || latencies.isEmpty()) {
            val started = System.nanoTime()
            val overlapped = backupJob.isActive
            val result = withTimeout(WRITE_TIMEOUT_MS) {
                writer.write("update pages set name = 'Renamed ${n++}' where uuid = 'page-0'", "pages", PAGES.toLong(), confirm)
            }
            latencies += (System.nanoTime() - started) / NANOS_PER_MS
            if (overlapped) writesDuringBackup++
            if (result !is WriteResult.Done) errors += result.toString()
        }
        val (backupResult, backupMs) = backupJob.await()
        val snapshot = backupResult.getOrNull()
        val maxLatency = latencies.max()
        record("backup", "ms=$backupMs result=${backupResult.fold({ it }, { "ok" })} snapshot_bytes=${snapshot?.let { File(it).length() }}")
        val sorted = latencies.sorted()
        record("writes", "count=${latencies.size} during_backup=$writesDuringBackup p50_ms=${sorted[sorted.size / 2]} p99_ms=${sorted[sorted.size * 99 / 100]} max_ms=$maxLatency errors=$errors")

        assertTrue(snapshot != null, "backup failed: ${backupResult.leftOrNull()}")
        assertTrue(errors.none { it.contains("locked", ignoreCase = true) }, "lock error surfaced: $errors")
        assertEquals(emptyList(), errors, "every concurrent write must succeed")
        assertTrue(writesDuringBackup >= 1, "no write overlapped the backup (backup took $backupMs ms)")
        assertTrue(maxLatency <= WRITE_BOUND_MS, "app write took $maxLatency ms during VACUUM INTO (bound $WRITE_BOUND_MS ms)")
    }

    private fun record(key: String, value: Any?) {
        val line = "CONSOLE-BACKUP-ANDROID $key=$value"
        synchronized(lines) { lines += line }
        Log.i("ConsoleBackupAndroid", line)
        runCatching { publish() }.onFailure { Log.w("ConsoleBackupAndroid", "publish failed: $it") }
    }

    private fun publish() {
        val name = "console-backup-concurrent-$runId.txt"
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.delete(collection, "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(name))
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
        }
        val uri = checkNotNull(resolver.insert(collection, values))
        resolver.openOutputStream(uri)!!.use { it.write(synchronized(lines) { lines.joinToString("\n") }.toByteArray()) }
    }

    private companion object {
        const val DB_MB = 450
        const val CHUNK_MB = 50
        const val ROW_BYTES = 4_000
        const val PAGES = 20
        const val NANOS_PER_MS = 1_000_000L
        const val WRITE_TIMEOUT_MS = 60_000L

        /**
         * Two Pixel_Fold API 34 runs on a 464 MB database: `VACUUM INTO` took 2,341 and 2,163 ms, 2,286 and 1,645 app
         * writes overlapped it, max write latency 44 and 375 ms, 0 errors; Spike 0.3b measured max 102 ms on JVM.
         * The bound is about 2.7x the worst observed latency and under half the backup time: what it rules out is
         * a write queued behind the backup.
         */
        const val WRITE_BOUND_MS = 1_000L
        val runId = System.currentTimeMillis()
        val lines = mutableListOf<String>()
    }
}
