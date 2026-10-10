package dev.stapler.stelekit.db

import app.cash.sqldelight.db.SqlDriver

actual fun pragmaOptimizeAndClose(driver: SqlDriver?) {
    // PRAGMA optimize is not applicable to the WASM/JS in-memory driver.
    try { driver?.close() } catch (_: Exception) {}
}
