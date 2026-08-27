package dev.stapler.stelekit.transfer.qrcode

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.LogseqPageSerializer
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageName
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.transfer.TransferId
import dev.stapler.stelekit.util.ContentHasher
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking

/**
 * Story 3.3.1 — the v1 success-metric gate (plan.md "v1 Definition of Done", validation.md Happy
 * Path Scenario): `serialize -> TransferPayloadEnvelope.wrap -> FountainEncoder -> (simulated lossy
 * channel) -> FountainDecoder -> TransferPayloadEnvelope.unwrap -> GraphLoader.importMarkdownString
 * -> OutlinerPipeline` must reproduce the source page's `Block.contentHash` set exactly AND the
 * source page's real name (not a synthesized placeholder). If this test fails, the bug is real —
 * find and fix it in `FountainEncoder`/`ChunkBuffer`/`TransferPayloadEnvelope`/the parse tail; do
 * not weaken either assertion.
 *
 * Page-name-envelope fix: the wire protocol used to carry only block content, not the page title
 * (Logseq derives a page's name from its filename, not file content, and
 * [dev.stapler.stelekit.db.LogseqPageSerializer.serialize] never embeds one) — this test now
 * exercises [TransferPayloadEnvelope.wrap]/[unwrap] exactly as [dev.stapler.stelekit.ui.transfer.QrEncodeViewModel]
 * / [QrTransferCoordinator] do in production, so the RECEIVED PAGE NAME assertion below is a
 * strictly stronger fidelity guarantee than before, added alongside (not replacing) the existing
 * `Block.contentHash` set-equality assertion.
 */
class QrRoundTripFidelityTest {

    private class NoOpFileSystem : FileSystem {
        override fun getDefaultGraphPath() = ""
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = false
        override fun listFiles(path: String): List<String> = emptyList()
        override fun listDirectories(path: String): List<String> = emptyList()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = false
        override fun createDirectory(path: String) = false
        override fun deleteFile(path: String) = false
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
    }

    private fun graphLoader(): GraphLoader {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        return GraphLoader(fileSystem = NoOpFileSystem(), pageRepository = pageRepo, blockRepository = blockRepo)
    }

    private fun uuid(n: Int): String = "00000000-0000-0000-0000-" + n.toString().padStart(12, '0')

    /**
     * 20 blocks (10 root + 10 children), nested (block-level) properties on several blocks, and
     * one Unicode block ("café ☕ — note") whose multi-byte UTF-8 content is a codepoint-aware
     * chunking regression guard, not decoration.
     *
     * Deliberately carries no PAGE-level properties: `GraphLoader.importMarkdownString`'s parse
     * tail (`MarkdownPageParser.buildPageModel`'s `firstBlockSkipped` merge) does not fold a
     * multi-line page-property preamble (`LogseqPageSerializer`'s own `key:: value` lines before
     * the first bullet) back into `Page.properties` — each such line instead round-trips as its
     * own literal-content block. That is a pre-existing `MarkdownParser`/`OutlinerParser` behavior
     * orthogonal to this feature's fountain/chunk pipeline (confirmed here: the reassembled
     * markdown byte-for-byte matched the original — see the `verified.markdown` assertion below —
     * the extra blocks came from parsing, not transfer), so it is out of scope for Epic 3.3 to fix.
     * Block-level ("nested") properties are exercised below and DO round-trip correctly.
     */
    private fun buildFixture(): Pair<Page, List<Block>> {
        val now = Clock.System.now()
        val pageUuid = PageUuid(uuid(1))
        val page = Page(
            uuid = pageUuid,
            name = "QR Fidelity Fixture",
            createdAt = now,
            updatedAt = now,
        )

        val blocks = mutableListOf<Block>()
        var nextUuid = 10
        repeat(10) { rootIndex ->
            val rootUuid = BlockUuid(uuid(nextUuid++))
            val rootContent = if (rootIndex == 4) "café ☕ — note" else "root block $rootIndex with some filler content"
            blocks += Block(
                uuid = rootUuid,
                pageUuid = pageUuid,
                content = rootContent,
                level = 0,
                position = "a$rootIndex",
                parentUuid = null,
                createdAt = now,
                updatedAt = now,
                properties = if (rootIndex % 3 == 0) mapOf("status" to "done") else emptyMap(),
            )

            val childUuid = BlockUuid(uuid(nextUuid++))
            blocks += Block(
                uuid = childUuid,
                pageUuid = pageUuid,
                content = "child of root $rootIndex",
                level = 1,
                position = "a0",
                parentUuid = rootUuid,
                createdAt = now,
                updatedAt = now,
                properties = if (rootIndex % 5 == 0) mapOf("priority" to "high") else emptyMap(),
            )
        }

        assertEquals(20, blocks.size, "fixture must contain exactly 20 blocks")
        return page to blocks
    }

    /** BC-UR parts through a channel that drops [dropFraction] of frames in random order. */
    private fun deliverThroughLossyChannel(
        encoder: FountainEncoder,
        random: Random,
        dropFraction: Double,
    ): List<FountainChunk> {
        val generated = encoder.parts().take(encoder.seqLen * 4).toList()
        return generated.filter { random.nextDouble() >= dropFraction }.shuffled(random)
    }

