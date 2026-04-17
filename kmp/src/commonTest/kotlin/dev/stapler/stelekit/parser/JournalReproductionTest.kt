package dev.stapler.stelekit.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class JournalReproductionTest {

    private val parser = MarkdownParser()

    @Test
    fun `test 2026-01-21 hierarchy reproduction`() {
        val input = """
- Till Listening to [[The Knowledge: How to Rebuild Our World from Scratch]]
  - xTool MetalFab Laser Welder and CNC Cutter https://share.google.com/xyz
  - Perhaps we need something like [[Fiver]] for [[Machinists]] so that local (and slightly less local) [[Machine Shops]] can more easily connect with potential suppliers? The difficulty could be building up a [[Critical Mass]] of buyers and building trust.
  - [[Rediscovering Paper]]
    - You need to bathe the contents in an [[Alkaline]] like [[Potash]], [[Soda]] or [[Lime]]
    - [[iron Gall Ink]] is what has historically been used for writing
    - The movable type printing press
        """.trimIndent()

        // Debug: Print normalized content to see what Preprocessor does
        val normalized = MarkdownPreprocessor.normalize(input)
        println("Normalized Input:\n$normalized")

        val page = parser.parsePage(input)
        val blocks = page.blocks
        
        // We expect ONE root block ("Till Listening to...")
        // If the bug exists, we might see multiple root blocks.
        
        println("Parsed Root Blocks: ${blocks.size}")
        blocks.forEach { b -> println(" - ${b.content} (Children: ${b.children.size})") }

        assertEquals(1, blocks.size, "Should have exactly 1 root block")
        
        val root = blocks[0]
        assertEquals("Till Listening to [[The Knowledge: How to Rebuild Our World from Scratch]]", root.content)
        
        // Root should have 3 children
        assertEquals(3, root.children.size, "Root should have 3 children")
        
        val child1 = root.children[0]
        assertTrue(child1.content.startsWith("xTool MetalFab"))
        
        val child2 = root.children[1]
        assertTrue(child2.content.startsWith("Perhaps we need"))
        
        val child3 = root.children[2]
        assertEquals("[[Rediscovering Paper]]", child3.content)
        
        // Child 3 should have 3 children
        assertEquals(3, child3.children.size, "Rediscovering Paper should have 3 children")
    }
    
    private fun assertTrue(condition: Boolean, message: String? = null) {
        if (!condition) throw AssertionError(message ?: "Condition failed")
    }
}
