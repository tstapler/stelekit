package dev.stapler.stelekit.parsing

import dev.stapler.stelekit.model.BlockType
import dev.stapler.stelekit.model.ParsedBlock
import dev.stapler.stelekit.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Characterizes what `ParsedBlock.content` holds for pipe tables as `MarkdownParser` produces it today.
 * Facts recorded here are the verbatim prefix/suffix contract Task 1.1a's TableParser must honour.
 */
class TableBlockContentShapeTest {
    private val table = "| a | b |\n|---|---|\n| 1 | 2 |"

    private fun blocks(md: String): List<ParsedBlock> = MarkdownParser().parsePage(md).blocks

    private fun only(md: String): ParsedBlock = blocks(md).single()

    @Test
    fun bareTable_isBlockTypeTable_withNoBulletPrefix() {
        val b = only("$table\n")
        assertIs<BlockType.Table>(b.blockType)
        assertEquals(table, b.content)
        assertTrue(b.properties.isEmpty())
    }

    @Test
    fun bulletWithUnindentedRows_isBlockTypeTable_andBulletPrefixIsStripped() {
        val b = only("- $table\n")
        assertIs<BlockType.Table>(b.blockType)
        assertEquals(table, b.content, "the '- ' prefix is not part of content")
    }

    @Test
    fun logseqStyleBulletWithIndentedRows_isBulletNotTable_contentIsDeindented() {
        val b = only("- | a | b |\n  |---|---|\n  | 1 | 2 |\n")
        assertIs<BlockType.Bullet>(b.blockType)
        assertEquals(table, b.content, "continuation indent is stripped; type is Bullet, so BlockItem never renders TableBlock")
    }

    @Test
    fun idProperty_afterIndentedBulletTable_isLiftedOutOfContent() {
        val b = only("- | a | b |\n  |---|---|\n  | 1 | 2 |\n  id:: 6f2c\n")
        assertEquals(table, b.content)
        assertEquals("6f2c", b.properties["id"])
    }

    @Test
    fun idProperty_afterBareTable_isLiftedOutOfContent() {
        val b = only("$table\n  id:: 6f2c\n")
        assertIs<BlockType.Table>(b.blockType)
        assertEquals(table, b.content)
        assertEquals("6f2c", b.properties["id"])
    }

    @Test
    fun unindentedIdLine_afterBulletTable_becomesASeparateParagraphBlock() {
        val bs = blocks("- $table\nid:: 6f2c\n")
        assertEquals(2, bs.size)
        assertIs<BlockType.Table>(bs[0].blockType)
        assertEquals(table, bs[0].content)
        assertTrue(bs[0].properties.isEmpty())
        assertEquals("id:: 6f2c", bs[1].content)
    }

    @Test
    fun idProperty_beforeTheRows_staysInsideBulletContent() {
        val b = only("- id:: 6f2c\n  | a | b |\n  |---|---|\n  | 1 | 2 |\n")
        assertIs<BlockType.Bullet>(b.blockType)
        assertEquals("id:: 6f2c\n$table", b.content)
    }

    @Test
    fun trailingTextAfterLastRow_issue724() {
        val bullet = only("- | a | b |\n  |---|---|\n  | 1 | 2 |\n  some trailing text\n")
        assertIs<BlockType.Bullet>(bullet.blockType)
        assertEquals("$table\nsome trailing text", bullet.content, "bullet form keeps the trailing line in content")

        val bare = blocks("$table\nsome trailing text\n")
        assertEquals(2, bare.size)
        assertIs<BlockType.Table>(bare[0].blockType)
        assertEquals(table, bare[0].content, "bare form splits trailing text into a following Paragraph")
        assertEquals("some trailing text", bare[1].content)

        val indented = blocks("$table\n  trailing\n")
        assertIs<BlockType.Table>(indented[0].blockType)
        assertEquals(table, indented[0].content)
        assertEquals("trailing", indented[0].children.single().content, "indented trailing text becomes a child Paragraph")
    }

    @Test
    fun crlf_bulletForm_keepsCarriageReturnsInContentAndProperties() {
        val b = only("- | a | b |\r\n  |---|---|\r\n  | 1 | 2 |\r\n  id:: 6f2c\r\n")
        assertIs<BlockType.Bullet>(b.blockType)
        assertEquals("| a | b |\r\n|---|---|\r\n| 1 | 2 |\r", b.content, "CR survives on every line but the last, and the last row keeps a trailing CR")
        assertEquals("6f2c\r", b.properties["id"], "the id value keeps its CR")
    }

    @Test
    fun crlf_tableForm_isNormalizedToLf() {
        assertEquals(table, only("| a | b |\r\n|---|---|\r\n| 1 | 2 |\r\n").content)
        assertEquals(table, only("- | a | b |\r\n|---|---|\r\n| 1 | 2 |\r\n").content)
    }

    @Test
    fun blankLineInsideTheRows_splitsTheTable() {
        val bare = blocks("| a | b |\n|---|---|\n\n| 1 | 2 |\n")
        assertEquals(3, bare.size)
        assertIs<BlockType.Table>(bare[0].blockType)
        assertEquals("| a | b |\n|---|---|", bare[0].content, "header + delimiter only; the rows land in a later Paragraph")
        assertEquals("| 1 | 2 |", bare[2].content)

        val bullet = blocks("- | a | b |\n  |---|---|\n\n  | 1 | 2 |\n")
        assertEquals(2, bullet.size)
        assertEquals("| a | b |\n|---|---|", bullet[0].content)
        assertEquals("| 1 | 2 |", bullet[1].content)
    }

    @Test
    fun cellPaddingAndDelimiterStyle_arePreservedVerbatim_butEachLineIsTrimmed() {
        val b = only("- |  a  |  b  |\n|:--:|---|\n|  1  |  2  |\n")
        assertIs<BlockType.Table>(b.blockType)
        assertEquals("|  a  |  b  |\n|:--:|---|\n|  1  |  2  |", b.content)
    }

    @Test
    fun bulletForm_withExtraSpacesAfterDash_keepsLeadingSpacesOnFirstLineOnly() {
        val b = only("-   |  a  |  b  |\n    |:--:|---|\n    |  1  |  2  |\n")
        assertIs<BlockType.Bullet>(b.blockType)
        assertEquals("  |  a  |  b  |\n|:--:|---|\n|  1  |  2  |", b.content)
    }

    @Test
    fun nestedBulletTable_withRowsAtParentIndent_isNotATable() {
        val parent = only("- parent\n  - | a | b |\n  |---|---|\n  | 1 | 2 |\n")
        assertEquals("| a | b |", parent.children.first().content)
        assertEquals(3, parent.children.size, "rows split into separate Paragraph children")
    }

    @Test
    fun tableAfterTextLine_staysInsideBulletContent() {
        val b = only("- intro\n  | a | b |\n  |---|---|\n  | 1 | 2 |\n")
        assertIs<BlockType.Bullet>(b.blockType)
        assertEquals("intro\n$table", b.content)
    }
}
