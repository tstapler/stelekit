package dev.stapler.stelekit.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory


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

        val context = staticContext ?: throw IllegalStateException(
            "DriverFactory must be initialized with a Context before creating a driver. " +
            "Call DriverFactory().init(context) first."
        )

        // Ensure parent directory exists for absolute paths
        if (dbName.startsWith("/")) {
            java.io.File(dbName).parentFile?.mkdirs()
        }

        // AndroidSqliteDriver handles schema creation (fresh installs) and numbered .sqm
        // migrations (via SQLiteOpenHelper.onUpgrade) automatically.
        val driver = AndroidSqliteDriver(
            schema = SteleDatabase.Schema,
            context = context,
            name = dbName,
            factory = RequerySQLiteOpenHelperFactory()
        )

        // WAL mode: allows concurrent reads while a write is in progress, reducing SQLITE_BUSY.
        // busy_timeout: retry for up to 10 seconds before surfacing SQLITE_BUSY to the caller.
        try { driver.execute(null, "PRAGMA journal_mode=WAL;", 0) } catch (_: Exception) { }
        try { driver.execute(null, "PRAGMA synchronous=NORMAL;", 0) } catch (_: Exception) { }
        try { driver.execute(null, "PRAGMA busy_timeout=10000;", 0) } catch (_: Exception) { }

        // Apply incremental DDL migrations (idempotent, hash-tracked).
        MigrationRunner.applyAll(driver)

        return driver
    }

    actual fun getDatabaseUrl(graphId: String): String {
        val basePath = getDatabaseDirectory()
        return "jdbc:sqlite:$basePath/stelekit-graph-$graphId.db"
    }

    actual fun getDatabaseDirectory(): String {
        val context = staticContext ?: throw IllegalStateException("DriverFactory not initialized with a Context.")
        return context.filesDir.absolutePath
    }
}

actual val defaultDatabaseUrl: String
    get() {
        return "jdbc:sqlite:stelekit.db"
    }
