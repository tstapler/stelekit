// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Regression coverage for the MAJOR review finding on PR #327:
 * `GraphManager.pendingStorageLocations` was unsynchronized and never cleared on graph removal.
 *
 * - [pendingStorageLocationEntry cleared by removeGraph] proves the leak fix: a location queued
 *   by `addGraph()` before its graph's driver ever opened is evicted by `removeGraph()`, so a
 *   later re-add of the same path (same `GraphId` = sha256(path)) never has that stale write
 *   silently applied to the new instance's database.
 * - [removeGraph deletes the storage_locations row for the removed graph] proves the DB-row
 *   deletion wired into `removeGraph()`'s only-real-graph teardown path (the one path where the
 *   graph's own per-graph database is still open at removal time — see `removeGraph()`'s KDoc).
 *
 * Both use the real SQLDELIGHT backend against an isolated on-disk data dir, per
 * `GraphManagerOnGraphLocationDeterminedTest`'s precedent, since the assertions need to observe
 * actual `storage_locations` rows (or their absence) through a fresh connection to the same file.
 */
class GraphManagerPendingStorageLocationRemovalTest {

    private var originalDevDataDir: String? = null
    private lateinit var tempDataDir: java.io.File

    @BeforeTest
    fun setUpIsolatedDataDir() {
        originalDevDataDir = System.getProperty("stelekit.devDataDir")
        tempDataDir = createTempDirectory("stelekit_pending_storage_location_removal_test_").toFile()
        System.setProperty("stelekit.devDataDir", tempDataDir.absolutePath)
    }

    @AfterTest
    fun tearDownIsolatedDataDir() {
        if (originalDevDataDir != null) {
            System.setProperty("stelekit.devDataDir", originalDevDataDir!!)
        } else {
            System.clearProperty("stelekit.devDataDir")
        }
        tempDataDir.deleteRecursively()
    }

    private class StubSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private class StubFileSystem : FileSystem {
        override fun getDefaultGraphPath() = "/test"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = true
        override fun listFiles(path: String) = emptyList<String>()
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = true
        override fun createDirectory(path: String) = true
        override fun deleteFile(path: String) = true
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
        override fun startExternalChangeDetection(scope: CoroutineScope, onChange: () -> Unit) {}
        override fun stopExternalChangeDetection() {}
    }

    private fun newManager() = GraphManager(
        platformSettings = StubSettings(),
        driverFactory = DriverFactory(),
        fileSystem = StubFileSystem(),
        defaultBackend = GraphBackend.SQLDELIGHT,
    )

    /** Reads `storage_locations` for [graphId] via a fresh connection to that graph's db file. */
    private fun selectStorageLocationRow(graphId: String): Storage_locations? {
        val url = DriverFactory().getDatabaseUrl(graphId)
        val driver = DriverFactory().createDriver(url)
        try {
            return SteleDatabase(driver).steleDatabaseQueries.selectStorageLocation(graphId).executeAsOneOrNull()
        } finally {
            driver.close()
        }
    }

    /** Reflects into the private `pendingStorageLocations` map — mirrors this repo's
     *  `GraphManagerEnrichmentCoordinatorTest` precedent for polling a fire-and-forget,
     *  mutex-guarded eviction dispatched off `removeGraph()` (not itself `suspend`). */
    @Suppress("UNCHECKED_CAST")
    private fun pendingStorageLocationsSnapshot(graphManager: GraphManager): Map<GraphId, StorageLocation> =
        GraphManager::class.java.getDeclaredField("pendingStorageLocations")
            .apply { isAccessible = true }
            .get(graphManager) as Map<GraphId, StorageLocation>

    // Task: "removeGraph() on a graph with a pending (not-yet-flushed) storage_locations write
    // clears that pending entry — a subsequent re-add of the same graph doesn't get a
    // stale-queued write applied to the new instance."
    @Test
    fun `removeGraph clears a pending storage location so a re-add is not silently applied`() = runBlocking {
        val runId = System.nanoTime()
        val path = "/test/graph-pending-removal-$runId"
        val graphManager = newManager()

        // addGraph() runs before any switchGraph() for this graph, so onGraphLocationDetermined
        // has no live driver to write through yet and queues the location instead.
        val graphId = graphManager.addGraph(path, location = StorageLocation.SafFolder("g", "tree://stale"))
        assertTrue(
            pendingStorageLocationsSnapshot(graphManager).containsKey(graphId),
            "precondition: the location must be queued, not yet flushed",
        )

        val removed = graphManager.removeGraph(graphId)
        assertTrue(removed)

        // removeGraph()'s eviction is dispatched fire-and-forget (see evictPendingStorageLocationFor's
        // KDoc) — poll for it to land, same idiom as GraphManagerEnrichmentCoordinatorTest.
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (pendingStorageLocationsSnapshot(graphManager).containsKey(graphId)) {
                    delay(20)
                }
            }
        }

        // Re-add the identical path — GraphId = sha256(path), so this reconstructs the same id —
        // with NO location this time, and open it for real. If the pending entry had leaked, the
        // stale SafFolder("tree://stale") location would be silently written to this new
        // instance's storage_locations row purely because switchGraph()'s flush block still found
        // it queued, even though this addGraph() call passed location = null.
        val reAddedGraphId = graphManager.addGraph(path)
        assertTrue(reAddedGraphId == graphId, "sanity check: re-adding the same path must reconstruct the same GraphId")
        graphManager.switchGraph(reAddedGraphId)
        graphManager.awaitPendingMigration() ?: error("Failed to open graph — database did not initialise")
        graphManager.shutdown()

        assertNull(
            selectStorageLocationRow(reAddedGraphId.value),
            "no storage_locations row should exist — the stale pending write must not have been replayed",
        )
    }

    // Task: "if you successfully wire the DB-row deletion too, a test proving the row is
    // actually gone after removal."
    @Test
    fun `removeGraph deletes the storage_locations row for the removed graph`() = runBlocking {
        val runId = System.nanoTime()
        val path = "/test/graph-row-removal-$runId"
        val graphManager = newManager()

        val graphId = graphManager.addGraph(path, location = StorageLocation.AppOwned("g"))
        graphManager.switchGraph(graphId)
        graphManager.awaitPendingMigration() ?: error("Failed to open graph — database did not initialise")

        // Precondition: switchGraph()'s flush block actually wrote the row.
        assertTrue(selectStorageLocationRow(graphId.value) != null, "precondition: the row must exist before removal")

        // graphId is the sole real graph and active, so removeGraph() takes the only-real-graph
        // teardown path where its writeActor is still reachable to delete the row.
        val removed = graphManager.removeGraph(graphId)
        assertTrue(removed)

        // The delete-then-close is dispatched fire-and-forget on PlatformDispatcher.IO (see
        // deleteStorageLocationRowThenCloseFactory's KDoc) — poll for the row to actually
        // disappear via a fresh connection, real time (not runTest's virtual clock).
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (selectStorageLocationRow(graphId.value) != null) {
                    delay(20)
                }
            }
        }

        assertNull(selectStorageLocationRow(graphId.value), "storage_locations row must be gone after removal")
    }
}
