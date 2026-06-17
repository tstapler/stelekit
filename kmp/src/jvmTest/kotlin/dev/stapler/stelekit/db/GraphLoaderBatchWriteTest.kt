package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.FilePath
import dev.stapler.stelekit.parsing.ParseMode
import dev.stapler.stelekit.performance.RingBufferSpanExporter
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.flow.first
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
 * queue backed up) this caused 100-250 seconds of completely untraced delay per page.
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
}
