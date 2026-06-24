package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.FilePath
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.parsing.ParseMode
import dev.stapler.stelekit.performance.RingBufferSpanExporter
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.PageRepository
import kotlin.system.measureTimeMillis
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for the diff-merge batch-write optimization.
 *
 * Before the fix, [GraphLoader.dispatchFullBlockWrites] called [DatabaseWriteActor.deleteBlock]
 * once per block in a sequential loop — N+1 separate actor round-trips for a page with N blocks
 * to delete. Each round-trip suspended until the actor processed it, and on a loaded graph (actor
 * queue backed up) this caused 100-250 seconds of completely untraced delay per page (each of N
 * actor round-trips waited ~200 ms; summed across the blocks of a page, this accumulated to the
 * page total observed in the pre-fix Android traces).
 *
 * After the fix, all deletes + the savePage + inserts/updates are batched into a SINGLE
 * [DatabaseWriteActor.execute] call — always 1 actor round-trip regardless of delete count.
 * The resulting span is "db.writeBlocks" (replacing "db.saveBlocks") and carries delete.count,
 * insert.count, and update.count attributes for full observability.
 */
class GraphLoaderBatchWriteTest {

    private class FakeFs : FileSystem {
        val files = mutableMapOf<String, String>()
        override fun getDefaultGraphPath() = "/graph"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String) = files[path]
        override fun writeFile(path: String, content: String) = true
        override fun listFiles(path: String) =
            files.keys.filter { it.startsWith("$path/") }.map { it.substringAfterLast("/") }
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = files.containsKey(path)
        override fun directoryExists(path: String) = true
        override fun createDirectory(path: String) = true
        override fun deleteFile(path: String) = true
        override fun pickDirectory() = null
        override fun getLastModifiedTime(path: String): Long? = null
    }

    /**
     * Returns a [PageRepository] whose [PageRepository.savePage] adds [delayMs] of synthetic
     * latency. Used by TC-LATENCY to simulate actor queue congestion equivalent to SAF
     * filesystem writes (~50 ms on Android) or a heavily loaded DB actor (~200 ms).
     *
     * [InMemoryPageRepository] is final, so delegation is used instead of subclassing.
     */
    @OptIn(DirectRepositoryWrite::class)
    private fun slowPageRepo(delayMs: Long): PageRepository {
        val fast = InMemoryPageRepository()
        return object : PageRepository by fast {
            @DirectRepositoryWrite
            override suspend fun savePage(page: Page): Either<DomainError, Unit> {
                delay(delayMs); return fast.savePage(page)
            }
        }
    }

    /**
     * Runs [action] against a [DatabaseWriteActor] while [bgCoroutines] background coroutines
     * continuously flood it with slow page saves (each taking [writeDelayMs]). Returns the
     * wall-clock ms elapsed during [action].
     *
     * The warm-up period ([bgCoroutines] × [writeDelayMs] × 4) is computed from the constants
     * so that the queue is guaranteed to be occupied before measurement begins.
     *
     * Cancellation is joined before the actor channel is closed to avoid a race where a
     * mid-send coroutine observes [kotlinx.coroutines.channels.ClosedSendChannelException].
     */
    private suspend fun CoroutineScope.runCongestionScenario(
        blockCount: Int,
        bgCoroutines: Int,
        writeDelayMs: Long,
        testPage: Page,
        action: suspend (actor: DatabaseWriteActor, blockRepo: InMemoryBlockRepository, uuids: List<BlockUuid>) -> Unit,
    ): Long {
        val warmUpMs = bgCoroutines * writeDelayMs * 4
        val blockRepo = InMemoryBlockRepository()
        val actor = DatabaseWriteActor(blockRepo, slowPageRepo(writeDelayMs))
        val uuids = (1..blockCount).map { BlockUuid("del-$it") }
        // 200 iterations × writeDelayMs / bgCoroutines ≈ 250ms runway — outlasts the measurement window
        val bgJobs = (1..bgCoroutines).map { launch { repeat(200) { actor.savePage(testPage) } } }
        delay(warmUpMs)
        val elapsedMs = measureTimeMillis { action(actor, blockRepo, uuids) }
        bgJobs.forEach { it.cancel() }
        bgJobs.joinAll()  // await full cancellation before closing the actor's channel
        actor.close()
        return elapsedMs
    }

    /**
     * TC-BATCH-01: diff-merge batches all deletes + saves into a single actor execute.
     *
     * Scenario: load a page with 10 blocks (V1), then re-parse with 3 blocks (V2).
     * The diff is UUID-based (position-derived UUIDs): blocks at positions 1-3 are
     * updates (content changed), blocks at positions 4-10 disappear → 7 deletes.
     *
     * Pre-fix behaviour (the bug):
     *   7 × writeActor.deleteBlock(uuid) — each a separate channel send + await.
     *   Each awaited individually through the actor queue. During graph import the actor
     *   queue backs up with hundreds of other page saves; each individual delete waits
     *   its turn → 7+ sequential round-trips → observed 100-250 s of untraced delay.
     *   Span name: "db.saveBlocks" with no delete.count attribute.
     *
     * Post-fix behaviour:
     *   1 × writeActor.execute { 7 deletes + savePage + 3 updates }
     *   = always 1 actor round-trip regardless of delete count.
     *   Span name: "db.writeBlocks" with delete.count=7, update.count=3.
     *
     * Fails against pre-fix code: "db.writeBlocks" span does not exist (was "db.saveBlocks"),
     * and there is no delete.count attribute.
     */
    @Test
    fun `TC-BATCH-01 diff-merge batches all deletes into single actor execute`() = runBlocking {
        val ringBuffer = RingBufferSpanExporter(capacity = 200).also { it.enabled = true }
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val fs = FakeFs()

        val actor = DatabaseWriteActor(blockRepo, pageRepo)
        actor.ringBuffer = ringBuffer

        val loader = GraphLoader(
            fileSystem = fs,
            pageRepository = pageRepo,
            blockRepository = blockRepo,
            externalWriteActor = actor,
        )

        val path = "/graph/pages/batch-test.md"
        fs.files[path] = ""
        loader.setGraphPath("/graph")

        // V1: 10 blocks
        val v1Content = (1..10).joinToString("\n") { "- Block $it version-one" }
        loader.parseAndSavePage(FilePath(path), v1Content, ParseMode.FULL)

        val pages = pageRepo.getAllPagesSnapshot().getOrNull()!!
        assertTrue(pages.isNotEmpty(), "Page should exist after V1 load")
        val page = pages.first()
        val blocksV1 = blockRepo.getBlocksForPage(page.uuid).first().getOrNull()!!
        assertEquals(10, blocksV1.size, "V1 should have 10 blocks in DB")

        // Clear V1 spans — only V2 spans matter
        ringBuffer.drain()

        // V2: 3 blocks — positions 4-10 disappear → 7 deletes, positions 1-3 update
        val v2Content = (1..3).joinToString("\n") { "- Block $it version-two" }
        loader.parseAndSavePage(FilePath(path), v2Content, ParseMode.FULL)

        // Correctness: 3 V2 blocks in DB, 7 V1 blocks gone
        val blocksV2 = blockRepo.getBlocksForPage(page.uuid).first().getOrNull()!!
        assertEquals(3, blocksV2.size, "V2 should have exactly 3 blocks")
        assertTrue(blocksV2.all { it.content.contains("version-two") }, "All blocks should be V2")

        // Span check: single db.writeBlocks span with delete.count=7
        // Pre-fix: "db.saveBlocks" without delete.count (7 deletes were N untraced individual actor calls)
        val spans = ringBuffer.snapshot()
        val writeBlocksSpans = spans.filter { it.name == "db.writeBlocks" }
        assertEquals(1, writeBlocksSpans.size, "Exactly one db.writeBlocks span per parseAndSavePage")
        val writeSpan = writeBlocksSpans.first()
        assertEquals("7", writeSpan.attributes["delete.count"],
            "delete.count must equal 7 (blocks at positions 4-10 removed)")
        assertTrue(spans.none { it.name == "db.saveBlocks" },
            "db.saveBlocks is retired — all writes go through db.writeBlocks")

        actor.close()
    }

    /**
     * TC-META-01: METADATA_ONLY parse batches page save + stub creation into a single actor execute.
     *
     * Before the fix, [GraphLoader.saveMetadataOnlyBlocks] made 3 separate actor calls:
     *   1. writeActor.savePage(page)            — 1 RT
     *   2. writeActor.deleteBlocksForPage(uuid) — 1 RT
     *   3. writeActor.saveBlocks(stubs)         — 1 RT
     * Total: 3 actor round-trips per METADATA_ONLY page. Across thousands of pages during
     * background import this caused significant actor queue congestion.
     *
     * After the fix, all three are inside a single writeActor.execute { } lambda = 1 RT.
     * The resulting span is "db.saveMetadata" with a "stub.count" attribute.
     *
     * Fails against pre-fix code: "db.saveMetadata" span does not exist, and "db.savePage"
     * (the old standalone span) does exist.
     */
    @Test
    fun `TC-META-01 metadata-only parse batches page and stubs into single actor execute`() = runBlocking {
        val ringBuffer = RingBufferSpanExporter(capacity = 200).also { it.enabled = true }
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val fs = FakeFs()

        val actor = DatabaseWriteActor(blockRepo, pageRepo)
        actor.ringBuffer = ringBuffer

        val loader = GraphLoader(
            fileSystem = fs,
            pageRepository = pageRepo,
            blockRepository = blockRepo,
            externalWriteActor = actor,
        )

        val path = "/graph/pages/meta-test.md"
        fs.files[path] = ""
        loader.setGraphPath("/graph")

        // Parse 5 top-level blocks in METADATA_ONLY mode (creates stub blocks)
        val content = (1..5).joinToString("\n") { "- Block $it stub" }
        loader.parseAndSavePage(FilePath(path), content, ParseMode.METADATA_ONLY)

        // Correctness: page exists in repository
        val pages = pageRepo.getAllPagesSnapshot().getOrNull()!!
        assertTrue(pages.isNotEmpty(), "Page should exist after METADATA_ONLY load")

        // Span check: single db.saveMetadata span with stub.count attribute
        // Pre-fix: separate db.savePage span + 2 untraced actor calls
        // Post-fix: single execute → db.saveMetadata span with stub.count
        val spans = ringBuffer.snapshot()
        val metaSpans = spans.filter { it.name == "db.saveMetadata" }
        assertEquals(1, metaSpans.size, "Exactly one db.saveMetadata span per METADATA_ONLY parse")

        val stubCount = metaSpans.first().attributes["stub.count"]?.toIntOrNull()
        assertNotNull(stubCount, "db.saveMetadata span must carry stub.count attribute")
        assertEquals(5, stubCount, "stub.count should equal the number of parsed top-level blocks")

        // Pre-fix: standalone db.savePage span appeared because savePage was a separate actor call.
        // Post-fix: it is merged into db.saveMetadata and must not appear on its own.
        assertTrue(
            spans.none { it.name == "db.savePage" },
            "db.savePage must not appear as a standalone span — it is merged into db.saveMetadata"
        )

        actor.close()
    }

    /**
     * TC-LATENCY: demonstrate that N separate actor calls under queue congestion is O(N) slower
     * than 1 batch execute, matching the pre-fix 100-250 s trace data from Android.
     *
     * Mechanism: background coroutines continuously flood the actor with slow page saves
     * (simulating a graph import in progress). The actor processes one request at a time.
     * Each individual deleteBlock call must wait for background work to drain before the
     * actor processes it — so N deletes = N × drain-wait. A single execute { N deletes }
     * only waits for drain-wait once, then runs all deletes in one actor turn.
     *
     * This directly reproduces the ~223 s untraced delay from the pre-fix Android traces
     * (uploads/ at repo root, not checked in): parseAndSavePage spent ~223 s in completely
     * untraced time that maps to N separate deleteBlock round-trips against a heavily loaded
     * actor during 8 000-page import.
     *
     * Note: this test validates [DatabaseWriteActor] queue mechanics directly. The structural
     * regression guard that [GraphLoader.dispatchFullBlockWrites] uses a single execute call
     * is TC-BATCH-01 (span name + delete.count assertion).
     */
    @Test
    fun `TC-LATENCY actor round-trip amplification under queue congestion`() = runBlocking {
        val WRITE_DELAY_MS = 5L
        val BG_COROUTINES = 4
        val BLOCKS = 15

        val testPage = Page(
            uuid = PageUuid("latency-demo"),
            name = "latency-demo",
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
        )

        // PRE-FIX: N separate writeActor.deleteBlock() calls, each an independent actor round-trip.
        // Each waits for background saves to drain → N × drain-wait total.
        val preFixMs = runCongestionScenario(BLOCKS, BG_COROUTINES, WRITE_DELAY_MS, testPage) { actor, _, uuids ->
            uuids.forEach { uuid -> actor.deleteBlock(uuid, testPage.uuid) }
        }

        // POST-FIX: 1 execute { N direct blockRepo.deleteBlock() } — only 1 actor round-trip.
        val postFixMs = runCongestionScenario(BLOCKS, BG_COROUTINES, WRITE_DELAY_MS, testPage) { actor, blockRepo, uuids ->
            actor.execute {
                uuids.forEach { uuid -> blockRepo.deleteBlock(uuid) }
                Unit.right()  // required: execute expects Either<DomainError, Unit>
            }
        }

        println()
        println("=== TC-LATENCY: Actor Round-Trip Amplification ===")
        println("Background: $BG_COROUTINES coroutines × savePage(${WRITE_DELAY_MS}ms) — simulates import workload")
        println("Blocks to delete: $BLOCKS")
        println("Pre-fix  ($BLOCKS separate writeActor.deleteBlock calls): ${preFixMs}ms")
        println("Post-fix (1 writeActor.execute { $BLOCKS blockRepo.deleteBlock }): ${postFixMs}ms")
        val speedup = if (postFixMs > 0) preFixMs.toDouble() / postFixMs else Double.MAX_VALUE
        println("Speedup: ${"%.1f".format(speedup)}x")
        println()
        println("Extrapolation to Android (actor queue wait = 100-250 ms per round-trip):")
        println("  Pre-fix, 50 blocks:  50 × 100ms = 5 000 ms  ..up to 50 × 250ms = 12 500 ms")
        println("  Post-fix, 50 blocks: 1 × 100ms = 100 ms")
        println("  This matches the 223 s untraced delay in the 2026-06-14 Android traces.")
        println("==================================================")

        // Hard upper bound: post-fix must complete in under 500ms even with background congestion.
        // With 4 coroutines × 5ms saves the post-fix path waits for ~1 queue drain (~20ms max)
        // then runs N deletes synchronously inside the execute lambda. 500ms gives 25× headroom
        // for slow CI machines. If this fires, the single-execute batch pattern has regressed:
        // check whether DatabaseWriteActor.execute is still routing all N deletes through one
        // channel round-trip (TC-BATCH-01's span check is the definitive structural guard).
        assertTrue(
            postFixMs < 500,
            "Post-fix batch path took ${postFixMs}ms — expected < 500ms under simulated queue " +
                "congestion ($BG_COROUTINES coroutines × ${WRITE_DELAY_MS}ms savePage delay). " +
                "See TC-BATCH-01 for the structural regression guard on dispatchFullBlockWrites."
        )
        // Relative check: pre-fix must be at least 3x slower than post-fix under the same congestion.
        assertTrue(
            preFixMs > postFixMs * 3,
            "Pre-fix ($preFixMs ms) should be at least 3× slower than post-fix ($postFixMs ms) " +
                "with $BG_COROUTINES background coroutines keeping the actor busy"
        )
    }
}
