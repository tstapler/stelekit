package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        )
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

    @Test
    fun `malformed SAF detection persisted by older builds is cleared on load`() = runTest {
        val broken = saf().copy(
            detectedRepoRoot = "saf:/",
            detectedWikiSubdir = "content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Apersonal-wiki%2Flogseq",
            effectivePath = "saf:/content%3A%2F%2Fbroken",
        )
        val (manager, _) = newManager(listOf(clone(), broken))

        val loaded = manager.getGraphInfo(safId)!!
        assertNull(loaded.detectedRepoRoot)
        assertNull(loaded.detectedWikiSubdir)
        assertNull(loaded.effectivePath)
        assertEquals(safPath, loaded.effectiveNotesPath.value)
    }
}
