package dev.stapler.stelekit.db

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.logging.Logger

private val log = Logger.getLogger("DriverFactory")

private fun jvmDatabaseDirectory(): String {
    val os = System.getProperty("os.name").lowercase()
    val userHome = System.getProperty("user.home")
    return when {
        os.contains("win") -> {
            val appData = System.getenv("APPDATA") ?: "$userHome\\AppData\\Roaming"
            "$appData\\SteleKit"
        }
        os.contains("mac") -> "$userHome/Library/Application Support/SteleKit"
        else -> { // Linux and others
            val xdgData = System.getenv("XDG_DATA_HOME") ?: "$userHome/.local/share"
            "$xdgData/stelekit"
        }
    }
}

actual class DriverFactory actual constructor() {
    actual fun init(context: Any) {
        // No-op on JVM
    }

    actual fun createDriver(jdbcUrl: String): SqlDriver {
        if (jdbcUrl.startsWith("jdbc:sqlite:") && !jdbcUrl.contains(":memory:")) {
            val path = jdbcUrl.substringAfter("jdbc:sqlite:")
            val parentDir = File(path).parentFile
            if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
                throw IOException("Failed to create database directory: ${parentDir.absolutePath}")
            }
        }

        // Properties are applied to every connection the pool creates at startup.
        // WAL enables concurrent reads alongside a single write. busy_timeout prevents
        // immediate SQLITE_BUSY failures when the write actor briefly holds the write lock.
        val connectionProps = Properties().apply {
            setProperty("journal_mode", "WAL")
            setProperty("synchronous", "NORMAL")
            setProperty("foreign_keys", "true")
            setProperty("busy_timeout", "30000")
        }

        // In-memory SQLite databases are connection-scoped: each connection gets a separate
        // empty database. Use a single-connection pool (poolSize=1) so schema creation and
        // all subsequent queries share the same connection. File-based databases use poolSize=8
        // for concurrent read throughput.
        val poolSize = if (jdbcUrl.contains(":memory:")) 1 else 8
        val driver = PooledJdbcSqliteDriver(jdbcUrl, connectionProps, poolSize = poolSize)

        try {
            SteleDatabase.Schema.create(driver)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Log every exception — "already exists" is benign, anything else is unexpected
            // and will surface as a query failure if it was actually fatal.
            log.warning("Schema creation: ${e.message}")
        }

        MigrationRunner.applyAll(driver)

        return driver
    }

    actual fun getDatabaseUrl(graphId: String): String =
        "jdbc:sqlite:${getDatabaseDirectory()}/stelekit-graph-$graphId.db"

    actual fun getDatabaseDirectory(): String = jvmDatabaseDirectory()
}

actual val defaultDatabaseUrl: String
    get() = "jdbc:sqlite:${jvmDatabaseDirectory()}/stelekit.db"
