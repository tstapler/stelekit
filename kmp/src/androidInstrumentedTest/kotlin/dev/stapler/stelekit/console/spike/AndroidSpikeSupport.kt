// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console.spike

import android.content.Context
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.db.DriverFactory
import io.requery.android.database.sqlite.SQLiteDatabase as RequeryDb
import java.io.File

/** Shared fixtures for the Android halves of Story 0.3. Results are `SPIKE-RESULT` lines published to Downloads (see [record]). */
internal object AndroidSpikeSupport {

    const val RECURSIVE_CTE = "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM r WHERE n<1000000000) SELECT count(*) FROM r"

    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun freshDir(name: String): File = File(context.cacheDir, "console-spike-$name-${System.nanoTime()}").also { it.mkdirs() }

    /** The app's own driver (write path, pooled) over [db]; creates schema + migrations like production. */
    fun appDriver(db: File): SqlDriver {
        DriverFactory.setContext(context)
        return DriverFactory().createDriver("jdbc:sqlite:${db.absolutePath}")
    }

    fun appInsertPage(driver: SqlDriver, id: String) {
        driver.execute(null, "INSERT INTO pages(uuid,name,created_at,updated_at) VALUES ('$id','$id',1,1)", 0).value
    }

    fun requeryReadOnly(db: File): RequeryDb = RequeryDb.openDatabase(db.absolutePath, null, RequeryDb.OPEN_READONLY)

    fun requeryReadWrite(db: File): RequeryDb = RequeryDb.openDatabase(db.absolutePath, null, RequeryDb.OPEN_READWRITE)

    fun platformReadOnly(db: File): android.database.sqlite.SQLiteDatabase =
        android.database.sqlite.SQLiteDatabase.openDatabase(db.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)

    fun scalarLong(db: RequeryDb, sql: String): Long = db.rawQuery(sql, null).use { it.moveToFirst(); it.getLong(0) }

    fun scalarLong(db: android.database.sqlite.SQLiteDatabase, sql: String): Long = db.rawQuery(sql, null).use { it.moveToFirst(); it.getLong(0) }

    fun scalarString(db: RequeryDb, sql: String): String = db.rawQuery(sql, null).use { it.moveToFirst(); it.getString(0) }

    /** Inserts [pages] pages x [blocksPerPage] blocks of ~[contentBytes] over one transaction; FTS triggers fire. */
    fun seed(db: File, pages: Int, blocksPerPage: Int = 10, contentBytes: Int = 1500) {
        val rw = requeryReadWrite(db)
        try {
            rw.beginTransaction()
            val filler = "lorem ipsum dolor sit amet ".repeat(contentBytes / 27 + 1).take(contentBytes)
            for (p in 0 until pages) {
                rw.execSQL("INSERT INTO pages(uuid,name,created_at,updated_at) VALUES ('page-$p','Page $p',1,1)")
                for (b in 0 until blocksPerPage) {
                    rw.execSQL(
                        "INSERT INTO blocks(uuid,page_uuid,content,position,created_at,updated_at) VALUES (?,?,?,?,1,1)",
                        arrayOf("page-$p-b$b", "page-$p", "$filler $p $b", "a$b"),
                    )
                }
            }
            rw.setTransactionSuccessful()
            rw.endTransaction()
        } finally {
            rw.close()
        }
    }

    private val runId = System.currentTimeMillis()
    private val lines = mutableListOf<String>()

    /**
     * Gradle uninstalls the app after the run and the result XML carries no stdout, so each record re-publishes all lines
     * of this process to Downloads/console-spike-<runId>.txt via MediaStore, where they survive the uninstall for `adb pull`.
     */
    @Synchronized
    fun record(spike: String, key: String, value: Any?) {
        val line = "SPIKE-RESULT $spike $key=$value"
        lines += line
        Log.i("ConsoleSpike", line)
        runCatching { publish() }.onFailure { Log.w("ConsoleSpike", "publish failed: $it") }
    }

    private fun publish() {
        val resolver = context.contentResolver
        val name = "console-spike-$runId.txt"
        val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.delete(collection, "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(name))
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
        }
        val uri = checkNotNull(resolver.insert(collection, values))
        resolver.openOutputStream(uri)!!.use { it.write(lines.joinToString("\n").toByteArray()) }
    }
}

/** Runs [block], returning its result and elapsed wall-clock ms. */
internal inline fun <T> timedMs(block: () -> T): Pair<T, Long> {
    val t0 = System.nanoTime()
    val r = block()
    return r to (System.nanoTime() - t0) / 1_000_000
}
