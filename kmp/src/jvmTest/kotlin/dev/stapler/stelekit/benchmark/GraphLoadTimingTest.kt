package dev.stapler.stelekit.benchmark

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageName
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.RepositoryFactoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.measureTime

/**
 * End-to-end load timing and write-latency benchmarks.
 *
 * Load timing (tests 1–3) — measures parse/I/O/SQLite cost per load phase:
 *   1. Synthetic graph, in-memory repos   — isolates parser + file I/O
 *   2. Synthetic graph, SQLite backend    — adds write-actor + WAL cost
 *   3. Real graph, SQLite backend         — requires -DSTELEKIT_GRAPH_PATH
 *
 * Write-latency under load (tests 4–5) — simulates typing while the graph loads:
 *   4. Synthetic graph                    — always runs
 *   5. Real graph                         — requires -DSTELEKIT_GRAPH_PATH
 *
 * System properties:
 *   -DSTELEKIT_GRAPH_PATH=/path           real graph path (tests 3, 5)
 *   -DSTELEKIT_BENCH_CONFIG=TINY|SMALL|MEDIUM|LARGE|MESH  (default: SMALL)
 *   -DSTELEKIT_BENCH_PAGES=<n>
 *   -DSTELEKIT_BENCH_JOURNALS=<n>
 *   -DSTELEKIT_BENCH_DENSITY=<0.0-1.0>
 *
 * JFR profiling:
 *   ./gradlew :kmp:jvmTestProfile -PgraphPath=/your/graph
 */
class GraphLoadTimingTest {

    private data class LoadTimingResult(
        val phase1TtiMs: Long,
        val phase2Ms: Long,
        val phase3Ms: Long,
        val totalMs: Long,
        val pageCount: Int,
    )

    // ── shared infrastructure ──────────────────────────────────────────────

    private val fileSystem = PlatformFileSystem()

