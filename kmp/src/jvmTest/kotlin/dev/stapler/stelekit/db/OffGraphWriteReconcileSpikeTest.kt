package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.repository.RepositorySet
import dev.stapler.stelekit.repository.createGraphLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spike 0.1.1 (cross-graph-merge-share-target, ADR-001): a markdown file written straight to disk
 * into a NON-active graph must reconcile on the next `switchGraph` with no spurious external-change
 * (DiskConflict source) event and no re-parse on a later reopen.
 *
 * Real temp directories, real [PlatformFileSystem], real [GraphManager] + SQLDelight DB, and a
 * [GraphLoader] built exactly as production does (`RepositorySet.createGraphLoader`, then
 * `loadGraphProgressive` + `indexRemainingPages`, watcher started by the loader). No FakeFileSystem.
 */
class OffGraphWriteReconcileSpikeTest {

    private val spikeUuid = "11111111-1111-1111-1111-111111111111"
    private val secondUuid = "22222222-2222-2222-2222-222222222222"

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    /** Real filesystem that counts content reads per path (the "loader counter" for re-parse). */
    private class ReadCountingFileSystem(private val delegate: FileSystem) : FileSystem by delegate {
        val reads = ConcurrentHashMap<String, AtomicInteger>()
        override fun readFile(path: String): String? {
            reads.getOrPut(path) { AtomicInteger() }.incrementAndGet()
            return delegate.readFile(path)
        }
        fun readsOf(path: String) = reads[path]?.get() ?: 0
    }

    private class Opened(
        val repos: RepositorySet,
        val loader: GraphLoader,
        val changes: CopyOnWriteArrayList<ExternalFileChange>,
    )

    private var originalDevDataDir: String? = null
    private lateinit var dataDir: File
    private lateinit var rootA: File
    private lateinit var rootB: File
    private lateinit var fs: ReadCountingFileSystem
    private lateinit var gm: GraphManager
    private val loaders = mutableListOf<GraphLoader>()

    @BeforeTest
    fun setUp() {
        originalDevDataDir = System.getProperty("stelekit.devDataDir")
        dataDir = Files.createTempDirectory("offgraph_spike_data_").toFile()
        System.setProperty("stelekit.devDataDir", dataDir.absolutePath)
        rootA = Files.createTempDirectory("offgraph_spike_A_").toFile().also { File(it, "pages").mkdirs(); File(it, "journals").mkdirs() }
        rootB = Files.createTempDirectory("offgraph_spike_B_").toFile().also { File(it, "pages").mkdirs(); File(it, "journals").mkdirs() }
        // Production registers each graph root in the PlatformFileSystem security whitelist when the graph is picked/opened.
        fs = ReadCountingFileSystem(PlatformFileSystem().also { it.registerGraphRoot(rootA.absolutePath); it.registerGraphRoot(rootB.absolutePath) })
        gm = GraphManager(MapSettings(), DriverFactory(), fs, GraphBackend.SQLDELIGHT)
    }

    @AfterTest
    fun tearDown() {
        loaders.forEach { runCatching { it.stopWatching() } }
        runCatching { gm.shutdown() }
        originalDevDataDir?.let { System.setProperty("stelekit.devDataDir", it) } ?: System.clearProperty("stelekit.devDataDir")
        listOf(dataDir, rootA, rootB).forEach { it.deleteRecursively() }
    }

    /** switchGraph + the production load sequence; waits for load, indexing and several watcher ticks. */
    private suspend fun open(id: GraphId, root: File): Opened = withTimeout(60_000) {
        gm.switchGraph(id)
        val repos = checkNotNull(gm.awaitPendingMigration()) { "graph $id failed to initialise" }
        val loader = repos.createGraphLoader(fs, graphId = id.value, watcherPollIntervalMs = 100L)
        loaders += loader
        val changes = CopyOnWriteArrayList<ExternalFileChange>()
        val collector = CoroutineScope(Dispatchers.Default).launch { loader.externalFileChanges.collect { changes += it } }
        delay(50) // let the collector subscribe before any watcher tick can emit
        loader.loadGraphProgressive(root.absolutePath, 10, {}, {}, {})
        delay(1_000) // warm-start reconcile runs on a background job; also lets >= 8 watcher polls fire
        loader.indexRemainingPages {}
        delay(800)
        collector.cancel()
        Opened(repos, loader, changes)
    }

