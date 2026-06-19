package dev.stapler.stelekit.db

import app.cash.sqldelight.db.SqlDriver

/**
 * Platform-specific driver factory for SQLDelight.
 */
expect class DriverFactory() {
    /**
     * Initialize the driver factory with a platform-specific context.
     */
    fun init(context: Any)

    /**
     * Create a SQLDelight driver for the platform.
     * @param jdbcUrl The JDBC connection string (e.g. "jdbc:sqlite:logseq.db" or "jdbc:sqlite::memory:")
     */
    fun createDriver(jdbcUrl: String): SqlDriver

    /**
     * Get the database URL for a specific graph.
     */
    fun getDatabaseUrl(graphId: String): String

    /**
     * Get the directory where databases are stored.
     */
    fun getDatabaseDirectory(): String

    /**
     * Create a driver for the separate telemetry database (spans, query_stats, histograms, debug flags).
     * Uses a single connection to avoid WAL snapshot contention with the content database.
     */
    fun createTelemetryDriver(graphId: String): SqlDriver
}

fun DriverFactory.getTelemetryDatabaseUrl(graphId: String): String =
    "jdbc:sqlite:${getDatabaseDirectory()}/stelekit-telemetry-$graphId.db"

/**
 * Returns the default JDBC URL for the platform.
 */
expect val defaultDatabaseUrl: String

/**
 * Extension function to initialize the database with platform-specific driver.
 */
/**
 * Creates a [SteleDatabase] backed by [jdbcUrl].
 * Incremental schema migrations are applied inside [DriverFactory.createDriver]
 * via [MigrationRunner], so the returned database is always fully up-to-date.
 */
fun createDatabase(driverFactory: DriverFactory, jdbcUrl: String): SteleDatabase {
    val driver = driverFactory.createDriver(jdbcUrl)
    return SteleDatabase(driver)
}

expect fun createTelemetryDatabaseInMemory(): TelemetryDatabase
