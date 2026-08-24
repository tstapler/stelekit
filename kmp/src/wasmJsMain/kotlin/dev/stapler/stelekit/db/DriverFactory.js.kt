package dev.stapler.stelekit.db

import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.platform.WebLock
import kotlinx.coroutines.await

/**
 * Thrown by [DriverFactory.createDriverAsync] when another browser tab already holds the
 * tab-lifetime OPFS/SQLite leader lock for this graph (see [WebLock.tryAcquireLeader]). Callers
 * must not treat this the same as other driver-init failures — falling back to the demo graph
 * would silently hide a real, recoverable "open the graph in that other tab instead" situation.
 */
class GraphLockedElsewhereException(message: String) : Exception(message)

actual class DriverFactory actual constructor() {
    private var cachedDriver: WasmOpfsSqlDriver? = null

    actual fun init(context: Any) {}

    actual fun createDriver(jdbcUrl: String): SqlDriver =
        cachedDriver ?: error("createDriverAsync() must be called before createDriver() on wasmJs")

    actual fun createReadDriver(jdbcUrl: String): SqlDriver? = null  // WASM is single-threaded

    actual fun getDatabaseUrl(graphId: String): String = "jdbc:sqlite:stelekit-graph-$graphId"
    actual fun getDatabaseDirectory(): String = "/stelekit"

    actual fun createTelemetryDriver(graphId: String): SqlDriver =
        error("Telemetry database not supported on wasmJs")

    suspend fun createDriverAsync(graphId: String): WasmOpfsSqlDriver {
        check(cachedDriver == null) { "createDriverAsync() called twice for graph '$graphId'" }
        // Tab-lifetime leader election: the OPFS SQLite SyncAccessHandle Pool VFS is exclusive to
        // one tab. Without this gate, a second tab opening the same graph would fail to acquire the
        // pool and the worker would silently fall back to a disconnected :memory: database
        // (sqlite-stelekit-worker.js) — this tab's edits would then vanish on reload, since they
        // never touched OPFS. Held for the lifetime of this tab; never explicitly released.
        if (!WebLock.tryAcquireLeader("stelekit-sqlite-driver-$graphId")) {
            throw GraphLockedElsewhereException(
                "This graph is already open in another browser tab. Close or switch to that tab " +
                    "to continue editing there, or close it before reopening this graph here."
            )
        }
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

actual fun createTelemetryDatabaseInMemory(): TelemetryDatabase =
    error("createTelemetryDatabaseInMemory is not supported on wasmJs")

actual val defaultDatabaseUrl: String
    get() = "jdbc:sqlite:stelekit"