    /**
     * Page + blocks for [name]. Block reads lag the loader's writes by ~1-2s (observed), so poll up to
     * 10s until [expectedBlocks] are visible; the caller's assertion then reports the real count.
     */
    private suspend fun blocksOf(o: Opened, name: String, expectedBlocks: Int) =
        withTimeoutOrNull(10_000) {
            while (true) {
                val result = o.repos.pageRepository.getPagesByNames(listOf(name)).getOrNull().orEmpty().map { page ->
                    page to o.repos.blockRepository.getBlocksForPage(page.uuid).first().getOrNull().orEmpty()
                }
                if (result.isNotEmpty() && result.all { it.second.size == expectedBlocks }) return@withTimeoutOrNull result
                delay(250)
            }
            @Suppress("UNREACHABLE_CODE") emptyList()
        } ?: o.repos.pageRepository.getPagesByNames(listOf(name)).getOrNull().orEmpty().map { page ->
            page to o.repos.blockRepository.getBlocksForPage(page.uuid).first().getOrNull().orEmpty()
        }

    private suspend fun registerBoth(): Pair<GraphId, GraphId> {
        val a = gm.addGraph(rootA.absolutePath)
        val b = gm.addGraph(rootB.absolutePath)
        gm.awaitBackgroundDetection()
        return a to b
    }

    private fun journalDupes(pages: List<Page>) =
        pages.filter { it.isJournal }.groupBy { it.journalDate }.filterValues { it.size > 1 }

    @Test
    fun `new page file written into inactive graph reconciles after switchGraph with no external change event`() = runBlocking {
        val (a, b) = registerBoth()
        open(b, rootB) // B active; A has never been opened

        File(rootA, "pages/Spike.md").writeText("- hello\n  id:: $spikeUuid\n")

        val o = open(a, rootA)
        val found = blocksOf(o, "spike", 1)
        assertEquals(1, found.size, "expected 1 page named 'spike' after switchGraph(A); got ${found.map { it.first.name }}")
        val blocks = found.single().second
        assertEquals(1, blocks.size, "expected 1 block; got ${blocks.map { it.uuid.value to it.content }}")
        assertEquals(spikeUuid, blocks.single().uuid.value)
        assertEquals(0, o.changes.size, "expected 0 ExternalFileChange (DiskConflict source) events; got ${o.changes.map { it.filePath }}")
        val allPages = o.repos.pageRepository.getAllPagesSnapshot().getOrNull().orEmpty()
        assertTrue(journalDupes(allPages).isEmpty(), "duplicate journal pages: ${journalDupes(allPages).keys}")
    }

    @Test
    fun `block appended on disk to an already-indexed closed graph reconciles on reopen`() = runBlocking {
        val (a, b) = registerBoth()
        val spike = File(rootA, "pages/Spike.md")
        spike.writeText("- hello\n  id:: $spikeUuid\n")

        val first = open(a, rootA)
        assertEquals(1, blocksOf(first, "spike", 1).single().second.size, "precondition: 1 block indexed")
        loaders.forEach { it.stopWatching() }
        open(b, rootB) // closes A

        delay(1_100) // guarantee a distinct mtime even on 1s-granularity filesystems
        spike.appendText("- second\n  id:: $secondUuid\n")

        val o = open(a, rootA)
        val blocks = blocksOf(o, "spike", 2).single().second
        assertEquals(2, blocks.size, "expected 2 blocks after reopen; got ${blocks.map { it.uuid.value to it.content }}")
        assertTrue(blocks.any { it.uuid.value == secondUuid }, "appended block uuid missing: ${blocks.map { it.uuid.value }}")
        assertEquals(0, o.changes.size, "expected 0 ExternalFileChange events; got ${o.changes.map { it.filePath }}")
    }

    @Test
    fun `FileRegistry holds new mtime after reconcile and a second reopen does not re-parse`() = runBlocking {
        val (a, b) = registerBoth()
        open(b, rootB)
        val spike = File(rootA, "pages/Spike.md")
        spike.writeText("- hello\n  id:: $spikeUuid\n")

        val o1 = open(a, rootA)
        val path = spike.absolutePath
        assertEquals(1, blocksOf(o1, "spike", 1).single().second.size, "precondition: page indexed")
        // The registry's mtime map is private; zero watcher events over >= 8 polls (100ms) proves it
        // holds the on-disk mtime (an older recorded mtime would surface the file as changed).
        assertEquals(0, o1.changes.size, "FileRegistry mtime stale: watcher flagged ${o1.changes.map { it.filePath }}")

        loaders.forEach { it.stopWatching() }
        open(b, rootB)
        fs.reads.clear()
        val o2 = open(a, rootA)
        assertEquals(0, fs.readsOf(path), "second reopen re-read/re-parsed $path ${fs.readsOf(path)} time(s)")
        assertEquals(1, blocksOf(o2, "spike", 1).single().second.size)
        assertEquals(0, o2.changes.size, "expected 0 ExternalFileChange events on second reopen; got ${o2.changes.map { it.filePath }}")
    }

}
