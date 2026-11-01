// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.db.ConsoleBackup
import dev.stapler.stelekit.db.DbRestore
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.ReadHandleRestoreInspector
import dev.stapler.stelekit.db.RestoreHost
import dev.stapler.stelekit.db.RestoreOutcome
import dev.stapler.stelekit.db.RestoreSource
import dev.stapler.stelekit.db.platformBackupFiles
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Story 3.4 on the Android SQLite driver and a synthetic ~450 MB database (the owner's is 451 MB): the real
 * `ConsoleBackup`, `DbRestore`, file operations and read-only inspector, with the app driver closed and reopened
 * around the swap as `GraphManager` does. Timings go to `Download/db-restore-<runId>.txt` (no logcat). This is
 * not owner pass 12.1: that runs on a copy of the real database.
 */
@RunWith(AndroidJUnit4::class)
class DbRestoreAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var live: File
    private lateinit var url: String
    private var app: SqlDriver? = null

    private inner class AppHost : RestoreHost {
        override suspend fun closeDatabase() { app?.close(); app = null }
        override suspend fun <T> underSwapLock(block: suspend () -> T): T = block()
        override suspend fun reopenDatabase(): Boolean { app = DriverFactory().createDriver(url); return true }
    }

    @Before
    fun setUp() {
        DriverFactory.setContext(context)
        dir = File(context.cacheDir, "db-restore-${System.nanoTime()}").also { it.mkdirs() }
        live = dir.resolve("graph.db")
        url = "jdbc:sqlite:${live.absolutePath}"
        app = DriverFactory().createDriver(url)
    }

    @After
    fun tearDown() {
        runCatching { app?.close() }
        dir.deleteRecursively()
    }

    private fun exec(sql: String) {
        checkNotNull(app).execute(null, sql, 0).value
    }

    private fun pages(): Long = checkNotNull(app).executeQuery(null, "SELECT COUNT(*) FROM pages", { c -> c.next(); QueryResult.Value(c.getLong(0) ?: 0L) }, 0).value

    private fun seed() {
        exec("CREATE TABLE console_fill(id INTEGER PRIMARY KEY, payload BLOB NOT NULL)")
        val rowsPerChunk = CHUNK_MB * 1024 * 1024 / ROW_BYTES
        repeat(DB_MB / CHUNK_MB) { chunk ->
            val first = chunk * rowsPerChunk + 1
            exec(
                "WITH RECURSIVE c(x) AS (SELECT $first UNION ALL SELECT x+1 FROM c WHERE x < ${first + rowsPerChunk - 1}) " +
                    "INSERT INTO console_fill SELECT x, randomblob($ROW_BYTES) FROM c",
            )
            checkNotNull(app).executeQuery(null, "PRAGMA wal_checkpoint(TRUNCATE)", { c -> c.next(); QueryResult.Value(0L) }, 0).value
        }
        repeat(PAGES) { exec("INSERT INTO pages(uuid,name,created_at,updated_at) VALUES ('page-$it','Page $it',1,1)") }
    }

    @Test
    fun restore_should_SwapUndoAndLeaveNoStaleFiles_When_RunOnALargeDatabaseOnTheAndroidDriver() = runBlocking {
        seed()
        val files = checkNotNull(platformBackupFiles())
        record("seed", "db_bytes=${live.length()}")
        val snapshot = checkNotNull(ConsoleBackup.forJdbcUrl(url)).backup().getOrNull()
        checkNotNull(snapshot) { "backup failed" }
        repeat(LATE_PAGES) { exec("INSERT INTO pages(uuid,name,created_at,updated_at) VALUES ('late-$it','Late $it',1,1)") }
        assertEquals((PAGES + LATE_PAGES).toLong(), pages())

        val restore = DbRestore(live.absolutePath, files, ReadHandleRestoreInspector(), AppHost(), File(dir, ConsoleBackup.DIRECTORY_NAME).absolutePath)
        val started = System.nanoTime()
        val outcome = restore.restore(RestoreSource.Backup(File(snapshot).name))
        record("restore", "ms=${(System.nanoTime() - started) / NANOS_PER_MS} outcome=$outcome")
        assertIs<RestoreOutcome.Restored>(outcome)
        assertEquals(PAGES.toLong(), pages(), "later changes are lost")
        assertTrue(File(live.path + ".pre-restore").exists(), "undo copy kept")
        assertFalse(File(live.path + ".restoring").exists())
        assertFalse(File(live.path + ".restore-journal").exists())

        val undoStarted = System.nanoTime()
        val undo = restore.restore(RestoreSource.UndoCopy)
        record("undo", "ms=${(System.nanoTime() - undoStarted) / NANOS_PER_MS} outcome=$undo")
        assertIs<RestoreOutcome.Restored>(undo)
        assertEquals((PAGES + LATE_PAGES).toLong(), pages(), "undo returns the pre-restore state")
        checkNotNull(app).close()
    }

    private fun record(key: String, value: Any?) {
        val line = "DB-RESTORE-ANDROID $key=$value"
        synchronized(lines) { lines += line }
        runCatching {
            val name = "db-restore-$runId.txt"
            val resolver = context.contentResolver
            resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(name))
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            checkNotNull(resolver.openOutputStream(uri)).use { it.write(synchronized(lines) { lines.joinToString("\n") }.toByteArray()) }
        }
    }

    private companion object {
        const val DB_MB = 450
        const val CHUNK_MB = 50
        const val ROW_BYTES = 4_000
        const val PAGES = 20
        const val LATE_PAGES = 7
        const val NANOS_PER_MS = 1_000_000L
        val runId = System.currentTimeMillis()
        val lines = mutableListOf<String>()
    }
}
