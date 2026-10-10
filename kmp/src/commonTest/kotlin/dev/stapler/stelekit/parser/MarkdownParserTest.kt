package dev.stapler.stelekit.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import dev.stapler.stelekit.model.BlockType
import kotlin.test.assertTrue

class MarkdownParserTest {

    @Test
    fun testParseReferences() {
        // Use a simple paragraph to avoid list parsing issues in test environment
        val content = "Hello [[World]] and ((123-456))"

        val parser = MarkdownParser()
        val page = parser.parsePage(content)

        // We expect at least one block (the paragraph)
        assertTrue(page.blocks.isNotEmpty(), "Should have parsed at least one block")
        
        val block = page.blocks[0]
        // Verify reference extraction
        assertTrue(block.references.contains("World"), "Should contain WikiLink 'World'")
        assertTrue(block.references.contains("123-456"), "Should contain BlockRef '123-456'")
    }

    @Test
    fun wholeBlockQueryMacroIsClassifiedAsQuery() {
        val block = MarkdownParser().parsePage("- {{query (task now)}}").blocks.single()
        assertEquals(BlockType.Query("(task now)"), block.blockType)
    }

    @Test
    fun queryMacroMixedWithOtherTextRemainsBullet() {
        val block = MarkdownParser().parsePage("- some text {{query (task now)}} more text").blocks.single()
        assertEquals(BlockType.Bullet, block.blockType)
    }

    @Test
    fun queryMacroAsSoleParagraphContentIsClassifiedAsQuery() {
        val block = MarkdownParser().parsePage("{{query [[ProjectX]]}}").blocks.single()
        assertEquals(BlockType.Query("[[ProjectX]]"), block.blockType)
    }

    @Test
    fun embedMacroIsNotClassifiedAsQuery() {
        val block = MarkdownParser().parsePage("- {{embed [[Page]]}}").blocks.single()
        assertEquals(BlockType.Bullet, block.blockType)
    }

    @Test
    fun queryMacroWithPageRefContributesReferenceExactlyOnce() {
        val block = MarkdownParser().parsePage("- {{query [[ProjectX]]}}").blocks.single()
        assertEquals(1, block.references.count { it == "ProjectX" })
    }
}
