package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class GraphManagerUpdateGraphPathTest {
    private class StubSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    /** [StubSettings] variant that records every persisted registry snapshot, in order. */
    private class RecordingSettings : Settings {
        private val delegate = StubSettings()
        private val json = Json { ignoreUnknownKeys = true }
        val savedRegistries = mutableListOf<GraphRegistry>()
        override fun getBoolean(key: String, defaultValue: Boolean) = delegate.getBoolean(key, defaultValue)
        override fun putBoolean(key: String, value: Boolean) = delegate.putBoolean(key, value)
        override fun getString(key: String, defaultValue: String) = delegate.getString(key, defaultValue)
        override fun putString(key: String, value: String) {
            delegate.putString(key, value)
            if (key == "graph_registry") {
                savedRegistries.add(json.decodeFromString(GraphRegistry.serializer(), value))
            }
        }
        override fun containsKey(key: String) = delegate.containsKey(key)
    }

    private open class StubFileSystem : FileSystem {
        val renamedPaths = mutableListOf<Pair<String, String>>()
        var existingPaths = mutableSetOf<String>()
        var existingDirectories = mutableSetOf<String>()

        override fun getDefaultGraphPath() = "/tmp"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = true
        override fun listFiles(path: String) = emptyList<String>()
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = existingPaths.contains(path)
        override fun directoryExists(path: String) = existingDirectories.contains(path)
        override fun createDirectory(path: String) = true
        override fun deleteFile(path: String) = true
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
        override fun startExternalChangeDetection(scope: CoroutineScope, onChange: () -> Unit) {}
        override fun stopExternalChangeDetection() {}
        override fun renameFile(from: String, to: String): Boolean {
            renamedPaths.add(from to to)
            existingPaths.remove(from)
            existingPaths.add(to)
            return true
        }
    }

    /** [StubFileSystem] variant whose [renameFile] fails for any path in [failingPaths]. */
    private class FailingRenameFileSystem(private val failingPaths: Set<String>) : StubFileSystem() {
        override fun renameFile(from: String, to: String): Boolean {
            if (from in failingPaths) return false
            renamedPaths.add(from to to)
            existingPaths.remove(from)
            existingPaths.add(to)
            return true
        }
    }

    private fun newManager(fs: StubFileSystem, settings: Settings = StubSettings()) = GraphManager(
        platformSettings = settings,
        driverFactory = DriverFactory(),
        fileSystem = fs,
        defaultBackend = GraphBackend.IN_MEMORY,
    )

    @Test
    fun `updateGraphPath recomputes id and renames the db file`() = runTest {
        val fs = StubFileSystem()
        val graphManager = newManager(fs)
        val oldId = graphManager.addGraph("/old/path")
        val oldDbPath = DriverFactory().getDatabaseUrl(oldId.value).substringAfter("jdbc:sqlite:")
        fs.existingPaths.add(oldDbPath)
        fs.existingDirectories.add("/new/path")

        val result = graphManager.updateGraphPath(oldId, "/new/path")

        val success = assertIs<UpdateGraphPathResult.Success>(result)
        val newId = success.newId
        assertTrue(newId != oldId, "moving a graph must re-key its GraphId")

        val newDbPath = DriverFactory().getDatabaseUrl(newId.value).substringAfter("jdbc:sqlite:")
        assertTrue(fs.renamedPaths.any { it.first == oldDbPath && it.second == newDbPath })

        val registry = graphManager.graphRegistry.value
        assertFalse(registry.graphIds.contains(oldId))
        assertTrue(registry.graphIds.contains(newId))
        assertEquals("/new/path", registry.graphs.first { it.id == newId }.path)
    }

    @Test
    fun `updateGraphPath is a no-op when the db file does not exist`() = runTest {
        val fs = StubFileSystem()
        val graphManager = newManager(fs)
        val oldId = graphManager.addGraph("/old/path")
        fs.existingDirectories.add("/new/path")

        val result = graphManager.updateGraphPath(oldId, "/new/path")

        val success = assertIs<UpdateGraphPathResult.Success>(result)
        assertTrue(fs.renamedPaths.isEmpty(), "no db file existed, so nothing should be renamed")
        assertTrue(graphManager.graphRegistry.value.graphIds.contains(success.newId))
    }

    @Test
    fun `updateGraphPath fails when the target path does not exist`() = runTest {
        val fs = StubFileSystem()
        val graphManager = newManager(fs)
        val oldId = graphManager.addGraph("/old/path")

        val result = graphManager.updateGraphPath(oldId, "/missing/path")

        assertEquals(UpdateGraphPathResult.PathNotFound, result)
    }

    @Test
    fun `updateGraphPath returns GraphNotFound for an unknown id`() = runTest {
        val fs = StubFileSystem()
        val graphManager = newManager(fs)

        val result = graphManager.updateGraphPath(GraphId("unknown"), "/new/path")

        assertEquals(UpdateGraphPathResult.GraphNotFound, result)
    }

    @Test
    fun `updateGraphPath refuses to move the demo graph`() = runTest {
        val fs = StubFileSystem()
        fs.existingDirectories.add("/new/path")
        val graphManager = newManager(fs)
        val demoId = graphManager.addDemoGraph()

        val result = graphManager.updateGraphPath(demoId, "/new/path")

        assertEquals(UpdateGraphPathResult.DemoGraphImmutable, result)
    }

    @Test
    fun `updateGraphPath is a no-op when the path is unchanged`() = runTest {
        val fs = StubFileSystem()
        val graphManager = newManager(fs)
        val oldId = graphManager.addGraph("/same/path")

        val result = graphManager.updateGraphPath(oldId, "/same/path")

        assertEquals(UpdateGraphPathResult.PathUnchanged, result)
    }

    @Test
    fun `updateGraphPath refuses a path already tracked by another graph`() = runTest {
        val fs = StubFileSystem()
        fs.existingDirectories.add("/other/path")
        val graphManager = newManager(fs)
        graphManager.addGraph("/first/path")
        val secondId = graphManager.addGraph("/other/path")
        val firstAgainId = graphManager.addGraph("/first/path")

        // addGraph is idempotent by id, so re-derive the first graph's id directly.
        val firstId = graphManager.graphRegistry.value.graphs.first { it.path == "/first/path" }.id
        assertEquals(firstId, firstAgainId)

        val result = graphManager.updateGraphPath(firstId, "/other/path")

        assertEquals(UpdateGraphPathResult.AlreadyTracked, result)
        assertTrue(secondId != firstId)
    }

    @Test
    fun `updateGraphPath fails when the db file rename fails`() = runTest {
        val oldId = newManager(StubFileSystem()).graphIdFromPath("/old/path")
        val oldDbPath = DriverFactory().getDatabaseUrl(oldId.value).substringAfter("jdbc:sqlite:")

        val fs = FailingRenameFileSystem(failingPaths = setOf(oldDbPath))
        val graphManager = newManager(fs)
        graphManager.addGraph("/old/path")
        fs.existingPaths.add(oldDbPath)
        fs.existingDirectories.add("/new/path")

        val result = graphManager.updateGraphPath(oldId, "/new/path")

        assertEquals(UpdateGraphPathResult.DatabaseMoveFailed, result)
        // Registry must be untouched — the graph should still be tracked under its original id/path.
        val registry = graphManager.graphRegistry.value
        assertTrue(registry.graphIds.contains(oldId))
        assertEquals("/old/path", registry.graphs.first { it.id == oldId }.path)
    }

    @Test
    fun `updateGraphPath rolls back the db rename when a wal sidecar rename fails`() = runTest {
        val oldId = newManager(StubFileSystem()).graphIdFromPath("/old/path")
        val oldDbPath = DriverFactory().getDatabaseUrl(oldId.value).substringAfter("jdbc:sqlite:")

        val fs = FailingRenameFileSystem(failingPaths = setOf("$oldDbPath-wal"))
        val graphManager = newManager(fs)
        graphManager.addGraph("/old/path")
        fs.existingPaths.add(oldDbPath)
        fs.existingPaths.add("$oldDbPath-wal")
        fs.existingDirectories.add("/new/path")

        val result = graphManager.updateGraphPath(oldId, "/new/path")

        assertEquals(UpdateGraphPathResult.DatabaseMoveFailed, result)
        // The main DB file must be rolled back to its original path, not left orphaned
        // at the new path while the registry still points at the old one.
        assertTrue(fs.fileExists(oldDbPath), "main db file must be rolled back after a sidecar rename failure")
        val registry = graphManager.graphRegistry.value
        assertTrue(registry.graphIds.contains(oldId))
        assertEquals("/old/path", registry.graphs.first { it.id == oldId }.path)
    }

    @Test
    fun `updateGraphPath rolls back the wal rename when a shm sidecar rename fails`() = runTest {
        val oldId = newManager(StubFileSystem()).graphIdFromPath("/old/path")
        val oldDbPath = DriverFactory().getDatabaseUrl(oldId.value).substringAfter("jdbc:sqlite:")

        val fs = FailingRenameFileSystem(failingPaths = setOf("$oldDbPath-shm"))
        val graphManager = newManager(fs)
        graphManager.addGraph("/old/path")
        fs.existingPaths.add(oldDbPath)
        fs.existingPaths.add("$oldDbPath-wal")
        fs.existingPaths.add("$oldDbPath-shm")
        fs.existingDirectories.add("/new/path")

        val result = graphManager.updateGraphPath(oldId, "/new/path")

        assertEquals(UpdateGraphPathResult.DatabaseMoveFailed, result)
        // Both the main DB file and the already-renamed WAL sidecar must be rolled back —
        // otherwise the WAL data would be stranded at the new path while the DB stays old.
        assertTrue(fs.fileExists(oldDbPath), "main db file must be rolled back after a shm rename failure")
        assertTrue(fs.fileExists("$oldDbPath-wal"), "wal sidecar must be rolled back after a shm rename failure")
        val registry = graphManager.graphRegistry.value
        assertTrue(registry.graphIds.contains(oldId))
        assertEquals("/old/path", registry.graphs.first { it.id == oldId }.path)
    }

    @Test
    fun `updateGraphPath moves the active graph and updates the active graph pointer`() = runTest {
        val fs = StubFileSystem()
        val graphManager = newManager(fs)
        val oldId = graphManager.addGraph("/old/path")
        graphManager.switchGraph(oldId)
        assertEquals(oldId, graphManager.graphRegistry.value.activeGraphId)

        val oldDbPath = DriverFactory().getDatabaseUrl(oldId.value).substringAfter("jdbc:sqlite:")
        fs.existingPaths.add(oldDbPath)
        fs.existingDirectories.add("/new/path")

        val result = graphManager.updateGraphPath(oldId, "/new/path")

        val success = assertIs<UpdateGraphPathResult.Success>(result)
        val newId = success.newId
        assertEquals(
            newId,
            graphManager.graphRegistry.value.activeGraphId,
            "moving the active graph must repoint activeGraphId to the new id",
        )
    }

    @Test
    fun `updateGraphPath never persists a registry where activeGraphId points at a re-keyed-away id`() = runTest {
        val fs = StubFileSystem()
        val settings = RecordingSettings()
        val graphManager = newManager(fs, settings)
        val oldId = graphManager.addGraph("/old/path")
        graphManager.switchGraph(oldId)
        settings.savedRegistries.clear()

        val oldDbPath = DriverFactory().getDatabaseUrl(oldId.value).substringAfter("jdbc:sqlite:")
        fs.existingPaths.add(oldDbPath)
        fs.existingDirectories.add("/new/path")

        graphManager.updateGraphPath(oldId, "/new/path")

        // Every disk write observed during the move must be internally consistent: if a crash
        // happened right after any one of them, startup auto-restore must still find the active
        // graph. A snapshot with the graph re-keyed but activeGraphId still pointing at the old,
        // now-nonexistent id would break that.
        assertTrue(settings.savedRegistries.isNotEmpty(), "expected at least one registry save")
        for (snapshot in settings.savedRegistries) {
            val activeId = snapshot.activeGraphId ?: continue
            assertTrue(
                snapshot.graphIds.contains(activeId),
                "persisted registry has activeGraphId=$activeId but graphs=${snapshot.graphIds} — " +
                    "would break startup auto-restore if the process crashed right after this save",
            )
        }
    }
}
