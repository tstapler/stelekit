package dev.stapler.stelekit.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealWorldMarkdownTest {

    private val parser = MarkdownParser()

    @Test
    fun `test nested list parsing with 2 spaces`() {
        // Simulating the content from the screenshot
        val input = """
- Root Item
  - Nested Item 1
    - Double Nested Item
  - Nested Item 2
- Root Item 2
        """.trimIndent()

        val page = parser.parsePage(input)
        val blocks = page.blocks
        
        // Should have 2 root blocks
        assertEquals(2, blocks.size, "Should have 2 root blocks")
        
        val root1 = blocks[0]
        assertEquals("Root Item", root1.content)
        assertEquals(2, root1.children.size, "Root 1 should have 2 children")
        
        val nested1 = root1.children[0]
        assertEquals("Nested Item 1", nested1.content)
        assertEquals(1, nested1.children.size, "Nested 1 should have 1 child")
        
        val doubleNested = nested1.children[0]
        assertEquals("Double Nested Item", doubleNested.content)
        
        val nested2 = root1.children[1]
        assertEquals("Nested Item 2", nested2.content)
        
        val root2 = blocks[1]
        assertEquals("Root Item 2", root2.content)
    }

    @Test
    fun `test preprocessor normalization`() {
        val input = """
- Level 0
  - Level 1
    - Level 2
        """.trimIndent()
        
        val normalized = MarkdownPreprocessor.normalize(input)
        val lines = normalized.lines()
        
        assertTrue(lines[0].startsWith("- Level 0"))
        assertTrue(lines[1].startsWith("    - Level 1"), "Level 1 should have 4 spaces, got: '${lines[1]}'")
        assertTrue(lines[2].startsWith("        - Level 2"), "Level 2 should have 8 spaces, got: '${lines[2]}'")
    }
}
