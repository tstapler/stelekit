package dev.stapler.stelekit.db

import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.db.libsql.JvmLibsqlDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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

/**
 * Connection properties applied to every pooled JDBC connection for the main SteleKit database.
 *
 * Exposed as `internal` so [JvmDriverConnectionPropsTest] can assert critical properties without
 * constructing a real file-backed driver. Changes here are the canonical source of truth —
 * the test exists to prevent silent regressions (e.g. losing `transaction_mode=IMMEDIATE`).
 *
 * `transaction_mode=IMMEDIATE` prevents SQLITE_BUSY_SNAPSHOT: with BEGIN DEFERRED, SQLite
 * acquires a read lock at transaction start and upgrades to a write lock on the first write.
 * In WAL mode, if another connection commits between the read and the upgrade, the upgrade
 * throws SQLITE_BUSY_SNAPSHOT — which bypasses busy_timeout entirely. BEGIN IMMEDIATE acquires
 * the write lock upfront, eliminating the race. Since DatabaseWriteActor serializes all writes,
 * the write lock is never contended; this is zero-cost in practice.
 */
internal fun buildMainDbConnectionProps() = Properties().apply {
    setProperty("journal_mode", "WAL")
    setProperty("synchronous", "NORMAL")
    setProperty("foreign_keys", "true")
    setProperty("transaction_mode", "IMMEDIATE")
    setProperty("busy_timeout", "10000")
    // cache_size: negative = KiB; -32768 = 32 MB per connection.
    // 8 pool connections × 32 MB = 256 MB total page cache (JVM has more RAM headroom).
    setProperty("cache_size", "-32768")
    // temp_store=2 (MEMORY): keep sort/join temp tables in RAM, not on disk.
    setProperty("temp_store", "2")
    // wal_autocheckpoint: xerial applies Properties as PRAGMAs alphabetically, so
    // journal_mode=WAL activates before wal_autocheckpoint fires (j < w).
    setProperty("wal_autocheckpoint", "1000")
    // mmap_size=256MB: maps file pages into VA space. OS lazily maps only accessed pages.
    // Safe on 64-bit JVMs (all supported SteleKit desktop platforms).
    setProperty("mmap_size", "268435456")
    // analysis_limit=400: bound ANALYZE to 400 reservoir-sampled rows per index.
    // Applied to every pool connection so MigrationRunner.applyAll()'s ANALYZE blocks/pages
    // calls are bounded regardless of which connection the pool assigns them to.
    setProperty("analysis_limit", "400")
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
        val connectionProps = buildMainDbConnectionProps()

        // In-memory SQLite databases are connection-scoped: each connection gets a separate
        // empty database. Use a single-connection pool (poolSize=1) so schema creation and
        // all subsequent queries share the same connection. File-based databases use poolSize=8
        // for concurrent read throughput.
        val poolSize = if (jdbcUrl.contains(":memory:")) 1 else 8
        val driver = PooledJdbcSqliteDriver(jdbcUrl, connectionProps, poolSize = poolSize)

        try {
            runBlocking { SteleDatabase.Schema.create(driver).await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Log every exception — "already exists" is benign, anything else is unexpected
            // and will surface as a query failure if it was actually fatal.
            log.warning("Schema creation: ${e.message}")
        }

        runBlocking {
            // optimize=0x10002: prescribed by SQLite docs for long-lived connections.
            // Mask 0x10002 = 0x10000 (check all tables) | 0x0002 (run ANALYZE if stats missing/stale).
            // Handles fresh installs (empty sqlite_stat1) and warm starts with stale statistics.
            // PRAGMA optimize (default mask) is called at close via RepositoryFactoryImpl.close().
            driver.execute(null, "PRAGMA optimize=0x10002", 0).await()
            MigrationRunner.applyAll(driver)
        }

        return driver
    }

    fun createLibsqlDriver(dbPath: String): SqlDriver {
        File(dbPath).parentFile?.mkdirs()
        val driver = JvmLibsqlDriver(dbPath, poolSize = 8)
        try {
            runBlocking { SteleDatabase.Schema.create(driver).await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Schema already exists on an existing DB — this is expected and safe to ignore
        }
        // libsql local mode does not propagate DDL to pre-opened connections; reset the pool
        // so all connections see the freshly created schema (FTS tables, triggers, etc.).
        driver.resetPool()
        runBlocking {
            driver.execute(null, "PRAGMA optimize=0x10002", 0).await()
            MigrationRunner.applyAll(driver)
        }
        return driver
    }

    fun getLibsqlDatabasePath(graphId: String): String =
        "${getDatabaseDirectory()}/stelekit-graph-$graphId-libsql.db"

    actual fun getDatabaseUrl(graphId: String): String =
        "jdbc:sqlite:${getDatabaseDirectory()}/stelekit-graph-$graphId.db"

    actual fun getDatabaseDirectory(): String = jvmDatabaseDirectory()

    actual fun createTelemetryDriver(graphId: String): SqlDriver {
        val jdbcUrl = getTelemetryDatabaseUrl(graphId)
        val path = jdbcUrl.substringAfter("jdbc:sqlite:")
        val parentDir = java.io.File(path).parentFile
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            throw IOException("Failed to create telemetry database directory: ${parentDir.absolutePath}")
        }
        // Single connection: telemetry writes are serialized by HistogramWriter channel and
        // drain loop. poolSize=1 means no WAL snapshot issues across connections.
        val connectionProps = Properties().apply {
            setProperty("journal_mode", "WAL")
            setProperty("synchronous", "NORMAL")
            setProperty("busy_timeout", "5000")
            setProperty("cache_size", "-4096")
            setProperty("temp_store", "2")
            setProperty("wal_autocheckpoint", "1000")
        }
        val driver = PooledJdbcSqliteDriver(jdbcUrl, connectionProps, poolSize = 1)
        try {
            runBlocking { TelemetryDatabase.Schema.create(driver).await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warning("Telemetry schema creation: ${e.message}")
        }
        runBlocking {
            driver.execute(null, "PRAGMA optimize=0x10002", 0).await()
            TelemetryMigrationRunner.applyAll(driver)
        }
        return driver
    }
}

actual val defaultDatabaseUrl: String
    get() = "jdbc:sqlite:${jvmDatabaseDirectory()}/stelekit.db"

actual fun createTelemetryDatabaseInMemory(): TelemetryDatabase {
    val driver = PooledJdbcSqliteDriver("jdbc:sqlite::memory:", Properties(), poolSize = 1)
    runBlocking { TelemetryDatabase.Schema.create(driver).await() }
    return TelemetryDatabase(driver)
}
