package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for the GraphManager.init auto-restore behaviour introduced to fix Android widget
 * hibernation: when the app process is killed and restarted (e.g. by the widget), GraphManager
 * should automatically reconnect the previously-active graph's database — UNLESS the graph is
 * in paranoid mode (encrypted vault), in which case the user must unlock via the main UI first.
 */
class GraphManagerInitAutoRestoreTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class StubSettings(initial: Map<String, String> = emptyMap()) : Settings {
        private val store = mutableMapOf<String, String>().also { it.putAll(initial) }
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private open class StubFileSystem : FileSystem {
        override fun getDefaultGraphPath() = "/tmp"
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

    private fun makeSettings(graphId: String, isParanoidMode: Boolean): StubSettings {
        val graphInfo = GraphInfo(
            id = GraphId(graphId),
            path = "/test/graph",
            displayName = "Test Graph",
            addedAt = 0L,
            isParanoidMode = isParanoidMode,
        )
        val registry = GraphRegistry(
            activeGraphId = GraphId(graphId),
            graphs = listOf(graphInfo),
        )
        return StubSettings(mapOf("graph_registry" to json.encodeToString(registry)))
    }

    @Test
    fun `init auto-restores non-paranoid active graph`() = runTest {
        val graphId = "aabbccdd11223344"  // fixed ID that matches the registry entry
        val settings = makeSettings(graphId, isParanoidMode = false)

        val graphManager = GraphManager(
            platformSettings = settings,
            driverFactory = DriverFactory(),
            fileSystem = StubFileSystem(),
            defaultBackend = GraphBackend.IN_MEMORY,
        )

        graphManager.awaitPendingMigration()

        assertNotNull(
            graphManager.getActiveRepositorySet(),
            "GraphManager.init should auto-restore the previously-active non-paranoid graph " +
                "so the widget has a live RepositorySet without the user re-opening the app.",
        )
    }

    @Test
    fun `init does NOT auto-restore paranoid-mode active graph`() = runTest {
        val graphId = "aabbccdd11223344"
        val settings = makeSettings(graphId, isParanoidMode = true)

        val graphManager = GraphManager(
            platformSettings = settings,
            driverFactory = DriverFactory(),
            fileSystem = StubFileSystem(),
            defaultBackend = GraphBackend.IN_MEMORY,
        )

        graphManager.awaitPendingMigration()

        assertNull(
            graphManager.getActiveRepositorySet(),
            "GraphManager.init must NOT auto-restore a paranoid-mode (encrypted vault) graph. " +
                "The user must unlock the vault through the main app UI before the DB is exposed.",
        )
    }
}
