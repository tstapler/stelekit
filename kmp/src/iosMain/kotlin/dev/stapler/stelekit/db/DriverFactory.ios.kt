package dev.stapler.stelekit.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlinx.coroutines.runBlocking

actual class DriverFactory actual constructor() {
    actual fun init(context: Any) {
        // No-op on iOS
    }

    actual fun createDriver(jdbcUrl: String): SqlDriver {
        val dbName = jdbcUrl.substringAfter("jdbc:sqlite:")
        return NativeSqliteDriver(SteleDatabase.Schema, dbName)
    }

    actual fun createReadDriver(jdbcUrl: String): SqlDriver? = null  // NativeSqliteDriver is single-threaded

    actual fun getDatabaseUrl(graphId: String): String = "jdbc:sqlite:stelekit-graph-$graphId.db"
    actual fun getDatabaseDirectory(): String = "."

    actual fun createTelemetryDriver(graphId: String): SqlDriver {
        val dbName = "stelekit-telemetry-$graphId.db"
        val driver = NativeSqliteDriver(TelemetryDatabase.Schema, dbName)
        runBlocking { TelemetryMigrationRunner.applyAll(driver) }
        return driver
    }
}

actual val defaultDatabaseUrl: String
    get() = "jdbc:sqlite:stelekit.db"

actual fun createTelemetryDatabaseInMemory(): TelemetryDatabase {
    error("createTelemetryDatabaseInMemory is not supported on iOS — use createTelemetryDriver for production use")
}
