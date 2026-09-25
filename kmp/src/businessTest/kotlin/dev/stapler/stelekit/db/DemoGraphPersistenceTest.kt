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

    // synchronized: a real Settings backend (SharedPreferences, UserDefaults) is thread-safe;
    // a plain mutableMapOf is not, and the concurrent-writer test below calls putString from
    // multiple real threads — without this, a bare HashMap could throw
    // ConcurrentModificationException, masking the _graphRegistry race this file is testing for.
    private class InMemorySettings(initial: Map<String, String> = emptyMap()) : Settings {
        private val store = mutableMapOf<String, String>().also { it.putAll(initial) }
        override fun getBoolean(key: String, defaultValue: Boolean) = synchronized(store) { store[key]?.toBoolean() ?: defaultValue }
        override fun putBoolean(key: String, value: Boolean) { synchronized(store) { store[key] = value.toString() } }
        override fun getString(key: String, defaultValue: String) = synchronized(store) { store.getOrDefault(key, defaultValue) }
        override fun putString(key: String, value: String) { synchronized(store) { store[key] = value } }
        override fun containsKey(key: String) = synchronized(store) { store.containsKey(key) }
    }

    private open class StubFileSystem : FileSystem {
        override fun getDefaultGraphPath() = "/tmp"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = true
        override fun listFiles(path: String) = emptyList<String>()
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = false
        // false, not true: claiming every path (incl. "<path>/.git") exists made addGraph's
        // fire-and-forget detectGitRoot() fire a real background write against a discarded
        // GraphManager instance, causing a real CI flake (see the concurrency test below).
        override fun directoryExists(path: String) = false
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

    @Test
    fun `addGraph honours name and description and they survive a registry reload`() = kotlinx.coroutines.test.runTest {
        val settings = InMemorySettings()
        val id = makeGraphManager(settings).addGraph("/tmp/some-folder", null, "  Work notes ", " Team wiki ")

        val info = makeGraphManager(settings).graphRegistry.value.graphs.first { it.id == id }
        assertEquals("Work notes", info.displayName)
        assertEquals("Team wiki", info.description)
    }

    @Test
    fun `addGraph falls back to folder name and empty description`() = kotlinx.coroutines.test.runTest {
        val gm = makeGraphManager()
        val id = gm.addGraph("/tmp/some-folder")
        val info = gm.graphRegistry.value.graphs.first { it.id == id }
        assertEquals("some-folder", info.displayName)
        assertEquals("", info.description)
    }

    @Test
    fun `updateGraphDescription updates a normal graph's description and it survives a registry reload`() =
        kotlinx.coroutines.test.runTest {
            val settings = InMemorySettings()
            val id = makeGraphManager(settings).addGraph("/tmp/some-folder", null, "Work notes", "Original description")

            val result = makeGraphManager(settings).updateGraphDescription(id, " Updated description ")
            assertTrue(result, "updateGraphDescription() must return true for a normal graph")

            val reloaded = makeGraphManager(settings)
            val info = reloaded.graphRegistry.value.graphs.first { it.id == id }
            assertEquals("Updated description", info.description)
        }

    @Test
    fun `updateGraphDescription returns false for demo graph`() {
        val graphManager = makeGraphManager()

        graphManager.addDemoGraph()
        val result = graphManager.updateGraphDescription(DEMO_GRAPH_ID, "Custom Description")

        assertFalse(result, "updateGraphDescription() must return false for the demo graph")
        val demoEntry = graphManager.graphRegistry.value.graphs.first { it.id == DEMO_GRAPH_ID }
        assertEquals(
            "",
            demoEntry.description,
            "description must remain unchanged after a rejected updateGraphDescription",
        )
    }

    // updateGraphDescription/renameGraph/updateHostDirName used to mutate _graphRegistry via a
    // manual `val registry = _graphRegistry.value; ...; _graphRegistry.value = ...` read-then-set,
    // which is not atomic against any concurrent writer of the same GraphManager instance —
    // matching the exact bug class commit 81b6db34 already fixed once for switchGraph/
    // updateGraphInfoDetection, just at different call sites this file never got around to.
    // This test proves the atomicity property directly with real concurrent threads. Note: it
    // does NOT reproduce the specific CI flake in `updateGraphDescription survives reload` —
    // that flake's actual mechanism was a separate, cross-instance bug in this test file's own
    // StubFileSystem stub (see directoryExists's comment above), not this same-instance race.
    // Both are real bugs; this test guards the same-instance one.
    @Test
    fun `concurrent updateGraphDescription and renameGraph calls never lose either write`() {
        repeat(50) { iteration ->
            val settings = InMemorySettings()
            val graphManager = makeGraphManager(settings)
            val id = kotlinx.coroutines.runBlocking {
                graphManager.addGraph("/tmp/race-$iteration", null, "Original Name", "Original description")
            }

            val startLatch = java.util.concurrent.CountDownLatch(1)
            val threads = (0 until 20).map { i ->
                Thread {
                    startLatch.await()
                    if (i % 2 == 0) {
                        graphManager.updateGraphDescription(id, "Updated description $i")
                    } else {
                        graphManager.renameGraph(id, "Updated Name $i")
                    }
                }.also { it.start() }
            }
            startLatch.countDown()
            threads.forEach { it.join(5_000) }
            assertTrue(
                threads.none { it.isAlive },
                "iteration $iteration: a worker thread did not finish within 5s — treat as an " +
                    "inconclusive run, not a lost-update failure",
            )

            val info = graphManager.graphRegistry.value.graphs.first { it.id == id }
            assertTrue(
                info.description.startsWith("Updated description"),
                "iteration $iteration: a concurrent renameGraph call clobbered the description " +
                    "update — got '${info.description}'",
            )
            assertTrue(
                info.displayName.startsWith("Updated Name"),
                "iteration $iteration: a concurrent updateGraphDescription call clobbered the " +
                    "rename — got '${info.displayName}'",
            )
        }
    }
}
