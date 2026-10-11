package dev.stapler.stelekit.db

import dev.stapler.stelekit.merge.MarkdownTargetWriter
import dev.stapler.stelekit.merge.MergeBlock
import dev.stapler.stelekit.merge.MergePage
import dev.stapler.stelekit.merge.OffGraphTarget
import dev.stapler.stelekit.merge.PageKey
import dev.stapler.stelekit.merge.TargetWriterCapabilities
import dev.stapler.stelekit.merge.WriteOutcome
import dev.stapler.stelekit.merge.forFilePaths
import dev.stapler.stelekit.model.GraphId
import kotlinx.datetime.LocalDate
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
 * Permanent regression for Spike 0.1.1 (ADR-001): pages written into a CLOSED graph through the
 * production [MarkdownTargetWriter] (not a raw file write) reconcile on the next real
 * `GraphManager.switchGraph` with no `ExternalFileChange` (DiskConflict source), one page and
 * no duplicate journal. Real temp directories, real [PlatformFileSystem], real SQLDelight DB.
 * Same harness as [OffGraphWriteReconcileSpikeTest].
 */
class OffGraphReconcileRegressionTest {

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
        dataDir = Files.createTempDirectory("offgraph_regr_data_").toFile()
        System.setProperty("stelekit.devDataDir", dataDir.absolutePath)
        rootA = Files.createTempDirectory("offgraph_regr_A_").toFile().also { File(it, "pages").mkdirs(); File(it, "journals").mkdirs() }
        rootB = Files.createTempDirectory("offgraph_regr_B_").toFile().also { File(it, "pages").mkdirs(); File(it, "journals").mkdirs() }
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

    /** Writes through the production off-graph writer, as a copy into a closed target does. */
    private fun writerFor(id: GraphId, root: File) = MarkdownTargetWriter.forFilePaths(
        fs, OffGraphTarget(id, root.absolutePath, isActive = false), TargetWriterCapabilities(platformSupportsOffGraphWrite = true),
    )

    private fun page(name: String, uuid: String, text: String = "hello") =
        MergePage(name, blocks = listOf(MergeBlock(uuid, text)))

    private fun uuidOf(n: Int) = "00000000-0000-0000-0000-%012d".format(n)

    @Test
    fun `page and journal written by MarkdownTargetWriter into a closed graph reconcile with no conflict`() = runBlocking {
        val (a, b) = registerBoth()
        open(b, rootB) // A has never been opened
        val writer = writerFor(a, rootA)

        val created = writer.write(PageKey("Spike"), page("Spike", spikeUuid)).getOrNull()
        assertTrue(created is WriteOutcome.Created, "page write: $created")
        val journal = MergePage(
            "2026-06-15", isJournal = true, journalDate = LocalDate(2026, 6, 15),
            blocks = listOf(MergeBlock(secondUuid, "journal entry")),
        )
        val journalWrite = writer.write(PageKey("2026-06-15", isJournal = true), journal).getOrNull()
        assertTrue(journalWrite is WriteOutcome.Created, "journal write: $journalWrite")

        val o = open(a, rootA)
        val found = blocksOf(o, "spike", 1)
        assertEquals(1, found.size, "expected 1 page named 'spike'; got ${found.map { it.first.name }}")
        assertEquals(spikeUuid, found.single().second.single().uuid.value)
        assertEquals(0, o.changes.size, "expected 0 ExternalFileChange events; got ${o.changes.map { it.filePath }}")
        val all = o.repos.pageRepository.getAllPagesSnapshot().getOrNull().orEmpty()
        assertEquals(1, all.count { it.name.equals("spike", ignoreCase = true) }, "duplicate page: ${all.map { it.name }}")
        assertEquals(1, all.count { it.isJournal }, "expected exactly one journal; got ${all.filter { it.isJournal }.map { it.name }}")
        assertTrue(journalDupes(all).isEmpty(), "duplicate journal pages: ${journalDupes(all).keys}")
    }

    @Test
    fun `block spliced by MarkdownTargetWriter into an indexed closed graph reconciles on reopen`() = runBlocking {
        val (a, b) = registerBoth()
        File(rootA, "pages/Spike.md").writeText("- hello\n  id:: $spikeUuid\n")
        val first = open(a, rootA)
        assertEquals(1, blocksOf(first, "spike", 1).single().second.size, "precondition: 1 block indexed")
        loaders.forEach { it.stopWatching() }
        open(b, rootB) // closes A

        delay(1_100) // distinct mtime even on 1s-granularity filesystems
        val writer = writerFor(a, rootA)
        val existing = writer.readExisting(PageKey("Spike")).getOrNull()!!
        val merged = existing.copy(blocks = existing.blocks + MergeBlock(secondUuid, "second"))
        val outcome = writer.write(PageKey("Spike"), merged).getOrNull()
        assertTrue(outcome is WriteOutcome.Updated, "splice write: $outcome")

        val o = open(a, rootA)
        val blocks = blocksOf(o, "spike", 2).single().second
        assertEquals(2, blocks.size, "expected 2 blocks after reopen; got ${blocks.map { it.uuid.value to it.content }}")
        assertTrue(blocks.any { it.uuid.value == secondUuid })
        assertEquals(0, o.changes.size, "expected 0 ExternalFileChange events; got ${o.changes.map { it.filePath }}")
    }

    @Test
    fun `a burst of off-graph writes costs one reconcile on the next open and none after`() = runBlocking {
        val (a, b) = registerBoth()
        open(b, rootB)
        val burst = 25
        val writer = writerFor(a, rootA)
        val paths = (1..burst).map { n ->
            val name = "Burst %02d".format(n)
            assertTrue(writer.write(PageKey(name), page(name, uuidOf(n), "burst $n")).isRight(), "write $name")
            File(rootA, "pages/$name.md").absolutePath
        }

        // Control: a plain externally-created file shows what ONE reconcile costs (metadata scan + index load).
        val control = File(rootA, "pages/Control.md").also { it.writeText("- control\n  id:: ${uuidOf(999)}\n") }

        fs.reads.clear()
        val o1 = open(a, rootA)
        val all = o1.repos.pageRepository.getAllPagesSnapshot().getOrNull().orEmpty()
        assertEquals(burst, all.count { it.name.startsWith("Burst", ignoreCase = true) }, "pages after reconcile: ${all.map { it.name }}")
        val controlReads = fs.readsOf(control.absolutePath)
        assertTrue(controlReads in 1..2, "control file read $controlReads times")
        val readCounts = paths.associateWith { fs.readsOf(it) }
        assertTrue(readCounts.values.all { it == controlReads }, "burst files must cost one reconcile each ($controlReads reads), got ${readCounts.values.toSet()}")
        assertEquals(0, o1.changes.size, "watcher flagged ${o1.changes.map { it.filePath }}")

        loaders.forEach { it.stopWatching() }
        open(b, rootB)
        fs.reads.clear()
        val o2 = open(a, rootA)
        assertEquals(0, (paths + control.absolutePath).sumOf { fs.readsOf(it) }, "second reopen re-parsed burst files")
        assertEquals(0, o2.changes.size, "watcher flagged ${o2.changes.map { it.filePath }} on second reopen")
    }
}
