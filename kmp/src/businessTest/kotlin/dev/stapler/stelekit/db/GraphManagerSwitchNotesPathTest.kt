package dev.stapler.stelekit.db

import dev.stapler.stelekit.diagnostics.GraphDiagnosticsCollector
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * StelekitViewModel loads whatever `lastGraphPath` holds when GraphContent is rebuilt for a new
 * activeGraphId. switchGraph() must therefore point that setting at the target graph's
 * effectiveNotesPath (repo root + wikiSubdir), or a switch reloads the previous graph's folder.
 */
class GraphManagerSwitchNotesPathTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class StubSettings(initial: Map<String, String> = emptyMap()) : Settings {
        private val store = mutableMapOf<String, String>().also { it.putAll(initial) }
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private val cloneId = GraphId("aaaaaaaaaaaaaaaa")
    private val safId = GraphId("bbbbbbbbbbbbbbbb")
    private val safPath = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3ADocuments%2Fpersonal-wiki"

    private val managers = mutableListOf<GraphManager>()

    @AfterTest
    fun shutdownManagers() {
        managers.forEach { it.shutdown() }
        managers.clear()
    }

    private fun newManager(graphs: List<GraphInfo>, settings: StubSettings = StubSettings()): Pair<GraphManager, StubSettings> {
        settings.putString(
            "graph_registry",
            json.encodeToString(GraphRegistry(activeGraphId = graphs.first().id, graphs = graphs)),
        )
        val manager = GraphManager(
            platformSettings = settings,
            driverFactory = DriverFactory(),
            fileSystem = StubFileSystem(),
            defaultBackend = GraphBackend.IN_MEMORY,
        ).also { managers += it }
        return manager to settings
    }

    private fun clone() = GraphInfo(
        id = cloneId,
        path = "/data/graphs/clone",
        displayName = "cloned",
        addedAt = 0L,
        detectedRepoRoot = "/data/graphs/clone",
        detectedWikiSubdir = "logseq",
        effectivePath = "/data/graphs/clone/logseq",
    )

    private fun saf() = GraphInfo(id = safId, path = safPath, displayName = "personal-wiki", addedAt = 0L)

    @Test
    fun `switching graphs A to B to A points lastGraphPath at each graph's own notes path`() = runTest {
        val (manager, settings) = newManager(listOf(clone(), saf()))
        manager.awaitPendingMigration()
        assertEquals("/data/graphs/clone/logseq", settings.getString("lastGraphPath", ""))

        manager.switchGraph(safId)
        assertEquals(safPath, settings.getString("lastGraphPath", ""))

        manager.switchGraph(cloneId)
        assertEquals("/data/graphs/clone/logseq", settings.getString("lastGraphPath", ""))
    }

    @Test
    fun `startup corrects a lastGraphPath that belongs to a different graph`() = runTest {
        val stale = StubSettings(mapOf("lastGraphPath" to safPath))
        val (manager, settings) = newManager(listOf(clone(), saf()), stale)
        manager.awaitPendingMigration()

        assertEquals("/data/graphs/clone/logseq", settings.getString("lastGraphPath", ""))
    }

    @Test
    fun `re-selecting the already-active graph restores its notes path`() = runTest {
        val (manager, settings) = newManager(listOf(clone(), saf()))
        manager.awaitPendingMigration()
        settings.putString("lastGraphPath", "/data/graphs/clone") // raw root, as a picker flow writes it

        manager.switchGraph(cloneId)

        assertEquals("/data/graphs/clone/logseq", settings.getString("lastGraphPath", ""))
    }

    private class SafRow(
        val name: String,
        val path: String,
        val root: String?,
        val subdir: String?,
        val expectCleared: Boolean,
    )

    @Test
    fun `malformed SAF detection is cleared only for SAF graphs with garbage`() = runTest {
        val tree = "content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Apersonal-wiki%2Flogseq"
        val rows = listOf(
            SafRow("saf root + uri subdir", safPath, "saf:/", tree, expectCleared = true),
            SafRow("saf root + encoded content subdir", safPath, null, "content%3A%2F%2Fx", expectCleared = true),
            SafRow("content root prefix", "content://tree/x", "content://tree", null, expectCleared = true),
            SafRow("saf with sane detection is kept", safPath, null, "logseq", expectCleared = false),
            SafRow("non-saf path with odd values is kept", "/data/g", "saf:/", tree, expectCleared = false),
        )
        for (row in rows) {
            val id = GraphId("cccccccccccccccc")
            val g = GraphInfo(
                id = id, path = row.path, displayName = row.name, addedAt = 0L,
                detectedRepoRoot = row.root, detectedWikiSubdir = row.subdir, effectivePath = "stale",
            )
            val (manager, _) = newManager(listOf(g))
            val loaded = manager.getGraphInfo(id)!!
            if (row.expectCleared) {
                assertNull(loaded.detectedRepoRoot, row.name)
                assertNull(loaded.detectedWikiSubdir, row.name)
                assertNull(loaded.effectivePath, row.name)
            } else {
                assertEquals(row.root, loaded.detectedRepoRoot, row.name)
                assertEquals(row.subdir, loaded.detectedWikiSubdir, row.name)
                assertEquals("stale", loaded.effectivePath, row.name)
            }
        }
    }

