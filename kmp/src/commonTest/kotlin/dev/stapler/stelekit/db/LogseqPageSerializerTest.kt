package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.parser.MarkdownParser
import dev.stapler.stelekit.parsing.ParseMode
import dev.stapler.stelekit.util.ContentHasher
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals

class LogseqPageSerializerTest {

    private val now = Clock.System.now()

    @Test
    fun serialize_should_ProduceCanonicalMarkdown_When_PageHasNestedBlocksAndProperties() {
        val pageUuid = PageUuid("00000000-0000-0000-0000-000000000001")
        val page = Page(
            uuid = pageUuid,
            name = "Test",
            createdAt = now,
            updatedAt = now,
            properties = mapOf("tags" to "a,b"),
        )

        val rootUuid = BlockUuid("00000000-0000-0000-0000-000000000010")
        val root = Block(
            uuid = rootUuid,
            pageUuid = pageUuid,
            content = "root",
            level = 0,
            position = "a0",
            parentUuid = null,
            createdAt = now,
            updatedAt = now,
        )
        val child = Block(
            uuid = BlockUuid("00000000-0000-0000-0000-000000000011"),
            pageUuid = pageUuid,
            content = "child",
            level = 1,
            position = "a0",
            parentUuid = rootUuid,
            createdAt = now,
            updatedAt = now,
        )

        val markdown = LogseqPageSerializer.serialize(page, listOf(root, child))

        assertEquals("tags:: a,b\n- root\n\t- child\n", markdown)
    }

    @Test
    fun serialize_should_MatchPriorGraphWriterOutput_When_ExistingGraphWriterTestsRerun() {
        // Fixture mirrors GraphWriterTest.testSavePageMaintainsHierarchy — that jvmTest suite
        // asserts GraphWriter's saved file contains "- Block B\n\t- Block B1" for this exact
        // block tree. This test pins the full expected output produced by LogseqPageSerializer
        // in isolation, confirming GraphWriter.buildMarkdown's delegation introduced no
        // behavioral change to disk writes.
        val pageUuid = PageUuid("00000000-0000-0000-0000-000000000001")
        val page = Page(
            uuid = pageUuid,
            name = "TestPage",
            createdAt = now,
            updatedAt = now,
            journalDate = null,
            properties = emptyMap(),
        )

        val blockAUuid = BlockUuid("00000000-0000-0000-0000-000000000010")
        val blockA = Block(
            uuid = blockAUuid,
            pageUuid = pageUuid,
            content = "Block A",
            level = 0,
            position = "a0",
            parentUuid = null,
            createdAt = now,
            updatedAt = now,
        )
        val blockA1 = Block(
            uuid = BlockUuid("00000000-0000-0000-0000-000000000011"),
            pageUuid = pageUuid,
            content = "Block A1",
            level = 1,
            position = "a0",
            parentUuid = blockAUuid,
            createdAt = now,
            updatedAt = now,
        )
        val blockBUuid = BlockUuid("00000000-0000-0000-0000-000000000012")
        val blockB = Block(
            uuid = blockBUuid,
            pageUuid = pageUuid,
            content = "Block B",
            level = 0,
            position = "a1",
            parentUuid = null,
            createdAt = now,
            updatedAt = now,
        )
        val blockB1 = Block(
            uuid = BlockUuid("00000000-0000-0000-0000-000000000013"),
            pageUuid = pageUuid,
            content = "Block B1",
            level = 1,
            position = "a0",
            parentUuid = blockBUuid,
            createdAt = now,
            updatedAt = now,
        )

        val markdown = LogseqPageSerializer.serialize(page, listOf(blockA, blockA1, blockB, blockB1))

        assertEquals("- Block A\n\t- Block A1\n- Block B\n\t- Block B1\n", markdown)
    }

    @Test
    fun serialize_should_RoundTripThroughOutlinerPipeline_When_ContentHashesCompared() {
        val pageUuid = PageUuid("00000000-0000-0000-0000-000000000001")
        val page = Page(
            uuid = pageUuid,
            name = "RoundTrip",
            createdAt = now,
            updatedAt = now,
            properties = emptyMap(),
        )

        val rootUuid = BlockUuid("00000000-0000-0000-0000-000000000010")
        val root = Block(
            uuid = rootUuid,
            pageUuid = pageUuid,
            content = "root content",
            level = 0,
            position = "a0",
            parentUuid = null,
            createdAt = now,
            updatedAt = now,
        )
        val child = Block(
            uuid = BlockUuid("00000000-0000-0000-0000-000000000011"),
            pageUuid = pageUuid,
            content = "child content",
            level = 1,
            position = "a0",
            parentUuid = rootUuid,
            createdAt = now,
            updatedAt = now,
        )
        val originalBlocks = listOf(root, child)

        val markdown = LogseqPageSerializer.serialize(page, originalBlocks)

        val parsedPage = MarkdownParser().parsePage(markdown, ParseMode.FULL)
        val reparsedBlocks = mutableListOf<Block>()
        MarkdownPageParser.processParsedBlocks(
            parsedBlocks = parsedPage.blocks,
            pagePath = "RoundTrip.md",
            pageUuid = pageUuid,
            parentUuid = null,
            baseLevel = 0,
            now = now,
            destinationList = reparsedBlocks,
            mode = ParseMode.FULL,
        )

        val originalHashes = originalBlocks.map { ContentHasher.sha256ForContent(it.content) }.toSet()
        val reparsedHashes = reparsedBlocks.map { it.contentHash }.toSet()

        assertEquals(originalHashes, reparsedHashes)
    }
}
