package dev.stapler.stelekit.parsing

import dev.stapler.stelekit.parsing.ast.BulletBlockNode
import dev.stapler.stelekit.parsing.ast.TextNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlockParserTest {
    @Test
    fun `test block hierarchy`() {
        // Note: Using strict 2-space indentation as per our Lexer/Parser logic
        val input = """
- Root 1
  - Child 1.1
    - Grandchild 1.1.1
  - Child 1.2
- Root 2
        """.trimIndent()

        val parser = BlockParser(input)
        val doc = parser.parse()
        
        assertEquals(2, doc.children.size, "Should have 2 root blocks")
        
        val root1 = doc.children[0] as BulletBlockNode
        // TextNode content includes spaces? Lexer might include them.
        // My simple parseInlineContent consumes tokens. TEXT token logic needs checking.
        // Lexer consumes spaces as WS? Or part of TEXT?
        // Lexer: ' ', '\t' -> WS token (if not start of line).
        // parseInlineContent: loops until NEWLINE.
        // It appends currentToken.text(source).
        
        val root1Text = (root1.content[0] as TextNode).content
        // "Root 1"
        assertEquals("Root 1", root1Text.trim()) 
        assertEquals(2, root1.children.size, "Root 1 should have 2 children")
        
        val child1 = root1.children[0] as BulletBlockNode
        val child1Text = (child1.content[0] as TextNode).content
        assertEquals("Child 1.1", child1Text.trim())
        assertEquals(1, child1.children.size, "Child 1.1 should have 1 child")
        
        val grandchild = child1.children[0] as BulletBlockNode
        val gcText = (grandchild.content[0] as TextNode).content
        assertEquals("Grandchild 1.1.1", gcText.trim())
        
        val root2 = doc.children[1] as BulletBlockNode
        val root2Text = (root2.content[0] as TextNode).content
        assertEquals("Root 2", root2Text.trim())
    }

    @Test
    fun `test block properties`() {
        val input = """
- Block 1
  id:: 123
  type:: task
  some:: value
- Block 2
  prop:: value
        """.trimIndent()

        val parser = BlockParser(input)
        val doc = parser.parse()
        
        assertEquals(2, doc.children.size)
        
        val block1 = doc.children[0] as BulletBlockNode
        assertEquals("Block 1", (block1.content[0] as TextNode).content.trim())
        assertEquals(3, block1.properties.size)
        assertEquals("123", block1.properties["id"]?.trim())
        assertEquals("task", block1.properties["type"]?.trim())
        assertEquals("value", block1.properties["some"]?.trim())
        
        val block2 = doc.children[1] as BulletBlockNode
        assertEquals("Block 2", (block2.content[0] as TextNode).content.trim())
        assertEquals(1, block2.properties.size)
        assertEquals("value", block2.properties["prop"]?.trim())
    }
}
