package dev.stapler.stelekit.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class ReproduceIssueTest {

    private val parser = MarkdownParser()

    @Test
    fun `reproduce missing children issue`() {
        val input = """
- Considering a [[Blog Post Idea]] about talking through the intersection...
- Reading about [[Blog Post Frameworks]]
- Listening to [[The Knowledge: How to Rebuild Our World from Scratch]]
  - [[Rediscovering Paper]]
    - You need to bathe the contents in an [[Alkaline]] like [[Potash]]
    - [[iron Gall Ink]] is what has historically been used
    - The movable type printing press
- Looking to [[Reverse Engineer]]...
        """.trimIndent()

        val page = parser.parsePage(input)
        val blocks = page.blocks
        
        // Root blocks check
        assertEquals(4, blocks.size, "Should have 4 root blocks")
        
        val block1 = blocks[0]
        assertEquals("Considering a [[Blog Post Idea]] about talking through the intersection...", block1.content)
        
        val block2 = blocks[1]
        assertEquals("Reading about [[Blog Post Frameworks]]", block2.content)
        
        val block3 = blocks[2]
        assertEquals("Listening to [[The Knowledge: How to Rebuild Our World from Scratch]]", block3.content)
        
        // Check children of Block 3
        assertEquals(1, block3.children.size, "Block 3 should have 1 child (Rediscovering Paper)")
        
        val child1 = block3.children[0]
        assertEquals("[[Rediscovering Paper]]", child1.content)
        
        // Check children of Child 1
        assertEquals(3, child1.children.size, "Child 1 should have 3 children")
        assertEquals("You need to bathe the contents in an [[Alkaline]] like [[Potash]]", child1.children[0].content)
        assertEquals("[[iron Gall Ink]] is what has historically been used", child1.children[1].content)
        assertEquals("The movable type printing press", child1.children[2].content)
    }
}
