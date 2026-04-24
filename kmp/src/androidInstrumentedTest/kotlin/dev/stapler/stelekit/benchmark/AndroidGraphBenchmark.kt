package dev.stapler.stelekit.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.repository.RepositoryFactoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.measureTime

@RunWith(AndroidJUnit4::class)
class AndroidGraphBenchmark {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var graphDir: File
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        DriverFactory.setContext(context)
        graphDir = File(context.cacheDir, "bench-graph-${System.nanoTime()}").also { it.mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        generateGraph(graphDir)
    }

    @After
    fun tearDown() {
        scope.cancel()
        graphDir.deleteRecursively()
    }

    @Test
    fun loadPhaseTimings() = runBlocking {
        val dbFile = File(context.cacheDir, "bench-load-${System.nanoTime()}.db")
        val factory = RepositoryFactoryImpl(DriverFactory(), "jdbc:sqlite:${dbFile.absolutePath}")
        val repoSet = factory.createRepositorySet(GraphBackend.SQLDELIGHT, scope)
        val loader = GraphLoader(
            fileSystem = directFileSystem(),
            pageRepository = repoSet.pageRepository,
            blockRepository = repoSet.blockRepository,
            externalWriteActor = repoSet.writeActor,
            histogramWriter = repoSet.histogramWriter,
        )

        val start = System.currentTimeMillis()
        var phase1Ms = -1L

        loader.loadGraphProgressive(
            graphPath = graphDir.absolutePath,
            immediateJournalCount = 5,
            onProgress = {},
            onPhase1Complete = { phase1Ms = System.currentTimeMillis() - start },
            onFullyLoaded = {},
        )

        val phase3Ms = measureTime { loader.indexRemainingPages {} }.inWholeMilliseconds

        val pageCount = repoSet.pageRepository.getAllPages().first().getOrNull()?.size ?: 0

        android.util.Log.i("AndroidBench", "phase1=${phase1Ms}ms phase3=${phase3Ms}ms pages=$pageCount")

        factory.close()
        dbFile.delete()

        assertTrue(phase1Ms < 10_000L, "Phase 1 TTI exceeded 10s: ${phase1Ms}ms")
    }

    @Test
    fun writeLatencyDuringPhase3() = runBlocking {
        val dbFile = File(context.cacheDir, "bench-jank-${System.nanoTime()}.db")
        val factory = RepositoryFactoryImpl(DriverFactory(), "jdbc:sqlite:${dbFile.absolutePath}")
        val repoSet = factory.createRepositorySet(GraphBackend.SQLDELIGHT, scope)
        val actor = repoSet.writeActor!!
        val loader = GraphLoader(
            fileSystem = directFileSystem(),
            pageRepository = repoSet.pageRepository,
            blockRepository = repoSet.blockRepository,
            externalWriteActor = actor,
            histogramWriter = repoSet.histogramWriter,
        )

        val page = Page(
            uuid = "bench-page",
            name = "Benchmark Page",
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
        actor.savePage(page)

        loader.loadGraphProgressive(
            graphPath = graphDir.absolutePath,
            immediateJournalCount = 5,
            onProgress = {},
            onPhase1Complete = {},
            onFullyLoaded = {},
        )

        var writeIndex = 0

        val baselineLatencies = mutableListOf<Long>()
        repeat(10) { i ->
            val lat = measureTime {
                actor.saveBlocks(listOf(block(page.uuid, writeIndex++)))
            }.inWholeMilliseconds
            baselineLatencies.add(lat)
            if (i < 9) kotlinx.coroutines.delay(200)
        }

        val phase3Latencies = mutableListOf<Long>()
        val indexDone = AtomicInteger(0)

        val indexJob = CoroutineScope(Dispatchers.Default).launch {
            loader.indexRemainingPages {}
            indexDone.set(1)
        }

        val writeJob = CoroutineScope(Dispatchers.Default).launch {
            while (indexDone.get() == 0) {
                val lat = measureTime {
                    actor.saveBlocks(listOf(block(page.uuid, writeIndex++)))
                }.inWholeMilliseconds
                phase3Latencies.add(lat)
                kotlinx.coroutines.delay(200)
            }
        }

        indexJob.join()
        writeJob.cancelAndJoin()

        val baselineP95 = percentile(baselineLatencies, 95)
        val phase3P95 = percentile(phase3Latencies, 95)
        val jankFactor = if (baselineP95 > 0) phase3P95.toDouble() / baselineP95 else 0.0

        android.util.Log.i(
            "AndroidBench",
            "baseline_p95=${baselineP95}ms phase3_p95=${phase3P95}ms jank_factor=%.1fx".format(jankFactor),
        )

        factory.close()
        dbFile.delete()

        // Before the actor-contention fix, phase 3 ran as a single LOW-priority Execute lambda,
        // blocking HIGH-priority user saves for the full indexing duration (~5s on device).
        // After the fix, individual page writes are interleaved so p95 stays well under 5s.
        assertTrue(phase3P95 < 5_000L, "Phase 3 write p95 exceeded 5s — possible actor contention regression: ${phase3P95}ms")
    }

    private fun block(pageUuid: String, index: Int) = Block(
        uuid = "bench-block-$index",
        pageUuid = pageUuid,
        content = "Bench $index",
        level = 0,
        position = index,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    private fun percentile(values: List<Long>, pct: Int): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        return sorted[(sorted.size * pct / 100).coerceIn(0, sorted.size - 1)]
    }

    private fun generateGraph(dir: File) {
        File(dir, "pages").mkdirs()
        File(dir, "journals").mkdirs()
        repeat(20) { i ->
            File(dir, "pages/page-$i.md").writeText("- Note $i\n  - Child $i\n")
        }
        repeat(5) { i ->
            val m = (i % 12) + 1
            val d = (i % 28) + 1
            File(dir, "journals/2026_${m.toString().padStart(2, '0')}_${d.toString().padStart(2, '0')}.md")
                .writeText("- Journal $i\n")
        }
    }

    private fun directFileSystem(): FileSystem = object : FileSystem {
        override fun getDefaultGraphPath(): String = context.filesDir.absolutePath
        override fun expandTilde(path: String): String = path
        override fun readFile(path: String): String? = runCatching { File(path).readText() }.getOrNull()
        override fun writeFile(path: String, content: String): Boolean =
            runCatching { File(path).also { it.parentFile?.mkdirs() }.writeText(content); true }.getOrDefault(false)
        override fun listFiles(path: String): List<String> =
            File(path).listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList()
        override fun listDirectories(path: String): List<String> =
            File(path).listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
        override fun fileExists(path: String): Boolean = File(path).isFile
        override fun directoryExists(path: String): Boolean = File(path).isDirectory
        override fun createDirectory(path: String): Boolean = File(path).mkdirs()
        override fun deleteFile(path: String): Boolean = File(path).delete()
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = File(path).takeIf { it.exists() }?.lastModified()
    }
}
