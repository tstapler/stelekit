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
        // Retain successful ownership for the tab, but release failed startup attempts.
        val leaderLock = WebLock.tryAcquireHeld("stelekit-sqlite-driver-$graphId")
            ?: throw GraphLockedElsewhereException(
                "This graph is already open in another browser tab. Close or switch to that tab " +
                    "to continue editing there, or close it before reopening this graph here."
            )
        var driver: WasmOpfsSqlDriver? = null
        try {
            val opened = WasmOpfsSqlDriver(workerScriptPath = "./sqlite-stelekit-worker.js")
            driver = opened
            opened.init("/graph-${graphId}.sqlite3")
            check(opened.actualBackend != "memory") { "Persistent SQLite storage is unavailable. Please retry." }
            try {
                SteleDatabase.Schema.create(opened).await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Tables already exist on a persisted OPFS database — treat as benign.
            }
            MigrationRunner.applyAll(opened)
            cachedDriver = opened
            return opened
        } catch (e: Throwable) {
            driver?.terminate()
            leaderLock.release()
            throw e
        }
    }

    /**
     * The "open temporarily" flow's driver: a plain in-memory sqlite3 database, never OPFS-backed,
     * gone the moment this tab closes or reloads. Skips [WebLock.tryAcquireLeader] entirely — that
     * lock exists to serialize access to a *shared* OPFS resource other tabs could also open; an
     * in-memory database private to this JS realm has no such resource to contend over, and one
     * ephemeral session should never be blocked by (or block) another.
     */
    suspend fun createEphemeralDriverAsync(): WasmOpfsSqlDriver {
        check(cachedDriver == null) { "createEphemeralDriverAsync() called twice" }
        val driver = WasmOpfsSqlDriver(workerScriptPath = "./sqlite-stelekit-worker.js")
        try {
            driver.init(dbPath = "", mode = "ephemeral")
            SteleDatabase.Schema.create(driver).await()
            MigrationRunner.applyAll(driver)
            cachedDriver = driver
            return driver
        } catch (e: Throwable) {
            driver.terminate()
            throw e
        }
    }
}

actual fun createTelemetryDatabaseInMemory(): TelemetryDatabase =
    error("createTelemetryDatabaseInMemory is not supported on wasmJs")

actual val defaultDatabaseUrl: String
    get() = "jdbc:sqlite:stelekit"
