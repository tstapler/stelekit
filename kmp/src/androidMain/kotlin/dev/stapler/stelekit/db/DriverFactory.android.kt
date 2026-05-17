package dev.stapler.stelekit.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking

/**
 * Custom callback that applies performance PRAGMAs in [onConfigure], which fires before
 * schema creation. This is the correct lifecycle hook for per-connection settings with
 * [RequerySQLiteOpenHelperFactory] — the factory's [CallbackSQLiteOpenHelper] delegates
 * [onConfigure] directly to this callback, guaranteeing WAL mode is set before any DDL runs.
 *
 * ADR-003: [RequerySQLiteOpenHelperFactory]'s internal [CallbackSQLiteOpenHelper] calls
 * [onConfigure] from [SupportSQLiteOpenHelper.Callback], confirmed by decompiling
 * sqlite-android-3.49.0.aar. Moving PRAGMAs here eliminates the race where post-construction
 * PRAGMA application could run in DELETE journal mode if [schema.create] was slow or threw.
 */
private class WalConfiguredCallback(
    schema: app.cash.sqldelight.db.SqlSchema<app.cash.sqldelight.db.QueryResult.Value<Unit>>,
) : AndroidSqliteDriver.Callback(schema) {
    override fun onConfigure(db: SupportSQLiteDatabase) {
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

        // WAL verification — read back journal_mode to confirm the PRAGMA applied.
        // WalConfiguredCallback.onConfigure fires before schema creation, but if the
        // RequerySQLiteOpenHelperFactory integration silently drops onConfigure on some
        // Android versions, this read-back will catch it at startup without crashing.
        // Uses runBlocking because createDriver is not a suspend fun; AndroidSqliteDriver
        // is synchronous so await() returns immediately without blocking any thread pool.
        try {
            val journalMode = runBlocking {
                driver.executeQuery(
                    identifier = null,
                    sql = "PRAGMA journal_mode;",
                    mapper = { cursor ->
                        QueryResult.Value(
                            if (cursor.next().value) cursor.getString(0) else null,
                        )
                    },
                    parameters = 0,
                ).await()
            }
            if (journalMode?.lowercase() != "wal") {
                android.util.Log.w(
                    "DriverFactory",
                    "WAL not active — journal_mode=$journalMode. " +
                        "SQLite writes will be slower. Check RequerySQLiteOpenHelperFactory onConfigure.",
                )
            } else {
                android.util.Log.d("DriverFactory", "WAL confirmed active.")
            }
        } catch (_: Exception) {
            android.util.Log.w("DriverFactory", "Could not verify journal_mode PRAGMA.")
        }

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
