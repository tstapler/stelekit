package dev.stapler.stelekit.merge

import dev.stapler.stelekit.db.LogseqPageSerializer
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.util.UuidGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MergeConvertersTest {
    private val path = MergeFixtures.PATH
    private val u1 = "11111111-1111-1111-1111-111111111111"
    private val u2 = "22222222-2222-2222-2222-222222222222"
    private val u3 = "33333333-3333-3333-3333-333333333333"

    private fun tree() = MergePage(
        name = "Notes",
        blocks = listOf(
            MergeBlock(u1, "a", mapOf("collapsed" to "true"), listOf(MergeBlock(u2, "b"), MergeBlock(u3, "c"))),
            MergeBlock(null, "d"),
        ),
    )

    @Test
    fun mergePageToBlocksSerializeParseRoundTrips() {
        val original = tree()
        val page = MergeConverters.toPage(original, path)
        val blocks = MergeConverters.toBlocks(original, page.uuid, path)
        assertEquals(original, MergeConverters.toMergePage(page, blocks))

        val text = LogseqPageSerializer.serialize(page, blocks)
        val reparsed = MergeConverters.parseMarkdown(text, path, "Notes", isJournal = false)
        assertEquals(original, reparsed.mergePage)
    }

    @Test
    fun unlabeledBlocksStayNullAndKeepThePositionalUuidTheLoaderWouldDerive() {
        val original = tree()
        val page = MergeConverters.toPage(original, path)
        val blocks = MergeConverters.toBlocks(original, page.uuid, path)
        assertNull(MergeConverters.toMergePage(page, blocks).blocks[1].uuid)
        val text = LogseqPageSerializer.serialize(page, blocks)
        val reparsed = MergeConverters.parseMarkdown(text, path, "Notes", false)
        assertEquals(blocks.map { it.uuid }, reparsed.blocks.map { it.uuid })
    }

    @Test
    fun diskMarkdownParsesToTheSameTreeAndExplicitUuid() {
        val text = "- a\n\t- b\n\t  id:: 33333333-3333-3333-3333-333333333333\n"
        val merge = MergeConverters.parseMarkdown(text, path, "P", false).mergePage
        val a = merge.blocks.single()
        assertEquals("a", a.content)
        assertNull(a.uuid)
        val b = a.children.single()
        assertEquals("b", b.content)
        assertEquals(u3, b.uuid)
        assertTrue("id" !in b.properties)
    }

    @Test
    fun rendererEmitsIdForEveryInsertedBlockSoUuidSurvivesReparse() {
        val gid = GraphId("g")
        val incoming = MergePage("P", blocks = listOf(MergeBlock("S1", "x", children = listOf(MergeBlock("S2", "y")))))
        val inserted = (mergePage(null, incoming, MergePolicy(gid, "G")) as MergeOutcome.New).page
        val text = MergeRenderer.renderNewPage(inserted)
        val back = MergeConverters.parseMarkdown(text, path, "P", false).mergePage
        assertEquals(UuidRemap.uuidFor(gid, "S1"), back.blocks[0].uuid)
        assertEquals(UuidRemap.uuidFor(gid, "S2"), back.blocks[0].children[0].uuid)
        assertEquals("g:S1", back.blocks[0].properties[MergePropertyKeys.SRC_ID])
        assertEquals(inserted, back)
    }

    @Test
    fun serializerAloneDoesNotEmitIdFromBlockUuid() {
        // Documents Spike 0.1.3: why MergeRenderer must write id:: itself.
        val page = MergeConverters.toPage(MergePage("P"), path)
        val blocks = MergeConverters.toBlocks(MergePage("P", blocks = listOf(MergeBlock(null, "x"))), PageUuid(UuidGenerator.generateV7()), path)
        assertTrue("id::" !in LogseqPageSerializer.serialize(page, blocks))
    }

    @Test
    fun multilineContentAndEmptyBlocksRenderAndReparse() {
        val page = MergePage("P", blocks = listOf(MergeBlock(u1, "line one\nline two", mapOf("k" to "v")), MergeBlock(null, "")))
        val back = MergeConverters.parseMarkdown(MergeRenderer.renderNewPage(page), path, "P", false).mergePage
        assertEquals(page, back)
    }
}

class MergeConvertersPagePropertiesTest {
    @Test
    fun newPagePropertiesRenderAsAPropertyBlockThePageModelReadsBack() {
        val page = MergePage("P", properties = mapOf("alias" to "a", "type" to "t"), blocks = listOf(MergeBlock(null, "x")))
        val back = MergeConverters.parseMarkdown(MergeRenderer.renderNewPage(page), MergeFixtures.PATH, "P", false).mergePage
        assertEquals(page, back)
    }
}
