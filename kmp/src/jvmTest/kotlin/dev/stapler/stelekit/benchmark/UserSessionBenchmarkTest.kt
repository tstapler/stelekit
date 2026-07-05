package dev.stapler.stelekit.benchmark

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.repository.RepositoryFactoryImpl
import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.StelekitViewModel
import dev.stapler.stelekit.ui.StelekitViewModelDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * End-to-end user session benchmark with interleaved reads and writes.
 *
 * Unlike a phased benchmark (all navigations, then all writes), this test runs
 * "interaction cycles" that mix navigation, typing, search, and structural block
 * operations in the same pattern a real user would. This surfaces write serialisation
 * bottlenecks that only appear when the DatabaseWriteActor is processing inserts
 * concurrently with read queries.
 *
 * Action categories:
 *   navigate_page  — navigateToPageByUuid (DB read + uiState await)
 *   type_block     — addBlockToPage (write-actor insert)
 *   search         — searchPages FTS5 query
 *   rename_page    — BacklinkRenamer + disk write
 *   delete_block   — handleBackspace (DB delete)
 *   merge_block    — mergeBlock (merge + delete)
 *   reorder_block  — moveBlock (arbitrary position change)
 *   indent_block   — indentBlock
 *   outdent_block  — outdentBlock
 *   move_block     — moveBlockUp / moveBlockDown
 *   undo           — undo
 *   navigate_back  — goBack
 *
 * Requires: -DSTELEKIT_GRAPH_PATH=/path/to/your/logseq/graph (set via -PgraphPath in jvmTestProfile)
 *
 * Activate Android SAF latency shim: -PsafLatency=true (adds 50ms/write, 10ms/exists, 30ms/read)
 *
 * Outputs in benchmark.output.dir (default: kmp/build/reports/):
 *   benchmark-session.json              — p50/p95/p99/max per action category
 *   benchmark-session-spans.json        — OTel spans captured during the session
 *   benchmark-session-query-stats.json  — per-table SQL latency stats
 */
class UserSessionBenchmarkTest {

    // ── latency sample accumulator ────────────────────────────────────────────

    private val navigateSamples   = mutableListOf<Long>()
    private val typeSamples       = mutableListOf<Long>()
    private val searchSamples     = mutableListOf<Long>()
    private val renameSamples     = mutableListOf<Long>()
    private val deleteSamples     = mutableListOf<Long>()
    private val mergeSamples      = mutableListOf<Long>()
    private val reorderSamples    = mutableListOf<Long>()
    private val indentSamples     = mutableListOf<Long>()
    private val outdentSamples    = mutableListOf<Long>()
    private val moveSamples       = mutableListOf<Long>()
    private val undoSamples       = mutableListOf<Long>()
    private val backSamples       = mutableListOf<Long>()

    // ── helpers ───────────────────────────────────────────────────────────────

    private suspend fun navigateTo(vm: StelekitViewModel, page: Page): Long {
        val startMs = System.currentTimeMillis()
        vm.navigateToPageByUuid(page.uuid.value)
        withTimeoutOrNull(5_000) {
            vm.uiState.first { s ->
                s.currentScreen is Screen.PageView &&
                (s.currentScreen as Screen.PageView).page.uuid == page.uuid
            }
        }
        return (System.currentTimeMillis() - startMs).also { navigateSamples.add(it) }
    }

    private suspend fun typeBlock(vm: StelekitViewModel, pageUuid: String): Long {
        val elapsed = measureTime {
            vm.addBlockToPage(pageUuid)
            delay(20) // let the launched coroutine start; pacing delay is outside measurement
        }
        return elapsed.inWholeMilliseconds.also { typeSamples.add(it); delay(180) }
    }

    private suspend fun search(vm: StelekitViewModel, query: String): Long {
        val elapsed = measureTime { vm.searchPages(query).first() }
        return elapsed.inWholeMilliseconds.also { searchSamples.add(it) }
    }

