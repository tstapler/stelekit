package dev.stapler.stelekit.benchmark

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.repository.RepositoryFactoryImpl
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * CI-enforced block-insert latency benchmarks.
 *
 * TC-09: P99 insert latency (DB write) ≤ 200ms with SAF latency shim (simulated Android SAF IPC)
 * TC-10: P99 insert latency (DB write) ≤ 50ms without shim (JVM regression guard)
 *
 * Measurement: wall-clock time from [BlockStateManager.addBlockToPage] call start to
 * [Job.join] return — i.e. the DB write is committed. File writes are deferred by the
 * debounce and are NOT included in this measurement window, which is intentional: the
 * user perceives the latency only up to when the DB confirms the block (focus moves,
 * block appears).
 *
 * The [LatencyShimFileSystem] is NOT injected into [BlockStateManager] here because
 * [queueDiskSave] is skipped when no [GraphWriter] is configured — file write latency
 * is measured separately in [FileSystemCallCountTest]. The shim is injected into
 * [GraphWriter] to verify that the IO-dispatcher separation (Epic 1) prevents SAF
 * latency from starving the Default pool that runs the write actor.
 *
 * NOTE: After Epic 1 lands, [GraphWriter.savePageInternal] wraps all filesystem calls in
 * [withContext(PlatformDispatcher.IO)], so [Thread.sleep] in the shim blocks an IO-pool
 * thread and does NOT stall Default-dispatcher coroutines. Before Epic 1, the shim would
 * starve the Default pool and TC-09 would fail — that is the intended signal.
 */
class BlockInsertBenchmarkTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

    private fun makePage(tempDir: File): Page {
        val now = Clock.System.now()
        return Page(
            uuid = "bench-page-insert",
            name = "Bench Insert Page",
            createdAt = now,
            updatedAt = now,
            isJournal = false,
            filePath = "${tempDir.absolutePath}/pages/bench-insert-page.md",
        )
    }

    private data class BenchResult(val p50: Long, val p95: Long, val p99: Long)

    /**
     * Run N insert iterations via [BlockStateManager.addBlockToPage] and return
     * wall-clock latencies (ms from call start to [Job.join] — DB write committed).
     */
    private suspend fun runInserts(
        bsm: BlockStateManager,
        pageUuid: String,
        n: Int = 100,
    ): List<Long> {
        val latencies = mutableListOf<Long>()
        repeat(n) {
            val start = System.currentTimeMillis()
            bsm.addBlockToPage(pageUuid).join()
            latencies.add(System.currentTimeMillis() - start)
        }
        return latencies
    }

    private fun List<Long>.percentile(p: Double): Long {
        val sorted = sorted()
        val idx = (size * p).toInt().coerceIn(0, size - 1)
        return sorted[idx]
    }

    private fun writeBenchmarkJson(name: String, data: Map<String, Any>) {
        try {
            val outputDir = System.getProperty("benchmark.output.dir")
                ?.let { File(it) }
                ?: File("build/reports")
            outputDir.mkdirs()
            val file = File(outputDir, "$name.json")
            val sb = StringBuilder("{\n")
            val entries = data.entries.toList()
            entries.forEachIndexed { idx, (key, value) ->
                sb.append("  \"$key\": ")
                when (value) {
                    is String -> sb.append("\"$value\"")
                    is Long   -> sb.append(value)
                    is Int    -> sb.append(value)
                    else      -> sb.append("\"$value\"")
                }
                if (idx < entries.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("}")
            file.writeText(sb.toString())
            println("[benchmark] wrote $file")
        } catch (_: Exception) {
            // non-fatal — JSON output is informational
        }
    }

    /**
     * TC-09: P99 insert latency ≤ 200ms under SAF-like latency shim.
     *
     * Wires up a [LatencyShimFileSystem] into [GraphWriter]. The shim injects 50ms of
     * blocking latency per [writeFile] call (and 10ms per [fileExists], 30ms per
     * [readFile]) to simulate Android SAF Binder IPC. The assertion verifies that DB
     * writes complete well within 200ms even when file writes are slow — confirming
     * Epic 1 (IO dispatcher separation) is in effect.
     *
     * [GraphWriter.startAutoSave] is called so [queueSave] launches the debounced job,
     * but we do NOT join the debounced file write here — the latency measurement is
     * DB-only. The shim's [Thread.sleep] will run on the IO pool thread (after Epic 1),
     * not on the Default-dispatcher thread that [DatabaseWriteActor] uses.
     */
    @Test
    fun blockInsertLatency_syntheticGraph_shimmedSafFileSystem() = runBlocking {
        val tempDir = tempDir("block-insert-bench-shim")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val factory = RepositoryFactoryImpl(
                DriverFactory(),
                "jdbc:sqlite:${File(tempDir, "bench-shim.db").absolutePath}",
            )
            val repoSet = factory.createRepositorySet(GraphBackend.SQLDELIGHT, scope)
            val actor = requireNotNull(repoSet.writeActor) { "writeActor must not be null for SQLDELIGHT backend" }

            // Register the page so addBlockToPage can look it up
            val page = makePage(tempDir)
            actor.savePage(page)

            // Wire GraphWriter with the latency shim — file writes experience 50ms latency
            val shimFs = LatencyShimFileSystem(
                delegate = PlatformFileSystem(),
                writeLatencyMs = 50L,
                existsLatencyMs = 10L,
                readLatencyMs = 30L,
            )
            val graphWriter = GraphWriter(
                fileSystem = shimFs,
                writeActor = actor,
                graphPath = tempDir.absolutePath,
            )
            val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            graphWriter.startAutoSave(writerScope)

            val bsm = BlockStateManager(
                blockRepository = repoSet.blockRepository,
                graphLoader = dev.stapler.stelekit.db.GraphLoader(
                    fileSystem = shimFs,
                    pageRepository = repoSet.pageRepository,
                    blockRepository = repoSet.blockRepository,
                ),
                scope = scope,
                graphWriter = graphWriter,
                pageRepository = repoSet.pageRepository,
                graphPathProvider = { tempDir.absolutePath },
                writeActor = actor,
            )
            bsm.observePage(page.uuid)

            // Warm-up: 5 inserts not counted
            repeat(5) { bsm.addBlockToPage(page.uuid).join() }

            val latencies = runInserts(bsm, page.uuid, 100)
            val p50 = latencies.percentile(0.50)
            val p95 = latencies.percentile(0.95)
            val p99 = latencies.percentile(0.99)

            println("BlockInsert[shim] P50=${p50}ms P95=${p95}ms P99=${p99}ms")
            writeBenchmarkJson(
                "benchmark-insert",
                mapOf(
                    "variant"  to "shimmed-saf",
                    "sampleCount" to 100,
                    "p50Ms"    to p50,
                    "p95Ms"    to p95,
                    "p99Ms"    to p99,
                ),
            )

            assertTrue(
                p99 <= 200L,
                "TC-09: P99 insert latency ${p99}ms exceeds 200ms budget. " +
                    "This may indicate SAF Binder IPC (Thread.sleep in shim) is starving " +
                    "the Default dispatcher — verify Epic 1 (withContext(IO)) is in effect.",
            )

            writerScope.cancel()
            factory.close()
        } finally {
            scope.cancel()
            tempDir.deleteRecursively()
        }
    }

    /**
     * TC-10: P99 insert latency ≤ 50ms without latency shim (JVM regression guard).
     *
     * Runs the same benchmark with a no-op [FakeFileSystem] so file writes complete
     * instantly. This acts as a baseline regression guard: if this test fails, a
     * change to the DB write path has introduced unexpected latency in JVM tests,
     * independent of any Android SAF concern.
     */
    @Test
    fun blockInsertLatency_noShim_jvmBaseline() = runBlocking {
        val tempDir = tempDir("block-insert-bench-noshim")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val factory = RepositoryFactoryImpl(
                DriverFactory(),
                "jdbc:sqlite:${File(tempDir, "bench-noshim.db").absolutePath}",
            )
            val repoSet = factory.createRepositorySet(GraphBackend.SQLDELIGHT, scope)
            val actor = requireNotNull(repoSet.writeActor)

            val page = makePage(tempDir)
            actor.savePage(page)

            // FakeFileSystem: no filesystem latency — measures pure DB write cost
            val bsm = BlockStateManager(
                blockRepository = repoSet.blockRepository,
                graphLoader = dev.stapler.stelekit.db.GraphLoader(
                    fileSystem = FakeFileSystem(),
                    pageRepository = repoSet.pageRepository,
                    blockRepository = repoSet.blockRepository,
                ),
                scope = scope,
                writeActor = actor,
            )
            bsm.observePage(page.uuid)

            // Warm-up
            repeat(5) { bsm.addBlockToPage(page.uuid).join() }

            val latencies = runInserts(bsm, page.uuid, 100)
            val p50 = latencies.percentile(0.50)
            val p95 = latencies.percentile(0.95)
            val p99 = latencies.percentile(0.99)

            println("BlockInsert[no-shim] P50=${p50}ms P95=${p95}ms P99=${p99}ms")
            writeBenchmarkJson(
                "benchmark-insert-noshim",
                mapOf(
                    "variant"     to "no-shim",
                    "sampleCount" to 100,
                    "p50Ms"       to p50,
                    "p95Ms"       to p95,
                    "p99Ms"       to p99,
                ),
            )

            assertTrue(
                p99 <= 50L,
                "TC-10 (NFR-1): JVM P99 insert latency ${p99}ms exceeds 50ms budget. " +
                    "A regression in the DB write path has been introduced.",
            )

            factory.close()
        } finally {
            scope.cancel()
            tempDir.deleteRecursively()
        }
    }
}
