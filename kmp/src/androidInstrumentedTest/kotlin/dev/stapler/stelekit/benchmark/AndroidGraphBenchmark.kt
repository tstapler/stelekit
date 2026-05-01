package dev.stapler.stelekit.benchmark

import androidx.core.content.FileProvider
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

    // Matches the authority declared in AndroidManifest.xml for benchmark FileProvider
    private val safAuthority = "${context.packageName}.benchprovider"

    @Before
    fun setUp() {
        DriverFactory.setContext(context)
        graphDir = File(context.cacheDir, "bench-graph-${System.nanoTime()}").also { it.mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        generateMediumGraph(graphDir)
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

        android.util.Log.i("ANDROID_BENCH", """{"metric":"loadPhase","phase1Ms":$phase1Ms,"phase3Ms":$phase3Ms,"pageCount":$pageCount}""")

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

        android.util.Log.i("ANDROID_BENCH", """{"metric":"writeLatency","baselineP95Ms":$baselineP95,"phase3P95Ms":$phase3P95,"jankFactor":${"%.2f".format(jankFactor)},"writes":${phase3Latencies.size}}""")

        factory.close()
        dbFile.delete()

        // Before the actor-contention fix, phase 3 ran as a single LOW-priority Execute lambda,
        // blocking HIGH-priority user saves for the full indexing duration (~5s on device).
        // After the fix, individual page writes are interleaved so p95 stays well under 5s.
        assertTrue(phase3P95 < 5_000L, "Phase 3 write p95 exceeded 5s — possible actor contention regression: ${phase3P95}ms")
    }

    /**
     * Measures ContentResolver (FileProvider) read overhead vs direct File.readText().
     *
     * This simulates the per-file Binder IPC cost that SAF imposes for every readFile() call
     * during Phase 3 background indexing. The overhead ratio (safMs / directMs) is the
     * multiplier applied to all Phase 3 I/O on graphs stored via ACTION_OPEN_DOCUMENT_TREE.
     *
     * On an emulator with tmpfs-backed cacheDir this understates real-device SAF overhead
     * (real ExternalStorageProvider adds additional NAND + Binder latency), but the relative
     * ratio is meaningful and catches regressions introduced by code changes to the read path.
     */
    @Test
    fun safIoOverhead() = runBlocking {
        val sampleFiles = (File(graphDir, "pages").listFiles() ?: emptyArray())
            .filter { it.isFile }
            .sortedBy { it.name }
            .take(30)

        if (sampleFiles.isEmpty()) {
            android.util.Log.w("ANDROID_BENCH", "safIoOverhead: no sample files found — skipping")
            return@runBlocking
        }

        // Warm up filesystem cache and JIT
        sampleFiles.take(5).forEach { it.readText() }
        sampleFiles.take(5).forEach { file ->
            val uri = FileProvider.getUriForFile(context, safAuthority, file)
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }

        val directMs = measureTime {
            sampleFiles.forEach { it.readText() }
        }.inWholeMilliseconds

        val safMs = measureTime {
            sampleFiles.forEach { file ->
                val uri = FileProvider.getUriForFile(context, safAuthority, file)
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
        }.inWholeMilliseconds

        val n = sampleFiles.size
        val safPerFileMs = safMs.toDouble() / n
        val directPerFileMs = directMs.toDouble() / n
        val overhead = if (directMs > 0) safMs.toDouble() / directMs else safMs.toDouble()

        android.util.Log.i(
            "ANDROID_BENCH",
            """{"metric":"safIo","directMs":$directMs,"safMs":$safMs,"safPerFileMs":${"%.2f".format(safPerFileMs)},"directPerFileMs":${"%.2f".format(directPerFileMs)},"overhead":${"%.2f".format(overhead)},"files":$n}"""
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────────

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

    /**
     * Generates a MEDIUM-scale synthetic graph (500 pages, 90 journals) with realistic
     * block counts and wiki-link density. Deterministic (seed=42).
     *
     * Previously the benchmark used 25 pages, which masked the real-world severity of SAF
     * and actor-contention issues on graphs with 1000+ pages.
     */
    private fun generateMediumGraph(dir: File) {
        val pagesDir = File(dir, "pages").also { it.mkdirs() }
        val journalsDir = File(dir, "journals").also { it.mkdirs() }
        val rng = java.util.Random(42L)

        val topics = listOf(
            "Philosophy", "Mathematics", "Programming", "Literature", "History",
            "Economics", "Psychology", "Biology", "Physics", "Chemistry",
            "Architecture", "Design", "Music", "Art", "Cinema",
            "Health", "Nutrition", "Exercise", "Meditation", "Sleep",
            "Finance", "Investing", "Productivity", "Leadership", "Communication",
            "Learning", "Memory", "Focus", "Habits", "Goals",
            "Systems Thinking", "Mental Models", "First Principles", "Decision Making",
            "Note Taking", "Knowledge Management", "Personal Finance", "Time Management",
            "Software Design", "Data Structures", "Algorithms", "Distributed Systems",
            "Machine Learning", "Statistics", "Linear Algebra", "Probability",
            "Stoicism", "Epistemology", "Ethics", "Logic", "Metaphysics",
            "Ecology", "Climate", "Energy", "Sustainability", "Urban Planning",
        )

        val pageNames = (0 until 500).map { i ->
            if (i < topics.size) topics[i] else "${topics[i % topics.size]} ${i / topics.size + 2}"
        }

        for ((idx, name) in pageNames.withIndex()) {
            val blockCount = 3 + rng.nextInt(18) // 3–20 blocks
            val sb = StringBuilder()
            repeat(blockCount) { b ->
                sb.append("- Note $b about $name")
                if (rng.nextFloat() < 0.22f) {
                    val linkTarget = pageNames[(idx + b + 7) % pageNames.size]
                    sb.append(" [[${linkTarget}]]")
                }
                sb.append("\n")
                if (b % 4 == 0 && rng.nextFloat() < 0.35f) {
                    sb.append("  - Child detail for $name block $b\n")
                }
            }
            val fileName = name.replace(' ', '_').replace('/', '%') + ".md"
            File(pagesDir, fileName).writeText(sb.toString())
        }

        for (i in 0 until 90) {
            val year = 2026
            val month = (i / 30 % 12) + 1
            val day = (i % 28) + 1
            val blockCount = 2 + rng.nextInt(5) // 2–6 blocks
            val sb = StringBuilder()
            repeat(blockCount) { b ->
                sb.append("- Journal entry $b for day $i\n")
            }
            val fileName = "${year}_${month.toString().padStart(2, '0')}_${day.toString().padStart(2, '0')}.md"
            File(journalsDir, fileName).writeText(sb.toString())
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

    /**
     * FileSystem implementation that routes readFile() through ContentResolver + FileProvider,
     * adding real Binder IPC overhead on each read. All other operations use direct File access.
     *
     * This approximates the per-call cost of SAF's openInputStream() without requiring
     * ACTION_OPEN_DOCUMENT_TREE permissions in CI. The authority must match the <provider>
     * entry in AndroidManifest.xml (${applicationId}.benchprovider).
     *
     * Note: real SAF via ExternalStorageProvider adds extra overhead beyond what FileProvider
     * measures here (additional document metadata queries, NAND latency). Use this as a lower
     * bound on actual SAF cost, not an exact replica.
     */
    private fun contentProviderFileSystem(): FileSystem = object : FileSystem {
        override fun getDefaultGraphPath(): String = graphDir.absolutePath
        override fun expandTilde(path: String): String = path
        override fun readFile(path: String): String? = try {
            val uri = FileProvider.getUriForFile(context, safAuthority, File(path))
            context.contentResolver.openInputStream(uri)?.use { it.bufferedReader(Charsets.UTF_8).readText() }
        } catch (e: Exception) { null }
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
