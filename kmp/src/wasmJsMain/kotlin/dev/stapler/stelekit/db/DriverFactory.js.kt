package dev.stapler.stelekit.db

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.await

actual class DriverFactory actual constructor() {
    private var cachedDriver: WasmOpfsSqlDriver? = null

    actual fun init(context: Any) {}

    actual fun createDriver(jdbcUrl: String): SqlDriver =
        cachedDriver ?: error("createDriverAsync() must be called before createDriver() on wasmJs")

    actual fun getDatabaseUrl(graphId: String): String = "jdbc:sqlite:stelekit-graph-$graphId"
    actual fun getDatabaseDirectory(): String = "/stelekit"

    suspend fun createDriverAsync(graphId: String): WasmOpfsSqlDriver {
        val opfsPath = "/graph-${graphId}.sqlite3"
        val driver = WasmOpfsSqlDriver(workerScriptPath = "./sqlite-stelekit-worker.js")
        driver.init(opfsPath)
        SteleDatabase.Schema.create(driver).await()
        MigrationRunner.applyAll(driver)
        cachedDriver = driver
        return driver
    }
}

actual val defaultDatabaseUrl: String
    get() = "jdbc:sqlite:stelekit"
