package dev.stapler.stelekit.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking

/**
 * Performance PRAGMAs applied in [WalConfiguredCallback.onConfigure] via rawQuery.
 *
 * Exposed as `internal` so [WalConfiguredCallbackTest] can assert the full list without
 * relying on a real [SupportSQLiteDatabase] instance (which requires instrumented tests).
 */
internal val ANDROID_PRAGMAS: List<String> = listOf(
    "PRAGMA journal_mode=WAL",
    "PRAGMA synchronous=NORMAL",
    "PRAGMA busy_timeout=10000",
    "PRAGMA wal_autocheckpoint=4000",
    "PRAGMA temp_store=MEMORY",
    "PRAGMA cache_size=-8000",
    // mmap_size=256MB: maps file pages into process address space, avoids read() syscall
    // overhead on repeated reads. OS only maps pages actually accessed — not pre-allocated.
    // Safe at minSdk 26 with Requery's bundled SQLite 3.49.0.
    "PRAGMA mmap_size=268435456",
)

/**
 * Applies performance PRAGMAs in [onConfigure] via [SupportSQLiteDatabase.query] (rawQuery path).
 *
 * Requery's [RequerySQLiteOpenHelperFactory] restricts [SupportSQLiteDatabase.execSQL] for
 * statements that return a result set (like `PRAGMA journal_mode=WAL`), throwing
 * "Queries can be performed using SQLiteDatabase query or rawQuery methods only." The rawQuery
 * path ([query]) is unrestricted and correctly executes SET-type PRAGMAs — SQLite runs the
 * PRAGMA and returns the new value as a cursor, which we discard by calling [close].
 *
 * [onConfigure] fires before schema creation, ensuring WAL is in effect for all DDL.
 */
private class WalConfiguredCallback(
    schema: app.cash.sqldelight.db.SqlSchema<app.cash.sqldelight.db.QueryResult.Value<Unit>>,
) : AndroidSqliteDriver.Callback(schema) {
    override fun onConfigure(db: SupportSQLiteDatabase) {
        super.onConfigure(db) // preserves foreign-key enforcement and other AndroidSqliteDriver defaults
        // rawQuery path: Requery allows query/rawQuery for all statement types including SET-PRAGMAs.
        // See ANDROID_PRAGMAS for the full list and per-pragma rationale.
        ANDROID_PRAGMAS.forEach { pragma ->
            try { db.query(pragma).close() } catch (_: Exception) { }
        }
    }
}

actual class DriverFactory actual constructor() {
    companion object {
        internal var staticContext: Context? = null

        fun setContext(context: Context) {
            staticContext = context.applicationContext
        }
    }

    actual fun init(context: Any) {
        if (context is Context) {
            setContext(context)
        }
    }

    actual fun createDriver(jdbcUrl: String): SqlDriver {
        val dbName = jdbcUrl.substringAfter("jdbc:sqlite:")

        val context = staticContext ?: error(
            "DriverFactory must be initialized with a Context before creating a driver. " +
            "Call DriverFactory().init(context) first."
        )

        // Ensure parent directory exists for absolute paths
        if (dbName.startsWith("/")) {
            java.io.File(dbName).parentFile?.mkdirs()
        }

        // AndroidSqliteDriver handles schema creation (fresh installs) and numbered .sqm
        // migrations (via SQLiteOpenHelper.onUpgrade) automatically.
        // WalConfiguredCallback applies PRAGMAs in onConfigure via rawQuery (Requery-safe).
        val driver = AndroidSqliteDriver(
            schema = SteleDatabase.Schema.synchronous(),
            context = context,
            name = dbName,
            factory = RequerySQLiteOpenHelperFactory(),
            callback = WalConfiguredCallback(SteleDatabase.Schema.synchronous()),
        )

        // Apply incremental DDL migrations (idempotent, hash-tracked).
        runBlocking { MigrationRunner.applyAll(driver) }

        return driver
    }

    actual fun getDatabaseUrl(graphId: String): String {
        val basePath = getDatabaseDirectory()
        return "jdbc:sqlite:$basePath/stelekit-graph-$graphId.db"
    }

    actual fun getDatabaseDirectory(): String {
        val context = staticContext ?: error("DriverFactory not initialized with a Context.")
        return context.filesDir.absolutePath
    }
}

actual val defaultDatabaseUrl: String
    get() {
        return "jdbc:sqlite:stelekit.db"
    }
