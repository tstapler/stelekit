package dev.stapler.stelekit.db

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.runBlocking

actual fun pragmaOptimizeAndClose(driver: SqlDriver?) {
    try { runBlocking { driver?.execute(null, "PRAGMA optimize", 0)?.await() } } catch (_: Exception) {}
    try { driver?.close() } catch (_: Exception) {}
}