    private fun syntheticConfig(): SyntheticGraphGenerator.Config {
        val preset = when (System.getProperty("STELEKIT_BENCH_CONFIG", "SMALL").uppercase()) {
            "TINY"   -> SyntheticGraphGenerator.TINY
            "MEDIUM" -> SyntheticGraphGenerator.MEDIUM
            "LARGE"  -> SyntheticGraphGenerator.LARGE
            "MESH"   -> SyntheticGraphGenerator.MESH
            else     -> SyntheticGraphGenerator.SMALL
        }
        return preset.copy(
            pageCount    = System.getProperty("STELEKIT_BENCH_PAGES")?.toIntOrNull()     ?: preset.pageCount,
            journalCount = System.getProperty("STELEKIT_BENCH_JOURNALS")?.toIntOrNull()  ?: preset.journalCount,
            linkDensity  = System.getProperty("STELEKIT_BENCH_DENSITY")?.toFloatOrNull() ?: preset.linkDensity,
        )
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

    private fun syntheticPage() = Page(
        uuid      = PageUuid("jank-journal-page"),
        name      = "jank journal",
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
        isJournal = true,
    )

    private fun syntheticBlock(pageUuid: PageUuid, index: Int) = Block(
        uuid      = BlockUuid("jank-block-${index.toString().padStart(6, '0')}"),
        pageUuid  = pageUuid,
        content   = "Simulated journal entry $index — typing while graph loads",
        level     = 0,
        position  = index,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    // ── load timing helpers ────────────────────────────────────────────────

    private suspend fun loadAndTime(graphPath: String, loader: GraphLoader, label: String, pageCount: suspend () -> Int): LoadTimingResult {
        println("\n[$label] graph: $graphPath")
        println("─".repeat(64))

        val start = Clock.System.now()
        var phase1Ms = -1L
        var phase2Ms = -1L

        val loadMs = measureTime {
            loader.loadGraphProgressive(
                graphPath             = graphPath,
                immediateJournalCount = 10,
                onProgress            = {},
                onPhase1Complete      = { phase1Ms = (Clock.System.now() - start).inWholeMilliseconds },
                onFullyLoaded         = { phase2Ms = (Clock.System.now() - start).inWholeMilliseconds },
            )
        }.inWholeMilliseconds

        val phase3Ms = measureTime { loader.indexRemainingPages {} }.inWholeMilliseconds

        println("  Phase 1 — journals immediate (TTI):  ${phase1Ms}ms")
        println("  Phase 2 — background metadata load:  ${phase2Ms - phase1Ms}ms   (cumulative: ${phase2Ms}ms)")
        println("  Phase 3 — full-content index:        ${phase3Ms}ms")
        println("  ──────────────────────────────────────────────────────────")
        println("  Total (1+2+3):                       ${loadMs + phase3Ms}ms")
        val pages = pageCount()
        println("  Pages indexed:                        $pages")
        println("─".repeat(64))
        return LoadTimingResult(phase1Ms, phase2Ms - phase1Ms, phase3Ms, loadMs + phase3Ms, pages)
    }

    private fun writeQueryStatsJson(file: java.io.File, stats: List<dev.stapler.stelekit.performance.QueryStat>) {
        try {
            file.parentFile?.mkdirs()
            val sb = StringBuilder("[\n")
            stats.forEachIndexed { i, q ->
                sb.append("""  {"table":"${q.tableName}","op":"${q.operation}","calls":${q.calls},"p50":${q.estimatePercentile(0.5)},"p99":${q.estimatePercentile(0.99)},"maxMs":${q.maxMs},"totalMs":${q.totalMs}}""")
                if (i < stats.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("]")
            file.writeText(sb.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
    }

    private fun writeJson(file: java.io.File, data: Map<String, Any>) {
        try {
            file.parentFile?.mkdirs()
            val sb = StringBuilder()
            sb.append("{\n")
            val entries = data.entries.toList()
            entries.forEachIndexed { index, (key, value) ->
                sb.append("  \"$key\": ")
                when (value) {
                    is String  -> sb.append("\"$value\"")
                    is Boolean -> sb.append(value.toString())
                    is Long    -> sb.append(value.toString())
                    is Int     -> sb.append(value.toString())
                    is Double  -> sb.append("%.2f".format(value))
                    else       -> sb.append("\"$value\"")
                }
                if (index < entries.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("}")
            file.writeText(sb.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // non-fatal
        }
    }

    // ── write-latency helpers ──────────────────────────────────────────────

    /**
     * Measure baseline write latency with no concurrent load. Returns latencies in ms.
     */
    private suspend fun measureBaselineLatency(
        actor: dev.stapler.stelekit.db.DatabaseWriteActor,
        page: Page,
        startIndex: Int,
        sampleCount: Int = 20,
        intervalMs: Long = 200L,
    ): List<Long> {
        val latencies = mutableListOf<Long>()
        repeat(sampleCount) { i ->
            val lat = measureTime {
                actor.saveBlocks(listOf(syntheticBlock(page.uuid, startIndex + i)))
            }.inWholeMilliseconds
            latencies.add(lat)
            if (i < sampleCount - 1) kotlinx.coroutines.delay(intervalMs)
        }
        return latencies
    }

    private data class WriteLatencyResults(
        val baseline: List<Long>,
        val phase1: List<Long>,
        val phase2: List<Long>,
        val phase3: List<Long>,
    )

    /**
     * Run the full jank-simulation scenario against a real graph directory + SQLite.
     *
     * The write interval is 200ms — roughly the cadence of a user pressing keys
     * or autosave firing after a debounce. Each write goes through the DatabaseWriteActor,
     * which serializes it with the concurrent bulk-load writes.
     *
     * Phases:
     *   Baseline  — writes with an idle DB (no background load)
     *   Phase 1   — writes while loadJournalsImmediate() is running (heaviest contention)
     *   Phase 2   — writes while background journal + page metadata load is running
     *   Phase 3   — writes while indexRemainingPages() is running
     */
    private suspend fun runWriteLatencyBenchmark(
        graphPath: String,
        actor: dev.stapler.stelekit.db.DatabaseWriteActor,
        loader: GraphLoader,
        label: String,
    ): WriteLatencyResults {
        val page = syntheticPage()
        actor.savePage(page)

        // Baseline: 20 writes with an idle database
        println("  Measuring baseline (20 writes, no load)...")
        val baseline = measureBaselineLatency(actor, page, startIndex = 0)
        var writeIndex = baseline.size

        val currentPhase = AtomicInteger(1)

        val phase1Latencies = mutableListOf<Long>()
        val phase2Latencies = mutableListOf<Long>()
        val phase3Latencies = mutableListOf<Long>()

        val intervalMs = 200L

        // Load phases 1+2 concurrently with writes
        println("  Starting graph load with concurrent writes...")
        val loadJob = CoroutineScope(Dispatchers.Default).launch {
            loader.loadGraphProgressive(
                graphPath             = graphPath,
                immediateJournalCount = 10,
                onProgress            = {},
                onPhase1Complete      = { currentPhase.set(2) },
                onFullyLoaded         = { currentPhase.set(99) }, // sentinel: load done
            )
        }

        val writeJob = CoroutineScope(Dispatchers.Default).launch {
            while (currentPhase.get() < 99) {
                val phase = currentPhase.get()
                val lat = measureTime {
                    actor.saveBlocks(listOf(syntheticBlock(page.uuid, writeIndex++)))
                }.inWholeMilliseconds
                when (phase) {
                    1    -> phase1Latencies.add(lat)
                    2    -> phase2Latencies.add(lat)
                }
                kotlinx.coroutines.delay(intervalMs)
            }
        }

        loadJob.join()
        writeJob.cancelAndJoin()

        // Phase 3: indexRemainingPages with concurrent writes
        println("  Starting indexRemainingPages with concurrent writes...")
        currentPhase.set(3)

        val indexJob = CoroutineScope(Dispatchers.Default).launch {
            loader.indexRemainingPages {}
            currentPhase.set(99)
        }

        val writeJobPhase3 = CoroutineScope(Dispatchers.Default).launch {
            while (currentPhase.get() < 99) {
                val lat = measureTime {
                    actor.saveBlocks(listOf(syntheticBlock(page.uuid, writeIndex++)))
                }.inWholeMilliseconds
                phase3Latencies.add(lat)
                kotlinx.coroutines.delay(intervalMs)
            }
        }

        indexJob.join()
        writeJobPhase3.cancelAndJoin()

        return WriteLatencyResults(baseline, phase1Latencies, phase2Latencies, phase3Latencies)
    }

    private fun printWriteLatencyReport(label: String, results: WriteLatencyResults) {
        fun List<Long>.p(pct: Double): Long {
            if (isEmpty()) return -1L
            val sorted = sorted()
            return sorted[(size * pct).toInt().coerceIn(0, size - 1)]
        }
        fun List<Long>.row(tag: String) {
            if (isEmpty()) { println("    %-26s (no writes)".format(tag)); return }
            val jankCount = count { it > 32 }
            println(
                "    %-26s p50=%4dms  p95=%5dms  p99=%5dms  max=%6dms  jank=%d/%d (%.0f%%)".format(
                    tag, p(0.50), p(0.95), p(0.99), max()!!,
                    jankCount, size, jankCount * 100.0 / size,
                )
            )
        }

        val allDuring = results.phase1 + results.phase2 + results.phase3
        println("\n  [$label] Write-latency simulation")
        println("  ${"─".repeat(80)}")
        results.baseline.row("Baseline (no load):")
        println("  ${"─".repeat(80)}")
        results.phase1.row("Phase 1 — journals (TTI):")
        results.phase2.row("Phase 2 — bg metadata load:")
        results.phase3.row("Phase 3 — full-content index:")
        println("  ${"─".repeat(80)}")
        allDuring.row("All during load (combined):")
        println("  ${"─".repeat(80)}")

        // Jank factor: how much worse is p95 under load vs. baseline?
        val baselineP95 = results.baseline.p(0.95).coerceAtLeast(1)
        val loadP95     = allDuring.p(0.95)
        if (loadP95 >= 0) {
            println("  Jank factor (p95 load / p95 baseline): %.1fx".format(loadP95.toDouble() / baselineP95))
        }
        println()
    }

    // ── tests 1–3: load timing ─────────────────────────────────────────────

    @Test
    fun `synthetic graph load - in-memory repos`() = runBlocking {
        val cfg = syntheticConfig()
        val dir = tempDir("stelekit-bench-mem")
        try {
            val stats = SyntheticGraphGenerator(cfg).generate(dir)
            println("\n[synthetic/in-memory] ${stats.pageCount} pages, ${stats.journalCount} journals, ${stats.totalLinks} links")
            val pageRepo  = InMemoryPageRepository()
            val blockRepo = InMemoryBlockRepository()
            loadAndTime(dir.absolutePath, GraphLoader(fileSystem, pageRepo, blockRepo), "synthetic / in-memory") {
                pageRepo.getAllPagesSnapshot().getOrNull()?.size ?: 0
            }
            Unit
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `synthetic graph load - sqlite backend`() = runBlocking {
        val cfg    = syntheticConfig()
        val dir    = tempDir("stelekit-bench-sql")
        val scope  = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val stats = SyntheticGraphGenerator(cfg).generate(dir)
            println("\n[synthetic/SQLite] ${stats.pageCount} pages, ${stats.journalCount} journals, ${stats.totalLinks} links")
            val factory = RepositoryFactoryImpl(DriverFactory(), "jdbc:sqlite:${File(dir, "bench.db").absolutePath}")
            val repoSet = factory.createRepositorySet(GraphBackend.SQLDELIGHT, scope)
            val loader  = GraphLoader(fileSystem, repoSet.pageRepository, repoSet.blockRepository,
                                      externalWriteActor = repoSet.writeActor, histogramWriter = repoSet.histogramWriter)
            val result = loadAndTime(dir.absolutePath, loader, "synthetic / SQLite") {
                repoSet.pageRepository.getAllPagesSnapshot().getOrNull()?.size ?: 0
            }
            assertTrue(result.totalMs < 60_000L,
                "SQLite synthetic load took ${result.totalMs}ms — catastrophic regression detected (> 60s)")

            // Drain accumulated query stats (periodic flush interval exceeds benchmark duration)
            repoSet.queryStatsCollector?.drainNow()
            val queryStats = repoSet.queryStatsRepository?.getTopByTotalMs("unknown", 20) ?: emptyList()
            if (queryStats.isNotEmpty()) {
                println("\n[synthetic/SQLite] Top SQL queries by total_ms:")
                println("  %-42s %6s %8s %8s %8s %8s".format("table:operation", "calls", "p50ms", "p99ms", "maxMs", "totalMs"))
                queryStats.forEach { q ->
                    println("  %-42s %6d %8d %8d %8d %8d".format(
                        "${q.tableName}:${q.operation}",
                        q.calls,
                        q.estimatePercentile(0.50),
                        q.estimatePercentile(0.99),
                        q.maxMs,
                        q.totalMs,
                    ))
                }
            }

            val outputDir = System.getProperty("benchmark.output.dir")?.let { java.io.File(it) } ?: java.io.File("build/reports")
            writeJson(
                java.io.File(outputDir, "benchmark-load.json"),
                mapOf(
                    "graphConfig"  to System.getProperty("STELEKIT_BENCH_CONFIG", "SMALL").lowercase(),
                    // Use generator stats for accurate count — result.pageCount queries getAllPages()
                    // before the async write actor drains, so it returns 0.
                    "pageCount"    to (stats.pageCount + stats.journalCount),
                    "journalCount" to stats.journalCount,
                    "phase1TtiMs"  to result.phase1TtiMs,
                    "phase2Ms"     to result.phase2Ms,
                    "phase3Ms"     to result.phase3Ms,
                    "totalMs"      to result.totalMs,
                ),
            )
            writeQueryStatsJson(java.io.File(outputDir, "benchmark-query-stats.json"), queryStats)
            factory.close()
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `real graph load - sqlite backend`() = runBlocking {
        val graphPath = System.getProperty("STELEKIT_GRAPH_PATH")
        if (graphPath.isNullOrBlank()) {
            println("[real graph] SKIPPED — set -DSTELEKIT_GRAPH_PATH=/your/graph to run")
            return@runBlocking
        }
        val tempDir = BenchmarkGraphUtils.copyGraphToTempDir(graphPath, "stelekit-real-bench")
        val dbFile = Files.createTempFile("stelekit-real-bench", ".db").toFile().also { it.deleteOnExit() }
        val scope  = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val factory = RepositoryFactoryImpl(DriverFactory(), "jdbc:sqlite:${dbFile.absolutePath}")
            val repoSet = factory.createRepositorySet(GraphBackend.SQLDELIGHT, scope)
            val loader  = GraphLoader(fileSystem, repoSet.pageRepository, repoSet.blockRepository,
                                      externalWriteActor = repoSet.writeActor, histogramWriter = repoSet.histogramWriter)
            loadAndTime(tempDir.absolutePath, loader, "real graph / SQLite") {
                repoSet.pageRepository.getAllPagesSnapshot().getOrNull()?.size ?: 0
            }.also { }
            factory.close()
        } finally {
            scope.cancel()
            dbFile.delete()
            tempDir.deleteRecursively()
        }
    }

    // ── tests 4–5: write-latency under concurrent load ─────────────────────

    /**
     * Simulates a user typing journal notes while the app loads a synthetic graph.
     *
     * Issues one block write every 200ms through the DatabaseWriteActor — the same
     * channel used by the background graph loader. Reports p50/p95/p99/max latency
     * per load phase and a jank factor vs. baseline.
     *
     * A jank factor > 3× on p95 indicates the write actor is saturated and the user
     * would experience visible lag in the editor during load.
     */
    @Test
    fun `write latency under concurrent graph load - synthetic`() = runBlocking {
        val cfg   = syntheticConfig()
        val dir   = tempDir("stelekit-jank-syn")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val stats = SyntheticGraphGenerator(cfg).generate(dir)
            println("\n[jank/synthetic] ${stats.pageCount} pages, ${stats.journalCount} journals, ${stats.totalLinks} links")

            val factory = RepositoryFactoryImpl(DriverFactory(), "jdbc:sqlite:${File(dir, "jank.db").absolutePath}")
            val repoSet = factory.createRepositorySet(GraphBackend.SQLDELIGHT, scope)
            val actor   = repoSet.writeActor ?: run {
                println("[jank/synthetic] SKIPPED — no write actor (non-SQLDelight backend)")
                return@runBlocking
            }
            val loader  = GraphLoader(fileSystem, repoSet.pageRepository, repoSet.blockRepository,
                                      externalWriteActor = actor, histogramWriter = repoSet.histogramWriter)

            val results = runWriteLatencyBenchmark(dir.absolutePath, actor, loader, "synthetic")
            printWriteLatencyReport("synthetic", results)

            fun List<Long>.p(pct: Double): Long {
                if (isEmpty()) return -1L
                val sorted = sorted()
                return sorted[(size * pct).toInt().coerceIn(0, size - 1)]
            }
            val allDuring = results.phase1 + results.phase2 + results.phase3
            val baselineP50 = results.baseline.p(0.50)
            val baselineP95 = results.baseline.p(0.95)
            val loadP50     = allDuring.p(0.50)
            val loadP95     = allDuring.p(0.95)
            val jankFactor  = loadP95.toDouble() / baselineP95.coerceAtLeast(1)
            val outputDir = System.getProperty("benchmark.output.dir")?.let { java.io.File(it) } ?: java.io.File("build/reports")
            writeJson(
                java.io.File(outputDir, "benchmark-jank.json"),
                mapOf(
                    "jankBaselineP50Ms" to baselineP50,
                    "jankBaselineP95Ms" to baselineP95,
                    "jankLoadP50Ms"     to loadP50,
                    "jankLoadP95Ms"     to loadP95,
                    "jankFactor"        to (Math.round(jankFactor * 100.0) / 100.0),
                ),
            )
            factory.close()
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    /**
     * Regression guard: navigating to a large page (150 blocks) must complete in < 2 s
     * even when the database already contains thousands of blocks.
     *
     * This catches a class of regressions where selectBlocksByPageUuid degrades into a
     * full table scan on a populated database (observed in production: p99 = 203 seconds
     * on Android v0.33.0 without the idx_blocks_page_uuid_position composite index).
     *
     * The test also validates that blocks:select p99 stays below 200 ms.
     */
    @Test
    fun `large page navigation is fast with populated database`() = runBlocking {
        // Use a fixed config large enough to reproduce the production regression (> 5 000 blocks).
        // NOT syntheticConfig() — this test must always seed a realistic dataset regardless of
        // the STELEKIT_BENCH_CONFIG system property so the regression guard is never weakened.
        val cfg = SyntheticGraphGenerator.Config(pageCount = 500, journalCount = 50, linkDensity = 0.3f)
        // PlatformFileSystem.validatePath requires paths within user.home. Use a subdirectory of
        // the home dir rather than /tmp so GraphLoader's directoryExists check doesn't reject it.
        val dir = java.io.File(System.getProperty("user.home"), ".stelekit-test-large-page-${System.nanoTime()}")
            .also { it.mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            SyntheticGraphGenerator(cfg).generate(dir)
            val factory = RepositoryFactoryImpl(DriverFactory(), "jdbc:sqlite:${File(dir, "largepage.db").absolutePath}")
            val repoSet = factory.createRepositorySet(GraphBackend.SQLDELIGHT, scope)
            val loader  = GraphLoader(fileSystem, repoSet.pageRepository, repoSet.blockRepository,
                                      externalWriteActor = repoSet.writeActor, histogramWriter = repoSet.histogramWriter)
            loader.loadGraphProgressive(
                graphPath             = dir.absolutePath,
                immediateJournalCount = 10,
                onProgress            = {},
                onPhase1Complete      = {},
                onFullyLoaded         = {},
            )
            loader.indexRemainingPages {}

            // Write a dense page with 150 blocks to the pages/ directory
            val densePageContent = buildString {
                repeat(150) { i -> appendLine("- Dense block content number $i with [[some link]] and more text") }
            }
            val pagesDir = File(dir, "pages").also { it.mkdirs() }
            File(pagesDir, "Dense Page.md").writeText(densePageContent)

            // Measure navigation — this is the hot path that was broken in v0.33.0
            val loadMs = measureTime {
                loader.loadPageByName(PageName("Dense Page"))
            }.inWholeMilliseconds

            println("\n[large-page] Navigation to 150-block page: ${loadMs}ms")
            assertTrue(loadMs < 2_000,
                "Large-page navigation took ${loadMs}ms — regression detected " +
                "(index idx_blocks_page_uuid_position may be missing; expected < 2000ms)")

            // Flush and assert blocks:select p99. If the query stats repository is wired,
            // the stat MUST be present — silently passing when stats aren't flushed masks
            // the regression guard.
            repoSet.queryStatsCollector?.drainNow()
            if (repoSet.queryStatsRepository != null) {
                val blocksSelectStat = repoSet.queryStatsRepository
                    .getTopByTotalMs("unknown", 50)
                    .find { it.tableName == "blocks" && it.operation == "select" }
                assertNotNull(blocksSelectStat,
                    "blocks:select stat must be present when queryStatsRepository is wired")
                val p99 = blocksSelectStat.estimatePercentile(0.99)
                println("[large-page] blocks:select p99=${p99}ms (calls=${blocksSelectStat.calls})")
                assertTrue(p99 < 200,
                    "blocks:select p99=${p99}ms exceeds 200ms — likely missing composite index " +
                    "idx_blocks_page_uuid_position (production impact: p99 was 203 seconds on Android)")
            }
            factory.close()
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    /**
     * Same jank simulation against the user's real graph library.
     * Requires: -DSTELEKIT_GRAPH_PATH=/path/to/your/graph
     *
     * This is the most meaningful measurement because it uses real file counts,
     * link density, and block sizes from an actual Logseq/Stelekit library.
     */
    @Test
    fun `write latency under concurrent graph load - real graph`() = runBlocking {
        val graphPath = System.getProperty("STELEKIT_GRAPH_PATH")
        if (graphPath.isNullOrBlank()) {
            println("[jank/real] SKIPPED — set -DSTELEKIT_GRAPH_PATH=/your/graph to run")
            return@runBlocking
        }

        val tempDir = BenchmarkGraphUtils.copyGraphToTempDir(graphPath, "stelekit-jank-real")
        val dbFile = Files.createTempFile("stelekit-jank-real", ".db").toFile().also { it.deleteOnExit() }
        val scope  = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val factory = RepositoryFactoryImpl(DriverFactory(), "jdbc:sqlite:${dbFile.absolutePath}")
            val repoSet = factory.createRepositorySet(GraphBackend.SQLDELIGHT, scope)
            val actor   = repoSet.writeActor ?: run {
                println("[jank/real] SKIPPED — no write actor")
                return@runBlocking
            }
            val loader  = GraphLoader(fileSystem, repoSet.pageRepository, repoSet.blockRepository,
                                      externalWriteActor = actor, histogramWriter = repoSet.histogramWriter)

            val results = runWriteLatencyBenchmark(tempDir.absolutePath, actor, loader, "real graph")
            printWriteLatencyReport("real graph", results)
            factory.close()
        } finally {
            scope.cancel()
            dbFile.delete()
            tempDir.deleteRecursively()
        }
    }
}
