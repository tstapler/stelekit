package dev.stapler.stelekit.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.stapler.stelekit.db.libsql.AndroidLibsqlDriver
import dev.stapler.stelekit.platform.PlatformSettings
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import java.util.logging.Logger
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
        private val log = Logger.getLogger("DriverFactory")
        private val settings: PlatformSettings by lazy { PlatformSettings() }

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

        // Runtime feature flag: use the libsql JNI driver when enabled in developer settings.
        // Reads from the same PlatformSettings store that the Settings UI toggle writes to.
        // Takes effect on the next graph open; a restart is not required.
        val useLibsql = try { settings.getBoolean("db.libsql.enabled", false) }
                        catch (_: Exception) { false }
        if (useLibsql && !dbName.startsWith("/")) {
            log.warning("libsql driver enabled but '$dbName' is not an absolute path; falling back to system SQLite")
        }
        if (useLibsql && dbName.startsWith("/")) {
            val driver = AndroidLibsqlDriver(dbName)
            runBlocking {
                try { SteleDatabase.Schema.create(driver).await() } catch (_: Exception) { }
                driver.resetPool()
                MigrationRunner.applyAll(driver)
            }
            return driver
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
