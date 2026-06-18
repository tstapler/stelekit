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
    "PRAGMA temp_store=MEMORY",
    "PRAGMA cache_size=-8000",
    // mmap_size=64MB: maps file pages into process address space, avoids read() syscall
    // overhead on repeated reads. OS lazily maps only accessed pages — not pre-allocated.
    // 64MB is conservative for mobile: covers typical graph sizes while leaving VA headroom
    // on 32-bit ARM devices and staying safe on 1-2GB RAM handsets.
    "PRAGMA mmap_size=67108864",
    // wal_autocheckpoint=1000: trigger passive checkpoint every 1000 WAL pages (~4 MB).
    // Smaller threshold keeps the WAL compact so readers don't scan many frames during
    // concurrent writes. An explicit wal_checkpoint(TRUNCATE) is issued after bulk graph
    // import via SqlDelightBlockRepository.walCheckpoint() / onBulkImportComplete.
    "PRAGMA wal_autocheckpoint=1000",
    // analysis_limit=400: limits ANALYZE sampling to 400 index rows (reservoir sample).
    // Makes each ANALYZE call O(1) in table size — typically under 50 ms even on 50 000-row
    // tables. Must come before the ANALYZE calls below.
    // Called here (rawQuery) rather than driver.execute() because Requery throws for
    // result-returning statements (PRAGMA analysis_limit returns the new value).
    "PRAGMA analysis_limit=400",
    // ANALYZE blocks/pages unconditionally so fresh installs get correct query-planner
    // statistics on their second launch, after graph import has populated the table.
    // Without this, analyze_blocks migration runs on an empty DB → sqlite_stat1 shows 0 rows →
    // PRAGMA optimize permanently skips re-analysis → SCAN blocks (~1.5 s/query) forever.
    // Fails silently on first install (tables don't exist yet) via the try-catch in onConfigure.
    "ANALYZE blocks",
    "ANALYZE pages",
    // PRAGMA optimize: selectively runs ANALYZE on other tables whose statistics are
    // significantly outdated. Ensures query-planner correctness for tables not covered above.
    "PRAGMA optimize",
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
        val schema = SteleDatabase.Schema.synchronous()
        val driver = AndroidSqliteDriver(
            schema = schema,
            context = context,
            name = dbName,
            factory = RequerySQLiteOpenHelperFactory(),
            callback = WalConfiguredCallback(schema),
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
