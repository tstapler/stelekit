package dev.stapler.stelekit.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class MultiChildTest {

    private val parser = MarkdownParser()

    @Test
    fun `test multiple children at same level`() {
        val input = """
- Root
  - Child 1
  - Child 2
  - Child 3
        """.trimIndent()

        val page = parser.parsePage(input)
        val blocks = page.blocks
        
        assertEquals(1, blocks.size, "Should have 1 root block")
        val root = blocks[0]
        assertEquals("Root", root.content)
        
        assertEquals(3, root.children.size, "Root should have 3 children")
        assertEquals("Child 1", root.children[0].content)
        assertEquals("Child 2", root.children[1].content)
        assertEquals("Child 3", root.children[2].content)
    }
    
    @Test
    fun `test mixed nesting`() {
        val input = """
- Root
  - Child 1
    - Grandchild 1
  - Child 2
        """.trimIndent()

        val page = parser.parsePage(input)
        val blocks = page.blocks
        
        assertEquals(1, blocks.size, "Should have 1 root block")
        val root = blocks[0]
        
        assertEquals(2, root.children.size, "Root should have 2 children")
        val child1 = root.children[0]
        assertEquals("Child 1", child1.content)
        assertEquals(1, child1.children.size, "Child 1 should have 1 child")
        
        val child2 = root.children[1]
        assertEquals("Child 2", child2.content)
    }
}
