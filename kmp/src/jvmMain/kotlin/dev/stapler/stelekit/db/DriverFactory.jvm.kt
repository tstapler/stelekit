package dev.stapler.stelekit.db

import app.cash.sqldelight.db.SqlDriver
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
            // transaction_mode=IMMEDIATE: force BEGIN IMMEDIATE instead of BEGIN DEFERRED.
            // BEGIN DEFERRED acquires only a read lock at transaction start and upgrades to a
            // write lock on the first write. In WAL mode, if another connection commits between
            // the first read and the first write, the upgrade throws SQLITE_BUSY_SNAPSHOT —
            // which bypasses busy_timeout entirely (no retry). BEGIN IMMEDIATE acquires the
            // write lock upfront so no upgrade occurs. Since DatabaseWriteActor serializes
            // writes, the write lock is never contended; this pragma is zero-cost in practice.
            setProperty("transaction_mode", "IMMEDIATE")
            // busy_timeout=10000: wait up to 10s for a WAL write lock before SQLITE_BUSY.
            // With DatabaseWriteActor serializing all writes, contention is rare; 10s is
            // generous for a pooled-reader scenario and surfaces real deadlocks faster than 30s.
            setProperty("busy_timeout", "10000")
            // cache_size: negative = KiB; -32768 = 32 MB per connection.
            // 8 pool connections × 32 MB = 256 MB total page cache (JVM has more RAM headroom).
            setProperty("cache_size", "-32768")
            // temp_store=2 (MEMORY): keep sort/join temp tables in RAM, not on disk.
            setProperty("temp_store", "2")
            // wal_autocheckpoint=1000: trigger passive checkpoint every 1000 WAL pages (~4 MB).
            // Smaller threshold keeps the WAL compact so readers don't need to scan many frames.
            // During intensive reconcile, passive checkpoint may still lag; an explicit
            // wal_checkpoint(TRUNCATE) is issued after bulk import via onBulkImportComplete.
            // wal_autocheckpoint: xerial sqlite-jdbc applies Properties as PRAGMAs alphabetically,
            // so journal_mode=WAL is activated before wal_autocheckpoint fires (j < w). Safe with
            // the current xerial version; if driver ordering ever changes, this becomes a silent no-op.
            setProperty("wal_autocheckpoint", "1000")
            // mmap_size=256MB: memory-mapped I/O per connection. OS lazily maps accessed pages only.
            // Total across 8 pool connections: up to 2 GB virtual address space (not physical RAM).
            // Safe on 64-bit JVMs (Linux/macOS/Windows x86-64 desktop targets — all supported platforms).
            // On 32-bit JVMs this could exhaust virtual address space; however 32-bit desktop JVMs
            // are not a supported target for SteleKit.
            setProperty("mmap_size", "268435456")
        }

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
