package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.GraphId
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

/** Mirrors [GraphManagerUpdateGraphPathTest]'s fixture/style for [GraphManager.updateHostDirName]. */
class GraphManagerUpdateHostDirNameTest {
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
        override fun renameFile(from: String, to: String): Boolean = true
    }

    private fun newManager(settings: Settings = StubSettings()) = GraphManager(
        platformSettings = settings,
        driverFactory = DriverFactory(),
        fileSystem = StubFileSystem(),
        defaultBackend = GraphBackend.IN_MEMORY,
    )

    @Test
    fun `updateHostDirName persists the new name and is visible via graphRegistry`() = runTest {
        val graphManager = newManager()
        val id = graphManager.addGraph("/some/path")

        val result = graphManager.updateHostDirName(id, "my-folder")

        assertTrue(result)
        assertEquals("my-folder", graphManager.graphRegistry.value.graphs.first { it.id == id }.hostDirName)
    }

    @Test
    fun `updateHostDirName returns false for an unknown GraphId and does not touch the registry`() = runTest {
        val graphManager = newManager()
        val id = graphManager.addGraph("/some/path")
        val before = graphManager.graphRegistry.value

        val result = graphManager.updateHostDirName(GraphId("unknown"), "some-name")

        assertFalse(result)
        assertEquals(before, graphManager.graphRegistry.value)
        assertNull(graphManager.graphRegistry.value.graphs.first { it.id == id }.hostDirName)
    }

    @Test
    fun `updateHostDirName with null clears a previously-set value`() = runTest {
        val graphManager = newManager()
        val id = graphManager.addGraph("/some/path")
        assertTrue(graphManager.updateHostDirName(id, "my-folder"))
        assertEquals("my-folder", graphManager.graphRegistry.value.graphs.first { it.id == id }.hostDirName)

        val result = graphManager.updateHostDirName(id, null)

        assertTrue(result)
        assertNull(graphManager.graphRegistry.value.graphs.first { it.id == id }.hostDirName)
    }
}