    private suspend fun rename(vm: StelekitViewModel, page: Page, newName: String): Long {
        val startMs = System.currentTimeMillis()
        vm.renamePage(page, newName)
        withTimeoutOrNull(1_000) { vm.uiState.first { it.renameDialogBusy } }
        withTimeoutOrNull(8_000) { vm.uiState.first { !it.renameDialogBusy } }
        return (System.currentTimeMillis() - startMs).also { renameSamples.add(it); delay(100) }
    }

    private suspend fun deleteBlock(vm: StelekitViewModel, block: Block): Long {
        val elapsed = measureTime { vm.handleBackspace(block.uuid.value); delay(50) }
        return elapsed.inWholeMilliseconds.also { deleteSamples.add(it) }
    }

    private suspend fun mergeBlock(vm: StelekitViewModel, block: Block): Long {
        val elapsed = measureTime { vm.mergeBlock(block.uuid.value); delay(50) }
        return elapsed.inWholeMilliseconds.also { mergeSamples.add(it) }
    }

    private suspend fun reorderBlock(vm: StelekitViewModel, block: Block, newPos: String): Long {
        val elapsed = measureTime {
            vm.moveBlock(block.uuid.value, block.parentUuid?.value, newPos)
            delay(50)
        }
        return elapsed.inWholeMilliseconds.also { reorderSamples.add(it) }
    }

    private suspend fun indentBlock(vm: StelekitViewModel, block: Block): Long {
        val elapsed = measureTime { vm.indentBlock(block.uuid.value); delay(50) }
        return elapsed.inWholeMilliseconds.also { indentSamples.add(it) }
    }

    private suspend fun outdentBlock(vm: StelekitViewModel, block: Block): Long {
        val elapsed = measureTime { vm.outdentBlock(block.uuid.value); delay(50) }
        return elapsed.inWholeMilliseconds.also { outdentSamples.add(it) }
    }

    private suspend fun moveUp(vm: StelekitViewModel, block: Block): Long {
        val elapsed = measureTime { vm.moveBlockUp(block.uuid.value); delay(50) }
        return elapsed.inWholeMilliseconds.also { moveSamples.add(it) }
    }

    private suspend fun moveDown(vm: StelekitViewModel, block: Block): Long {
        val elapsed = measureTime { vm.moveBlockDown(block.uuid.value); delay(50) }
        return elapsed.inWholeMilliseconds.also { moveSamples.add(it) }
    }

    // ── main test ─────────────────────────────────────────────────────────────

