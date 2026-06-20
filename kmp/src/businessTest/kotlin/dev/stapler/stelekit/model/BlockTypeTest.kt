package dev.stapler.stelekit.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Instant

class BlockTypeTest {

    private val now: Instant get() = Clock.System.now()

    @Test
    fun fromStringImageAnnotationReturnsImageAnnotation() {
        assertIs<BlockType.ImageAnnotation>(blockTypeFromString("image_annotation"))
    }

    @Test
    fun fromStringUnknownPluginTypeReturnsUnknown() {
        val result = blockTypeFromString("unknown_plugin_type")
        assertIs<BlockType.Unknown>(result)
        assertEquals("unknown_plugin_type", result.raw)
    }

    @Test
    fun unknownRoundTripPreservesRaw() {
        val raw = "custom_block_xyz"
        val blockType = blockTypeFromString(raw)
        assertIs<BlockType.Unknown>(blockType)
        assertEquals(raw, blockType.toDiscriminatorString())
    }

    @Test
    fun blockWithUnknownTypeDoesNotThrow() {
        // Previously the Block.init would throw "Invalid blockType: foo".
        // With BlockType.Unknown this must succeed.
        val block = Block(
            uuid = BlockUuid("test-uuid-1"),
            pageUuid = PageUuid("page-uuid-1"),
            content = "some content",
            level = 0,
            position = "a0",
            createdAt = now,
            updatedAt = now,
            blockType = BlockType.Unknown("foo"),
        )
        assertIs<BlockType.Unknown>(block.blockType)
        assertEquals("foo", (block.blockType as BlockType.Unknown).raw)
    }

    @Test
    fun fromStringBulletReturnsBullet() {
        assertIs<BlockType.Bullet>(blockTypeFromString("bullet"))
    }

    @Test
    fun fromStringParagraphReturnsParagraph() {
        assertIs<BlockType.Paragraph>(blockTypeFromString("paragraph"))
    }

    @Test
    fun fromStringHeadingReturnsHeading() {
        assertIs<BlockType.Heading>(blockTypeFromString("heading"))
    }

    @Test
    fun fromStringCodeFenceReturnsCodeFence() {
        assertIs<BlockType.CodeFence>(blockTypeFromString("code_fence"))
    }

    @Test
    fun fromStringBlockquoteReturnsBlockquote() {
        assertIs<BlockType.Blockquote>(blockTypeFromString("blockquote"))
    }

    @Test
    fun fromStringOrderedListItemReturnsOrderedListItem() {
        assertIs<BlockType.OrderedListItem>(blockTypeFromString("ordered_list_item"))
    }

    @Test
    fun fromStringThematicBreakReturnsThematicBreak() {
        assertIs<BlockType.ThematicBreak>(blockTypeFromString("thematic_break"))
    }

    @Test
    fun fromStringTableReturnsTable() {
        assertIs<BlockType.Table>(blockTypeFromString("table"))
    }

    @Test
    fun fromStringRawHtmlReturnsRawHtml() {
        assertIs<BlockType.RawHtml>(blockTypeFromString("raw_html"))
    }

    @Test
    fun imageAnnotationRoundTrip() {
        assertEquals("image_annotation", BlockType.ImageAnnotation.toDiscriminatorString())
        assertIs<BlockType.ImageAnnotation>(blockTypeFromString(BlockType.ImageAnnotation.toDiscriminatorString()))
    }
}
