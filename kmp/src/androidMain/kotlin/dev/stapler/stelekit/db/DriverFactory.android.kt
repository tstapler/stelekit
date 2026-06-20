package dev.stapler.stelekit.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
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
    // busy_timeout=5000: wait up to 5s for a WAL write lock before returning SQLITE_BUSY.
    // With DatabaseWriteActor serializing all writes, SQLite-level lock contention is
    // rare (only during WAL checkpoint); 5s is generous and surfaces real deadlocks faster
    // than the previous 10s.
    "PRAGMA busy_timeout=5000",
    "PRAGMA temp_store=MEMORY",
    // cache_size=-5000: 5 MB page cache. mmap_size=64MB already serves hot reads via OS
    // virtual memory, so the cache primarily buffers WAL frames awaiting checkpoint.
    // Reduced from 8 MB to recover ~3 MB heap on memory-constrained devices.
    "PRAGMA cache_size=-5000",
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
    // analysis_limit=400: bound ANALYZE to 400 reservoir-sampled rows per index.
    // Without this, ANALYZE blocks/pages in MigrationRunner.applyAll() scans ALL rows —
    // on a 50 000-row blocks table this takes 5+ seconds on Android's single connection,
    // blocking every concurrent read for the duration. 400 samples are accurate enough
    // for the planner to prefer idx_blocks_page_position over a full heap scan.
    // Must be set before optimize=0x10002 (which may also trigger ANALYZE internally).
    "PRAGMA analysis_limit=400",
    // optimize=0x10002: prescribed by SQLite docs for long-lived connections (DB open for
    // the app lifetime). Mask 0x10002 = 0x10000 (check all tables, not just recently used)
    // | 0x0002 (run ANALYZE if statistics are missing or stale).
    // On first install, sqlite_stat1 is empty → optimize triggers ANALYZE automatically,
    // avoiding the permanent SCAN plans that the old unconditional ANALYZE blocks/pages
    // workaround was compensating for. On warm starts with fresh stats, optimize is a no-op.
    // PRAGMA optimize (default mask 0xfffe) is called at close via RepositoryFactoryImpl.close()
    // to persist stats for tables used during the session; process kills skip that call —
    // the 0x10000 flag here covers the next open in that case.
    "PRAGMA optimize=0x10002",
)

/**
 * Applies performance PRAGMAs in [onConfigure] via [SupportSQLiteDatabase.query] (rawQuery path).
 *
 * Uses the rawQuery path ([query]) rather than [SupportSQLiteDatabase.execSQL] because
 * SET-type PRAGMAs return a result set; [query] handles both write and read variants uniformly.
 * The cursor is discarded by calling [close].
 *
 * [onConfigure] fires before schema creation, ensuring WAL is in effect for all DDL.
 */
private class WalConfiguredCallback(
    schema: app.cash.sqldelight.db.SqlSchema<app.cash.sqldelight.db.QueryResult.Value<Unit>>,
) : AndroidSqliteDriver.Callback(schema) {
    override fun onConfigure(db: SupportSQLiteDatabase) {
        super.onConfigure(db) // preserves foreign-key enforcement and other AndroidSqliteDriver defaults
        // Sets ENABLE_WRITE_AHEAD_LOGGING on Android's SQLiteConnectionPool, allowing the pool
        // to issue non-primary read connections. Without this call the pool serializes all reads
        // on the primary connection regardless of the WAL journal mode set by PRAGMA below.
        db.enableWriteAheadLogging()
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
            factory = FrameworkSQLiteOpenHelperFactory(),
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

    actual fun createTelemetryDriver(graphId: String): SqlDriver {
        val dbPath = getTelemetryDatabaseUrl(graphId).substringAfter("jdbc:sqlite:")
        val context = staticContext ?: error("DriverFactory not initialized with a Context.")
        if (dbPath.startsWith("/")) {
            java.io.File(dbPath).parentFile?.mkdirs()
        }
        val schema = TelemetryDatabase.Schema.synchronous()
        val driver = AndroidSqliteDriver(
            schema = schema,
            context = context,
            name = dbPath,
            factory = FrameworkSQLiteOpenHelperFactory(),
            callback = object : AndroidSqliteDriver.Callback(schema) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    super.onConfigure(db)
                    db.enableWriteAheadLogging()
                    ANDROID_PRAGMAS.forEach { pragma ->
                        try { db.query(pragma).close() } catch (_: Exception) { }
                    }
                }
            },
        )
        runBlocking { TelemetryMigrationRunner.applyAll(driver) }
        return driver
    }
}

actual val defaultDatabaseUrl: String
    get() {
        return "jdbc:sqlite:stelekit.db"
    }

actual fun createTelemetryDatabaseInMemory(): TelemetryDatabase {
    error("createTelemetryDatabaseInMemory is not supported on Android — use createTelemetryDriver for production use")
}
