package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

/**
 * Task 1.1.3g — `onGraphLocationDetermined` is the single seam every creation/relocate call site
 * uses to write `storage_locations` (Story 1.1.3's acceptance criteria).
 *
 * `addGraph()` runs before its caller's own `switchGraph()` opens the graph's database, so a
 * location passed to `addGraph()` cannot be written immediately — see `onGraphLocationDetermined`'s
 * KDoc. These tests exercise the real flush path (`switchGraph()` + `awaitPendingMigration()`)
 * rather than mocking the seam, per this repo's `GraphManagerDatabaseLifecycleTest` precedent, and
 * read the persisted row back through a second driver connection to the same on-disk database
 * file — the same file `GraphManager`'s own driver just wrote to.
 */
class GraphManagerOnGraphLocationDeterminedTest {

    // Real SQLite files are written to DriverFactory's dev-data directory unless redirected —
    // see GraphManagerDatabaseLifecycleTest's identical isolation setup for why this is mandatory.
    private var originalDevDataDir: String? = null
    private lateinit var tempDataDir: java.io.File

    @BeforeTest
    fun setUpIsolatedDataDir() {
        originalDevDataDir = System.getProperty("stelekit.devDataDir")
        tempDataDir = createTempDirectory("stelekit_storage_location_seam_test_").toFile()
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

    // TC-1.1.3g-001 (REQ-1, validation.md)
    @Test
    fun `onGraphLocationDetermined writes exactly once when addGraph is called with a location`() = runBlocking {
        val runId = System.nanoTime()
        val graphManager = newManager()

        // The location's own graphId field is a domain-model detail unrelated to the row written —
        // onGraphLocationDetermined always keys the row by addGraph()'s own computed graphId.
        val graphId = graphManager.addGraph(
            "/test/graph-$runId",
            location = StorageLocation.AppOwned("placeholder"),
        )
        graphManager.switchGraph(graphId)
        graphManager.awaitPendingMigration() ?: error("Failed to open graph — database did not initialise")
        graphManager.shutdown()

        val row = selectStorageLocationRow(graphId.value)

        assertEquals(graphId.value, row?.graph_id)
        assertEquals("AppOwned", row?.kind)
    }

    // TC-1.1.3g-002 — regression: no location param must never write a row.
    @Test
    fun `addGraph without a location does not write a storage_locations row`() = runBlocking {
        val runId = System.nanoTime()
        val graphManager = newManager()

        val graphId = graphManager.addGraph("/test/graph-noloc-$runId")
        graphManager.switchGraph(graphId)
        graphManager.awaitPendingMigration() ?: error("Failed to open graph — database did not initialise")
        graphManager.shutdown()

        assertNull(selectStorageLocationRow(graphId.value))
    }
}