    @Test
    fun `a wikiSubdir change on the active graph updates the mirror`() = runTest {
        val (manager, settings) = newManager(listOf(clone(), saf()))
        manager.awaitPendingMigration()

        manager.updateWikiSubdir(cloneId, "notes")

        assertEquals("/data/graphs/clone/notes", settings.getString("lastGraphPath", ""))
    }

    @Test
    fun `a change to a non-active graph leaves the mirror on the active graph`() = runTest {
        val (manager, settings) = newManager(listOf(clone(), saf()))
        manager.awaitPendingMigration()

        manager.updateWikiSubdir(safId, "notes")

        assertEquals("/data/graphs/clone/logseq", settings.getString("lastGraphPath", ""))
    }

    @Test
    fun `switching to the demo graph does not touch lastGraphPath`() = runTest {
        val (manager, settings) = newManager(listOf(clone(), saf()))
        manager.awaitPendingMigration()

        manager.switchGraph(manager.addDemoGraph())

        assertEquals("/data/graphs/clone/logseq", settings.getString("lastGraphPath", ""))
    }

    @Test
    fun `relocating the graph content re-points the notes path and the mirror`() = runTest {
        val (manager, settings) = newManager(listOf(clone(), saf()))
        manager.awaitPendingMigration()

        manager.updateGraphContentPath(cloneId, "/moved/clone")

        assertEquals("/moved/clone/logseq", manager.getGraphInfo(cloneId)!!.effectiveNotesPath.value)
        assertEquals("/moved/clone/logseq", settings.getString("lastGraphPath", ""))
    }

    @Test
    fun `relocating a graph nested inside a repo does not duplicate its subdir`() = runTest {
        val nested = GraphInfo(
            id = cloneId, path = "/repo/wiki", displayName = "nested", addedAt = 0L,
            detectedRepoRoot = "/repo", detectedWikiSubdir = "wiki", effectivePath = "/repo/wiki",
        )
        val (manager, _) = newManager(listOf(nested, saf()))
        manager.awaitPendingMigration()

        manager.updateGraphContentPath(cloneId, "/new/wiki")

        assertEquals("/new/wiki", manager.getGraphInfo(cloneId)!!.effectiveNotesPath.value)
        assertNull(manager.getGraphInfo(cloneId)!!.detectedRepoRoot)
    }

    @Test
    fun `updateGraphPath drops the stale effective path of the old location`() = runTest {
        val (manager, _) = newManager(listOf(clone(), saf()))
        manager.awaitPendingMigration()

        val result = manager.updateGraphPath(cloneId, "/new/clone")

        val newId = (result as UpdateGraphPathResult.Success).newId
        assertTrue(
            !manager.getGraphInfo(newId)!!.effectiveNotesPath.value.startsWith("/data/graphs/clone"),
            "effective path must not keep pointing at the old folder",
        )
    }

    @Test
    fun `diagnostics report the effective notes path and whether the mirror matches`() = runTest {
        val (manager, settings) = newManager(listOf(clone(), saf()))
        manager.awaitPendingMigration()
        manager.switchGraph(safId)
        manager.switchGraph(cloneId)
        val repos = manager.awaitPendingMigration()!!

        val matching = GraphDiagnosticsCollector(manager, StubFileSystem(), repos, settings).collect()
        assertTrue("effectiveNotesPath=/data/graphs/clone/logseq (wikiSubdir=logseq)" in matching, matching)
        assertTrue("lastGraphPath matches effectiveNotesPath=true" in matching, matching)

        settings.putString("lastGraphPath", safPath)
        val stale = GraphDiagnosticsCollector(manager, StubFileSystem(), repos, settings).collect()
        assertTrue("lastGraphPath matches effectiveNotesPath=false" in stale, stale)
    }
}
