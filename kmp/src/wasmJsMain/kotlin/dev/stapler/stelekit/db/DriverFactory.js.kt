package dev.stapler.stelekit.db

import app.cash.sqldelight.db.SqlDriver

actual class DriverFactory actual constructor() {
    actual fun init(context: Any) {}
    actual fun createDriver(jdbcUrl: String): SqlDriver {
        // Phase B: replace with @sqlite.org/sqlite-wasm driver
        throw UnsupportedOperationException("Use RepositoryBackend.IN_MEMORY for browser demo")
    }
    actual fun getDatabaseUrl(graphId: String): String = "jdbc:sqlite:stelekit-graph-$graphId"
    actual fun getDatabaseDirectory(): String = "/stelekit"
}

actual val defaultDatabaseUrl: String
    get() = "jdbc:sqlite:stelekit"
