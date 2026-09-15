package dev.stapler.stelekit.db

import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest

/**
 * Regression coverage for removing the last real graph — previously `removeGraph` unconditionally
 * refused to remove the active graph, and the UI (Sidebar.kt) hid the remove affordance whenever
 * only one real graph existed, so a user had no way to delete their only graph. Fixed by carving
 * out "this is the last non-demo graph" as an allowed case that tears down the active repository
 * set and leaves `activeGraphId = null` instead of silently refusing.
 */
class GraphManagerRemoveGraphTest {

    private class StubSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private class StubFileSystem : FileSystem {
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

    private fun newManager() = GraphManager(
        platformSettings = StubSettings(),
        driverFactory = DriverFactory(),
        fileSystem = StubFileSystem(),
        defaultBackend = GraphBackend.IN_MEMORY,
    )

    @Test
    fun `removeGraph allows removing the only real graph and clears activeGraphId`() = runTest {
        val graphManager = newManager()
        val graphId = graphManager.addGraph("/test/graph-a")
        graphManager.switchGraph(graphId)
        graphManager.awaitPendingMigration()
        assertEquals(graphId, graphManager.graphRegistry.value.activeGraphId)
        assertFalse(graphManager.graphsExplicitlyEmptied.value)

        val removed = graphManager.removeGraph(graphId)

        assertTrue(removed, "removing the only real graph must now succeed")
        assertNull(graphManager.graphRegistry.value.activeGraphId)
        assertNull(graphManager.activeRepositorySet.value, "the removed graph's repository set must be torn down")
        assertTrue(graphManager.graphsExplicitlyEmptied.value, "removing the last graph must set the empty-state signal")
        assertTrue(graphManager.graphRegistry.value.graphs.none { it.id == graphId })
    }

    @Test
    fun `removeGraph still refuses the active graph when another real graph exists`() = runTest {
        val graphManager = newManager()
        val graphIdA = graphManager.addGraph("/test/graph-a")
        val graphIdB = graphManager.addGraph("/test/graph-b")
        graphManager.switchGraph(graphIdA)
        graphManager.awaitPendingMigration()

        val removed = graphManager.removeGraph(graphIdA)

        assertFalse(removed, "removing the active graph while another real graph exists must still be refused")
        assertEquals(graphIdA, graphManager.graphRegistry.value.activeGraphId)
        assertTrue(graphManager.graphRegistry.value.graphs.any { it.id == graphIdA })
        assertTrue(graphManager.graphRegistry.value.graphs.any { it.id == graphIdB })
        assertFalse(graphManager.graphsExplicitlyEmptied.value)
    }

    @Test
    fun `removeGraph removes a non-active graph normally when multiple real graphs exist`() = runTest {
        val graphManager = newManager()
        val graphIdA = graphManager.addGraph("/test/graph-a")
        val graphIdB = graphManager.addGraph("/test/graph-b")
        graphManager.switchGraph(graphIdA)
        graphManager.awaitPendingMigration()

        val removed = graphManager.removeGraph(graphIdB)

        assertTrue(removed)
        assertEquals(graphIdA, graphManager.graphRegistry.value.activeGraphId)
        assertTrue(graphManager.graphRegistry.value.graphs.none { it.id == graphIdB })
        assertFalse(graphManager.graphsExplicitlyEmptied.value)
    }

    @Test
    fun `switchGraph clears the explicitly-emptied signal once a new graph becomes active`() = runTest {
        val graphManager = newManager()
        val graphIdA = graphManager.addGraph("/test/graph-a")
        graphManager.switchGraph(graphIdA)
        graphManager.awaitPendingMigration()
        graphManager.removeGraph(graphIdA)
        assertTrue(graphManager.graphsExplicitlyEmptied.value)

        val graphIdB = graphManager.addGraph("/test/graph-b")
        graphManager.switchGraph(graphIdB)
        graphManager.awaitPendingMigration()

        assertFalse(graphManager.graphsExplicitlyEmptied.value)
        assertEquals(graphIdB, graphManager.graphRegistry.value.activeGraphId)
    }

    @Test
    fun `removeGraph never removes the demo graph even when it is the only entry`() = runTest {
        val graphManager = newManager()
        val demoId = graphManager.addDemoGraph()

        val removed = graphManager.removeGraph(demoId)

        assertFalse(removed)
        assertTrue(graphManager.graphRegistry.value.graphs.any { it.id == demoId })
    }
}