    @Test
    fun pipeline_should_PreserveAllBlockContentHashes_When_TwentyBlockFixturePageEncodedThroughLossyChannelAndReassembled() = runBlocking {
        val (page, originalBlocks) = buildFixture()
        val markdown = LogseqPageSerializer.serialize(page, originalBlocks)
        val envelopeBytes = TransferPayloadEnvelope.wrap(PageName(page.name), markdown)

        val encoder = FountainCodec.encoder(TransferId(1001), envelopeBytes, maxFragmentBytes = 48).getOrNull()!!
        val random = Random(20260711)
        val delivered = deliverThroughLossyChannel(encoder, random, dropFraction = 0.25)

        val buffer = ChunkBuffer(maxPayloadBytes = FountainEncoder.DEFAULT_MAX_PAYLOAD_BYTES)
        for (chunk in delivered) {
            buffer.accept(chunk)
            if (buffer.isComplete()) break
        }
        assertTrue(buffer.isComplete(), "decoder should reach completion despite a 25% frame drop")

        val verified = buffer.reassemble().getOrNull()
        assertTrue(verified != null, "reassembly must pass the CRC32 proof gate")

        val (decodedName, decodedMarkdown) = TransferPayloadEnvelope.unwrap(verified!!.markdown.encodeToByteArray()).getOrNull()
            ?: error("envelope unwrap must succeed for a proof-gated payload")
        assertEquals(page.name, decodedName.value, "the received page name must match the sender's real page name (the page-name fidelity gate)")
        assertEquals(markdown, decodedMarkdown, "unwrapped markdown must match the original byte-for-byte")

        val (_, reparsedBlocks) = graphLoader().importMarkdownString(decodedMarkdown, decodedName)
            .getOrNull() ?: error("importMarkdownString must succeed for a proof-gated payload")

        val originalHashes = originalBlocks.map { ContentHasher.sha256ForContent(it.content) }.toSet()
        val reparsedHashes = reparsedBlocks.map { it.contentHash }.toSet()

        assertEquals(20, reparsedBlocks.size, "every block must survive the round trip")
        assertEquals(originalHashes, reparsedHashes, "Block.contentHash set must match exactly (the v1 fidelity gate)")
    }

    @Test
    fun pipeline_should_PreserveMultiByteUtf8Block_When_CodepointSplitsAcrossChunkBoundary() = runBlocking {
        // 'é' (2 bytes), '☕' (3 bytes), '—' (3 bytes) alongside 1-byte ASCII: irregular byte
        // widths guarantee that SOME fragment boundary lands mid-codepoint at this tiny
        // maxFragmentBytes, exercising the exact regression this test guards against — a naive
        // byte-oriented split must never corrupt the string once fully reassembled.
        val now = Clock.System.now()
        val pageUuid = PageUuid(uuid(2))
        val page = Page(uuid = pageUuid, name = "Utf8 Split Fixture", createdAt = now, updatedAt = now)
        val block = Block(
            uuid = BlockUuid(uuid(20)),
            pageUuid = pageUuid,
            content = "café ☕ — note, padded so multiple fragment boundaries land inside the multi-byte run: café ☕ — note",
            level = 0,
            position = "a0",
            parentUuid = null,
            createdAt = now,
            updatedAt = now,
        )
        val markdown = LogseqPageSerializer.serialize(page, listOf(block))
        val envelopeBytes = TransferPayloadEnvelope.wrap(PageName(page.name), markdown)

        // minFragmentBytes=1 (below FountainEncoder's default of 10) so a 6-byte maxFragmentBytes
        // is actually honored — this is what forces boundaries into the multi-byte run below.
        val encoder = FountainEncoder(TransferId(1002), envelopeBytes, maxFragmentBytes = 6, minFragmentBytes = 1).getOrNull()!!
        val random = Random(90210)
        val delivered = deliverThroughLossyChannel(encoder, random, dropFraction = 0.25)

        val buffer = ChunkBuffer(maxPayloadBytes = FountainEncoder.DEFAULT_MAX_PAYLOAD_BYTES)
        for (chunk in delivered) {
            buffer.accept(chunk)
            if (buffer.isComplete()) break
        }
        assertTrue(buffer.isComplete())

        val verified = buffer.reassemble().getOrNull()
        assertTrue(verified != null, "reassembly must pass the CRC32 proof gate even with a tiny fragment size")

        val (decodedName, decodedMarkdown) = TransferPayloadEnvelope.unwrap(verified!!.markdown.encodeToByteArray()).getOrNull()
            ?: error("envelope unwrap must succeed for a proof-gated payload, even at a tiny fragment size")
        assertEquals(page.name, decodedName.value, "the received page name must survive a multi-byte-UTF8-hostile chunk size")
        assertEquals(markdown, decodedMarkdown)

        val (_, reparsedBlocks) = graphLoader().importMarkdownString(decodedMarkdown, decodedName)
            .getOrNull() ?: error("importMarkdownString must succeed for a proof-gated payload")

        assertEquals(1, reparsedBlocks.size)
        assertEquals(block.content, reparsedBlocks.single().content, "multi-byte UTF-8 content must survive byte-exact")
    }
}
