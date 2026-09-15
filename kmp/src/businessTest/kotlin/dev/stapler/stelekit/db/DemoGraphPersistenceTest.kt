package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.DEMO_GRAPH_ID
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DemoGraphPersistenceTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class InMemorySettings(initial: Map<String, String> = emptyMap()) : Settings {
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

    private fun makeGraphManager(settings: InMemorySettings = InMemorySettings()) = GraphManager(
        platformSettings = settings,
        driverFactory = DriverFactory(),
        fileSystem = StubFileSystem(),
        defaultBackend = GraphBackend.IN_MEMORY,
    )

    // T-1
    @Test
    fun `demo graph is not persisted to registry after addDemoGraph`() {
        val settings = InMemorySettings()
        val graphManager = makeGraphManager(settings)

        graphManager.addDemoGraph()

        val persisted = settings.getString("graph_registry", "")
        assertFalse(
            persisted.contains("__demo__"),
            "Persisted registry must not contain __demo__ after addDemoGraph()",
        )
        assertFalse(
            persisted.contains("isDemo"),
            "Persisted registry must not contain isDemo after addDemoGraph()",
        )
    }

    // T-2
    @Test
    fun `switchGraph for demo does not persist demo entry`() {
        val settings = InMemorySettings()
        val graphManager = makeGraphManager(settings)

        graphManager.addDemoGraph()
        graphManager.switchGraph(DEMO_GRAPH_ID)

        val persisted = settings.getString("graph_registry", "")
        assertFalse(
            persisted.contains("__demo__"),
            "Persisted registry must not contain __demo__ after switchGraph(DEMO_GRAPH_ID)",
        )
        // activeGraphId should be null in the persisted JSON — either absent or explicitly null
        val decoded = if (persisted.isNotEmpty()) json.decodeFromString<GraphRegistry>(persisted) else GraphRegistry()
        assertEquals(
            null,
            decoded.activeGraphId,
            "Persisted activeGraphId must be null when demo is active",
        )
    }

    // T-3
    @Test
    fun `addDemoGraph is idempotent`() {
        val graphManager = makeGraphManager()

        graphManager.addDemoGraph()
        graphManager.addDemoGraph()

        val demoCount = graphManager.graphRegistry.value.graphs.count { it.id == DEMO_GRAPH_ID }
        assertEquals(1, demoCount, "addDemoGraph() called twice must produce exactly one demo entry in graphRegistry")
    }

    // T-4
    @Test
    fun `demo graph is stripped on registry load when isDemo flag is set`() {
        val demoJson = json.encodeToString(
            GraphRegistry(
                activeGraphId = DEMO_GRAPH_ID,
                graphs = listOf(
                    GraphInfo(
                        id = DEMO_GRAPH_ID,
                        path = "/demo",
                        displayName = "Demo Graph",
                        addedAt = 1000L,
                        isDemo = true,
                    )
                ),
            )
        )
        val settings = InMemorySettings(mapOf("graph_registry" to demoJson))

        val graphManager = makeGraphManager(settings)

        assertFalse(
            graphManager.graphRegistry.value.graphs.any { it.isDemo },
            "No isDemo entries should survive loadRegistry()",
        )
        assertEquals(
            null,
            graphManager.graphRegistry.value.activeGraphId,
            "activeGraphId should be null after stripping the only demo entry",
        )
    }

    // T-5
    @Test
    fun `loadRegistry resets onboardingCompleted when only demo entry existed`() {
        val demoJson = json.encodeToString(
            GraphRegistry(
                activeGraphId = DEMO_GRAPH_ID,
                graphs = listOf(
                    GraphInfo(
                        id = DEMO_GRAPH_ID,
                        path = "/demo",
                        displayName = "Demo Graph",
                        addedAt = 1000L,
                        isDemo = true,
                    )
                ),
            )
        )
        val settings = InMemorySettings(
            mapOf(
                "graph_registry" to demoJson,
                "onboardingCompleted" to "true",
            )
        )

        makeGraphManager(settings)

        assertFalse(
            settings.getBoolean("onboardingCompleted", true),
            "onboardingCompleted must be reset to false when the only registry entry was a demo entry",
        )
    }

    // T-6
    @Test
    fun `loadRegistry retains real graphs and strips only demo entries`() {
        val realId = GraphId("aabbccdd11223344")
        val demoJson = json.encodeToString(
            GraphRegistry(
                activeGraphId = realId,
                graphs = listOf(
                    GraphInfo(
                        id = realId,
                        path = "/home/user/notes",
                        displayName = "notes",
                        addedAt = 1000L,
                    ),
                    GraphInfo(
                        id = DEMO_GRAPH_ID,
                        path = "/demo",
                        displayName = "Demo Graph",
                        addedAt = 2000L,
                        isDemo = true,
                    ),
                ),
            )
        )
        val settings = InMemorySettings(mapOf("graph_registry" to demoJson))

        val graphManager = makeGraphManager(settings)

        val graphs = graphManager.graphRegistry.value.graphs
        assertEquals(1, graphs.size, "Only the real graph entry should survive after loadRegistry()")
        assertEquals(realId, graphs.first().id, "The surviving entry must be the real graph")
        assertTrue(graphs.none { it.isDemo }, "No demo entries should remain in graphRegistry after load")
    }

    // T-7
    @Test
    fun `renameGraph returns false for demo graph`() {
        val graphManager = makeGraphManager()

        graphManager.addDemoGraph()
        val result = graphManager.renameGraph(DEMO_GRAPH_ID, "Custom Name")

        assertFalse(result, "renameGraph() must return false for the demo graph")
        val demoEntry = graphManager.graphRegistry.value.graphs.first { it.id == DEMO_GRAPH_ID }
        assertEquals(
            "Demo Graph",
            demoEntry.displayName,
            "displayName must remain 'Demo Graph' after a rejected rename",
        )
    }
}
