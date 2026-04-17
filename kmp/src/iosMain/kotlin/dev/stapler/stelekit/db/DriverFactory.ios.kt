package dev.stapler.stelekit.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual class DriverFactory actual constructor() {
    actual fun init(context: Any) {
        // No-op on iOS
    }

    actual fun createDriver(jdbcUrl: String): SqlDriver {
        val dbName = jdbcUrl.substringAfter("jdbc:sqlite:")
        return NativeSqliteDriver(SteleDatabase.Schema, dbName)
    }
}

actual val defaultDatabaseUrl: String
    get() = "jdbc:sqlite:stelekit.db"
