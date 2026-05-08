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
        check(cachedDriver == null) { "createDriverAsync() called twice for graph '$graphId'" }
        val opfsPath = "/graph-${graphId}.sqlite3"
        val driver = WasmOpfsSqlDriver(workerScriptPath = "./sqlite-stelekit-worker.js")
        driver.init(opfsPath)
        try {
            SteleDatabase.Schema.create(driver).await()
        } catch (_: Throwable) {
            // Tables already exist on a persisted OPFS database — treat as benign.
        }
        MigrationRunner.applyAll(driver)
        cachedDriver = driver
        return driver
    }
}

actual val defaultDatabaseUrl: String
    get() = "jdbc:sqlite:stelekit"
