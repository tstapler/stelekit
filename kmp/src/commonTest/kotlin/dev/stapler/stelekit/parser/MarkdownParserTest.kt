package dev.stapler.stelekit.parser

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
