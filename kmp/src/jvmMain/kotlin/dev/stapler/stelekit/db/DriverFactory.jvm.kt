package dev.stapler.stelekit.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.Properties

actual class DriverFactory actual constructor() {
    actual fun init(context: Any) {
        // No-op on JVM
    }

    actual fun createDriver(jdbcUrl: String): SqlDriver {
        // Ensure parent directory exists for file-based URLs
        if (jdbcUrl.startsWith("jdbc:sqlite:") && !jdbcUrl.contains(":memory:")) {
            val path = jdbcUrl.substringAfter("jdbc:sqlite:")
            File(path).parentFile?.mkdirs()
        }

        // SQLDelight 2.x uses ThreadedConnectionManager which creates a new JDBC connection
        // per JVM thread. PRAGMA statements executed after driver construction only apply to
        // the initial thread's connection — new IO-pool threads get fresh connections with
        // default settings (busy_timeout=0, meaning immediate SQLITE_BUSY on contention).
        // Passing these as Properties to JdbcSqliteDriver causes sqlite-jdbc to apply them
        // as PRAGMAs for every newly-created connection, preventing SQLITE_BUSY during
        // concurrent bulk indexing.
        val connectionProps = Properties().apply {
            setProperty("journal_mode", "WAL")
            setProperty("synchronous", "NORMAL")
            setProperty("foreign_keys", "true")
            setProperty("busy_timeout", "30000") // 30 s for ALL per-thread connections
        }
        val driver = JdbcSqliteDriver(jdbcUrl, connectionProps)

        // Create the full schema on fresh databases; silently ignored on existing ones.
        try {
            SteleDatabase.Schema.create(driver)
        } catch (_: Exception) { }

        // Apply incremental DDL migrations (idempotent, hash-tracked).
        MigrationRunner.applyAll(driver)

        return driver
    }

    actual fun getDatabaseUrl(graphId: String): String {
        val basePath = getDatabaseDirectory()
        return "jdbc:sqlite:$basePath/stelekit-graph-$graphId.db"
    }

    actual fun getDatabaseDirectory(): String {
        val os = System.getProperty("os.name").lowercase()
        val userHome = System.getProperty("user.home")

        return when {
            os.contains("win") -> {
                val appData = System.getenv("APPDATA") ?: "$userHome\\AppData\\Roaming"
                "$appData\\SteleKit"
            }
            os.contains("mac") -> {
                "$userHome/Library/Application Support/SteleKit"
            }
            else -> { // Linux and others
                val xdgData = System.getenv("XDG_DATA_HOME") ?: "$userHome/.local/share"
                "$xdgData/stelekit"
            }
        }
    }
}

actual val defaultDatabaseUrl: String
    get() {
        val os = System.getProperty("os.name").lowercase()
        val userHome = System.getProperty("user.home")

        val basePath = when {
            os.contains("win") -> {
                val appData = System.getenv("APPDATA") ?: "$userHome\\AppData\\Roaming"
                "$appData\\SteleKit"
            }
            os.contains("mac") -> {
                "$userHome/Library/Application Support/SteleKit"
            }
            else -> { // Linux and others
                val xdgData = System.getenv("XDG_DATA_HOME") ?: "$userHome/.local/share"
                "$xdgData/stelekit"
            }
        }
        return "jdbc:sqlite:$basePath/stelekit.db"
    }
