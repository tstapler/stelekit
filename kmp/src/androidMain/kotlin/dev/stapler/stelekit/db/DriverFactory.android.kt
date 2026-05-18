package dev.stapler.stelekit.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking

/**
 * Custom callback that applies performance PRAGMAs in [onOpen], which fires after schema
 * creation and is unrestricted for [execSQL] with [RequerySQLiteOpenHelperFactory].
 *
 * ADR-003 update: [RequerySQLiteOpenHelperFactory]'s [CallbackSQLiteOpenHelper] restricts
 * [execSQL] during [onConfigure] (throws "Queries can be performed using SQLiteDatabase
 * query or rawQuery methods only"). Moving PRAGMAs to [onOpen] avoids this restriction.
 * WAL mode is persistent across connections once set, so [onOpen] is the correct hook.
 */
private class WalConfiguredCallback(
    schema: app.cash.sqldelight.db.SqlSchema<app.cash.sqldelight.db.QueryResult.Value<Unit>>,
) : AndroidSqliteDriver.Callback(schema) {
    override fun onConfigure(db: SupportSQLiteDatabase) {
        super.onConfigure(db) // preserves AndroidSqliteDriver.Callback defaults (foreign keys, etc.)
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        // WAL mode: allows concurrent reads while a write is in progress, reducing SQLITE_BUSY.
        // busy_timeout: retry for up to 10 seconds before surfacing SQLITE_BUSY to the caller.
        // wal_autocheckpoint=4000: reduce checkpoint frequency on write-heavy workloads (default=1000).
        // temp_store=MEMORY: keeps temp tables in RAM instead of hitting Android's storage.
        // cache_size=-8000: 8MB page cache reduces repeated reads for large graphs (1000+ pages).
        db.execSQL("PRAGMA journal_mode=WAL;")
        db.execSQL("PRAGMA synchronous=NORMAL;")
        db.execSQL("PRAGMA busy_timeout=10000;")
        db.execSQL("PRAGMA wal_autocheckpoint=4000;")
        db.execSQL("PRAGMA temp_store=MEMORY;")
        db.execSQL("PRAGMA cache_size=-8000;")
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
        // WalConfiguredCallback applies all PRAGMAs in onConfigure (before schema creation)
        // via RequerySQLiteOpenHelperFactory's CallbackSQLiteOpenHelper delegation — see ADR-003.
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
