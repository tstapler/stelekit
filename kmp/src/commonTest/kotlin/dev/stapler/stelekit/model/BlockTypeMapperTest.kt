package dev.stapler.stelekit.model

import kotlin.test.Test
import kotlin.test.assertEquals

class BlockTypeMapperTest {

    @Test
    fun bulletMapsToCorrectDiscriminator() {
        assertEquals(BlockTypes.BULLET, BlockType.Bullet.toDiscriminatorString())
    }

    @Test
    fun paragraphMapsToCorrectDiscriminator() {
        assertEquals(BlockTypes.PARAGRAPH, BlockType.Paragraph.toDiscriminatorString())
    }

    @Test
    fun headingMapsToCorrectDiscriminator() {
        assertEquals(BlockTypes.HEADING, BlockType.Heading(level = 2).toDiscriminatorString())
    }

    @Test
    fun codeFenceMapsToCorrectDiscriminator() {
        assertEquals(BlockTypes.CODE_FENCE, BlockType.CodeFence(language = "kotlin").toDiscriminatorString())
    }

    @Test
    fun blockquoteMapsToCorrectDiscriminator() {
        assertEquals(BlockTypes.BLOCKQUOTE, BlockType.Blockquote.toDiscriminatorString())
    }

    @Test
    fun orderedListItemMapsToCorrectDiscriminator() {
        assertEquals(BlockTypes.ORDERED_LIST_ITEM, BlockType.OrderedListItem(number = 1).toDiscriminatorString())
    }

    @Test
    fun thematicBreakMapsToCorrectDiscriminator() {
        assertEquals(BlockTypes.THEMATIC_BREAK, BlockType.ThematicBreak.toDiscriminatorString())
    }

    @Test
    fun tableMapsToCorrectDiscriminator() {
        assertEquals(BlockTypes.TABLE, BlockType.Table.toDiscriminatorString())
    }

    @Test
    fun rawHtmlMapsToCorrectDiscriminator() {
        assertEquals(BlockTypes.RAW_HTML, BlockType.RawHtml.toDiscriminatorString())
    }

    @Test
    fun allBlockTypesConstantsMatchDiscriminatorStrings() {
        val allTypes = listOf(
            BlockType.Bullet,
            BlockType.Paragraph,
            BlockType.Heading(1),
            BlockType.CodeFence(""),
            BlockType.Blockquote,
            BlockType.OrderedListItem(1),
            BlockType.ThematicBreak,
            BlockType.Table,
            BlockType.RawHtml,
        )
        val allConstants = setOf(
            BlockTypes.BULLET, BlockTypes.PARAGRAPH, BlockTypes.HEADING,
            BlockTypes.CODE_FENCE, BlockTypes.BLOCKQUOTE, BlockTypes.ORDERED_LIST_ITEM,
            BlockTypes.THEMATIC_BREAK, BlockTypes.TABLE, BlockTypes.RAW_HTML,
        )
        val mappedDiscriminators = allTypes.map { it.toDiscriminatorString() }.toSet()
        assertEquals(allConstants, mappedDiscriminators, "BlockTypes constants must match all discriminator strings")
    }
}