    @Test
    fun `user session - interleaved read-write cycles`() = runBlocking {
        val graphPath = System.getProperty("STELEKIT_GRAPH_PATH")
        if (graphPath.isNullOrBlank()) {
            println("[user-session] SKIPPED — set -DSTELEKIT_GRAPH_PATH=/your/graph to run")
            return@runBlocking
        }

        val useSaf = System.getProperty("stelekit.benchmark.saf")?.toBoolean() ?: false
        if (useSaf) println("[user-session] SAF latency shim enabled (write=50ms, exists=10ms, read=30ms)")

        var viewModel: StelekitViewModel? = null
        var tempDir: File? = null
        var factory: RepositoryFactoryImpl? = null
        var dbFile: File? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            tempDir = BenchmarkGraphUtils.copyGraphToTempDir(graphPath, "stelekit-session-bench")

            val cacheBase = File(System.getProperty("user.home"), ".cache/stelekit-benchmarks")
            cacheBase.mkdirs()
            dbFile = File(cacheBase, "session-bench-${System.currentTimeMillis()}.db")

            factory = RepositoryFactoryImpl(DriverFactory(), "jdbc:sqlite:${dbFile.absolutePath}")
            val repoSet = factory.createRepositorySet(GraphBackend.SQLDELIGHT, scope)
            // Warm up: ensure schema creation completes on the calling thread before
            // the ViewModel's Dispatchers.Default workers first access the database.
            repoSet.pageRepository.getAllPagesSnapshot()

            val ringBuffer = repoSet.ringBuffer?.also { it.enabled = true }

            val baseFs = PlatformFileSystem()
            val fileSystem = if (useSaf) LatencyShimFileSystem(baseFs) else baseFs
            val loader = GraphLoader(
                fileSystem,
                repoSet.pageRepository,
                repoSet.blockRepository,
                externalWriteActor = repoSet.writeActor,
                histogramWriter = repoSet.histogramWriter,
            )
            val writer = GraphWriter(
                fileSystem,
                writeActor = repoSet.writeActor,
                graphPath = tempDir.absolutePath,
            )

            val deps = StelekitViewModelDependencies(
                pageRepository   = repoSet.pageRepository,
                blockRepository  = repoSet.blockRepository,
                searchRepository = repoSet.searchRepository,
                graphLoader      = loader,
                graphWriter      = writer,
                fileSystem       = fileSystem,
                platformSettings = BenchmarkGraphUtils.noopSettings(),
                scope            = scope,
                writeActor       = repoSet.writeActor,
                undoManager      = repoSet.undoManager,
                histogramWriter  = repoSet.histogramWriter,
                ringBuffer       = ringBuffer,
            )
            viewModel = StelekitViewModel(deps)
            val vm = viewModel

            vm.setGraphPath(tempDir.absolutePath)
            delay(200)
            println("[user-session] Waiting for graph to load (isFullyLoaded)…")
            withTimeoutOrNull(180_000) { vm.uiState.first { it.isFullyLoaded } }
                ?: println("[user-session] WARNING — graph did not reach isFullyLoaded after 180s")
            println("[user-session] isFullyLoaded: ${vm.uiState.value.isFullyLoaded}, status: ${vm.uiState.value.statusMessage}")

            // Wait for write actor to drain phase-2 inserts
            withTimeoutOrNull(120_000) {
                while (repoSet.writeActor?.hasPendingWrites == true) delay(200)
            } ?: println("[user-session] WARNING — write actor did not drain after 120s")

            val allPages = repoSet.pageRepository.getAllPagesSnapshot().getOrNull() ?: emptyList()
            println("[user-session] Pages in DB after actor drain: ${allPages.size}")
            if (allPages.isEmpty()) {
                println("[user-session] SKIPPED — no pages loaded from $graphPath after 60s")
                return@runBlocking
            }
            println("[user-session] Graph loaded: ${allPages.size} pages")

            // Pre-sample pages and queries deterministically
            val rnd = Random(42)
            val navPages = allPages.filter { !it.isJournal }.shuffled(rnd).take(20)
            val renamePages = allPages.filter { !it.isJournal }.shuffled(Random(43)).take(5).toMutableList()
            val queryPool = allPages.shuffled(Random(44))
                .mapNotNull { it.name.take(5).trim().takeIf { q -> q.length >= 2 } }
                .distinct().take(20)
                .let { q -> if (q.size < 20) q + listOf("the", "is", "and", "for", "on", "at", "by", "with", "from", "to") else q }

            // Find pages with enough blocks for structural ops
            val blockPage = run {
                var best: Page = navPages.first()
                var bestCount = 0
                for (p in navPages.take(8)) {
                    val n = repoSet.blockRepository.getBlocksForPage(p.uuid)
                        .first().getOrNull()?.size ?: 0
                    if (n > bestCount) { bestCount = n; best = p }
                }
                best
            }
            val allBlocks = repoSet.blockRepository.getBlocksForPage(blockPage.uuid)
                .first().getOrNull() ?: emptyList()
            // Blocks with a previous sibling are safe for delete/merge/reorder
            val sortedBlocks = allBlocks.sortedBy { it.position }
            val safeBlocks = sortedBlocks.drop(1) // skip first to ensure there's always a predecessor

            val outputDir = File(System.getProperty("benchmark.output.dir") ?: "build/reports")

            // ─────────────────────────────────────────────────────────────────
            // Interleaved cycles: each cycle navigates to a page, then performs
            // a mix of writes concurrent with subsequent navigation reads.
            //
            // Cycle layout (20 pages × 4 cycles of 5):
            //   Cycle 1 (pages 0-4):  nav + type(2) + search + indent
            //   Cycle 2 (pages 5-9):  nav + type(2) + search + outdent + delete
            //   Cycle 3 (pages 10-14): nav + type(2) + search + merge + reorder
            //   Cycle 4 (pages 15-19): nav + type(2) + search + move-up + move-down
            //
            // A rename is scattered every 4 navigations. Post-rename searches close out.
            // ─────────────────────────────────────────────────────────────────

            var queryIdx = 0
            var renameIdx = 0
            var safeIdx = 0

            fun nextQuery() = queryPool[queryIdx++ % queryPool.size]
            fun nextSafe() = if (safeBlocks.isEmpty()) null else safeBlocks[safeIdx++ % safeBlocks.size]

            println("[user-session] Starting interleaved cycles (${navPages.size} pages)…")

            for ((i, page) in navPages.withIndex()) {
                // ── navigate ──────────────────────────────────────────────────
                navigateTo(vm, page)

                // ── type 2 blocks ─────────────────────────────────────────────
                typeBlock(vm, page.uuid.value)
                typeBlock(vm, page.uuid.value)

                // ── search (every other navigation) ───────────────────────────
                if (i % 2 == 0) search(vm, nextQuery())

                // ── cycle-specific structural ops ──────────────────────────────
                val cycle = i / 5
                val block = nextSafe()
                when {
                    cycle == 0 && block != null -> indentBlock(vm, block)
                    cycle == 1 && block != null -> {
                        outdentBlock(vm, block)
                        nextSafe()?.let { deleteBlock(vm, it) }
                    }
                    cycle == 2 && block != null -> {
                        mergeBlock(vm, block)
                        nextSafe()?.let { b ->
                            val newPos = dev.stapler.stelekit.util.FractionalIndexing.generateKeyBetween(b.position, null)
                            reorderBlock(vm, b, newPos)
                        }
                    }
                    cycle == 3 && block != null -> {
                        moveUp(vm, block)
                        nextSafe()?.let { moveDown(vm, it) }
                    }
                }

                // ── rename every 4th navigation ────────────────────────────────
                if (i % 4 == 3 && renameIdx < renamePages.size) {
                    val target = renamePages[renameIdx++]
                    rename(vm, target, "bench-$renameIdx-${i}-${System.currentTimeMillis()}")
                }
            }
            println("[user-session] Cycles complete. Remaining structural ops…")

            // ── undo 5x ───────────────────────────────────────────────────────
            repeat(5) {
                val elapsed = measureTime { vm.undo(); delay(50) }
                undoSamples.add(elapsed.inWholeMilliseconds)
            }

            // ── navigate back 5x ──────────────────────────────────────────────
            repeat(5) {
                val elapsed = measureTime { vm.goBack(); delay(20) }
                backSamples.add(elapsed.inWholeMilliseconds)
            }

            // ── post-rename search (exposes FTS after writes) ─────────────────
            repeat(5) { search(vm, nextQuery()) }

            println("[user-session] Actions E-G: ${indentSamples.size} indent, ${outdentSamples.size} outdent, ${moveSamples.size} move, ${deleteSamples.size} delete, ${mergeSamples.size} merge, ${reorderSamples.size} reorder")
            println("[user-session] Total searches: ${searchSamples.size}")

            // ── drain spans and query stats ───────────────────────────────────
            ringBuffer?.let { rb ->
                val spans = rb.drain()
                println("[user-session] Captured ${spans.size} spans")
                try {
                    outputDir.mkdirs()
                    File(outputDir, "benchmark-session-spans.json").writeText(
                        Json { prettyPrint = true }.encodeToString(spans)
                    )
                } catch (_: Exception) {}
            }

            repoSet.queryStatsCollector?.drainNow()
            val queryStats = repoSet.queryStatsRepository?.getTopByTotalMs("unknown", 20) ?: emptyList()
            BenchmarkGraphUtils.writeQueryStatsJson(
                File(outputDir, "benchmark-session-query-stats.json"),
                queryStats,
            )

            // ── latency report ────────────────────────────────────────────────
            data class ActionStat(val key: String, val samples: List<Long>)
            val actions = listOf(
                ActionStat("navigate_page",  navigateSamples),
                ActionStat("type_block",     typeSamples),
                ActionStat("search",         searchSamples),
                ActionStat("rename_page",    renameSamples),
                ActionStat("delete_block",   deleteSamples),
                ActionStat("merge_block",    mergeSamples),
                ActionStat("reorder_block",  reorderSamples),
                ActionStat("indent_block",   indentSamples),
                ActionStat("outdent_block",  outdentSamples),
                ActionStat("move_block",     moveSamples),
                ActionStat("undo",           undoSamples),
                ActionStat("navigate_back",  backSamples),
            )

            val suffix = if (useSaf) " [SAF]" else ""
            println("\n[user-session$suffix] Latency per action (ms):")
            println("%-22s %5s %6s %6s %6s %6s".format("Action", "n", "p50", "p95", "p99", "max"))
            println("─".repeat(54))
            for (a in actions) {
                if (a.samples.isEmpty()) {
                    println("%-22s %5d  (no data)".format(a.key, 0))
                } else {
                    println(
                        "%-22s %5d %6d %6d %6d %6d".format(
                            a.key, a.samples.size,
                            BenchmarkGraphUtils.percentile(a.samples, 50),
                            BenchmarkGraphUtils.percentile(a.samples, 95),
                            BenchmarkGraphUtils.percentile(a.samples, 99),
                            a.samples.max(),
                        )
                    )
                }
            }

            val gitSha = try {
                ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .start().inputStream.bufferedReader().readLine() ?: "unknown"
            } catch (_: Exception) { "unknown" }

            try {
                outputDir.mkdirs()
                val sb = StringBuilder("{\n")
                sb.append("  \"gitSha\": \"$gitSha\",\n")
                sb.append("  \"graphPageCount\": ${allPages.size},\n")
                sb.append("  \"safLatency\": $useSaf,\n")
                sb.append("  \"actions\": [\n")
                actions.forEachIndexed { idx, a ->
                    if (a.samples.isEmpty()) {
                        sb.append("    {\"action\": \"${a.key}\", \"n\": 0, \"p50Ms\": 0, \"p95Ms\": 0, \"p99Ms\": 0, \"maxMs\": 0}")
                    } else {
                        sb.append(
                            "    {\"action\": \"${a.key}\", \"n\": ${a.samples.size}, " +
                            "\"p50Ms\": ${BenchmarkGraphUtils.percentile(a.samples, 50)}, " +
                            "\"p95Ms\": ${BenchmarkGraphUtils.percentile(a.samples, 95)}, " +
                            "\"p99Ms\": ${BenchmarkGraphUtils.percentile(a.samples, 99)}, " +
                            "\"maxMs\": ${a.samples.max()}}"
                        )
                    }
                    if (idx < actions.size - 1) sb.append(",")
                    sb.append("\n")
                }
                sb.append("  ]\n}")
                val outFile = if (useSaf) "benchmark-session-saf.json" else "benchmark-session.json"
                File(outputDir, outFile).writeText(sb.toString())
            } catch (_: Exception) {}

            assertTrue(allPages.size > 0, "Should have loaded pages from $graphPath")

        } finally {
            viewModel?.close()
            scope.cancel()
            runCatching { factory?.close() }
            dbFile?.let { f ->
                for (suffix in listOf("", "-wal", "-shm")) java.io.File("${f.absolutePath}$suffix").delete()
            }
            tempDir?.deleteRecursively()
        }
    }
}
