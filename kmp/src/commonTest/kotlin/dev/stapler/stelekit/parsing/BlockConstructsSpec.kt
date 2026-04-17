package dev.stapler.stelekit.parsing

import dev.stapler.stelekit.parsing.ast.*
import kotlin.test.*

/**
 * Spec-driven tests for all block-level constructs in the SteleKit parser.
 *
 * Implemented constructs are fully tested. Unimplemented constructs are marked
 * @Ignore with detailed comments describing the expected behavior and deferral reason.
 *
 * Priority legend:
 *   P1 = blocking / next milestone
 *   P2 = planned but not yet scheduled
 */
class BlockConstructsSpec {

    private fun parse(input: String) = BlockParser(input).parse()
    private fun parseFull(input: String) = OutlinerParser().parse(input)

    // -------------------------------------------------------------------------
    // BULLET LISTS — IMPLEMENTED
    // -------------------------------------------------------------------------

    @Test
    fun `bullet list - dash bullet creates BulletBlockNode at level 0`() {
        val doc = parse("- hello world")
        assertEquals(1, doc.children.size)
        val block = doc.children[0]
        assertIs<BulletBlockNode>(block)
        assertEquals(0, block.level)
        assertEquals("hello world", (block.content[0] as TextNode).content.trim())
    }

    @Test
    fun `bullet list - dash without trailing space is NOT a bullet (lexer requires dash-space)`() {
        // The Lexer only emits BULLET when '-' is immediately followed by a space character.
        // A bare '-' at EOF (no trailing space) is lexed as a TEXT token, so the parser
        // produces a ParagraphBlockNode containing the literal text "-".
        // This documents the current behaviour — "- " (with space) is the required form.
        val doc = parse("-")
        assertEquals(1, doc.children.size)
        assertIs<ParagraphBlockNode>(doc.children[0], "Bare '-' without trailing space is a paragraph, not a bullet")
    }

    @Test
    fun `bullet list - star bullet is NOT recognized as bullet (known bug)`() {
        // KNOWN BUG: The lexer only emits BULLET tokens for '-' bullets.
        // Star '*' is not tokenised as BULLET, so '* text' is parsed as a
        // ParagraphBlockNode containing literal "* text", not a BulletBlockNode.
        // This test is intentionally left without @Ignore so the failure is visible.
        // Fix: extend the Lexer to recognise '*' at the start of a line as BULLET.
        val doc = parse("* star item")
        assertEquals(1, doc.children.size)
        val block = doc.children[0]
        assertIs<BulletBlockNode>(block, "Star bullet should be recognised as BulletBlockNode (currently a bug)")
    }

    @Test
    fun `bullet list - plus bullet is NOT recognized as bullet (known bug)`() {
        // KNOWN BUG: The lexer only emits BULLET tokens for '-' bullets.
        // Plus '+' is not tokenised as BULLET, so '+ text' produces a ParagraphBlockNode.
        // Fix: extend the Lexer to recognise '+' at the start of a line as BULLET.
        val doc = parse("+ plus item")
        assertEquals(1, doc.children.size)
        val block = doc.children[0]
        assertIs<BulletBlockNode>(block, "Plus bullet should be recognised as BulletBlockNode (currently a bug)")
    }

    @Test
    fun `bullet list - two root bullets produce two top-level BulletBlockNodes`() {
        val doc = parse("- first\n- second")
        assertEquals(2, doc.children.size)
        assertIs<BulletBlockNode>(doc.children[0])
        assertIs<BulletBlockNode>(doc.children[1])
        assertEquals("first", (doc.children[0].content[0] as TextNode).content.trim())
        assertEquals("second", (doc.children[1].content[0] as TextNode).content.trim())
    }

    @Test
    fun `bullet list - two space indent creates one child under parent`() {
        val doc = parse("- parent\n  - child")
        assertEquals(1, doc.children.size)
        val parent = doc.children[0] as BulletBlockNode
        assertEquals(0, parent.level)
        assertEquals(1, parent.children.size)
        val child = parent.children[0] as BulletBlockNode
        assertEquals("child", (child.content[0] as TextNode).content.trim())
    }

    @Test
    fun `bullet list - four space indent creates grandchild (three levels deep)`() {
        val doc = parse("- root\n  - child\n    - grandchild")
        assertEquals(1, doc.children.size)
        val root = doc.children[0] as BulletBlockNode
        assertEquals(1, root.children.size)
        val child = root.children[0] as BulletBlockNode
        assertEquals(1, child.children.size)
        val grandchild = child.children[0] as BulletBlockNode
        assertEquals("grandchild", (grandchild.content[0] as TextNode).content.trim())
    }

    @Test
    fun `bullet list - six space indent creates great-grandchild (four levels deep)`() {
        val doc = parse("- a\n  - b\n    - c\n      - d")
        val a = doc.children[0] as BulletBlockNode
        val b = a.children[0] as BulletBlockNode
        val c = b.children[0] as BulletBlockNode
        val d = c.children[0] as BulletBlockNode
        assertEquals("d", (d.content[0] as TextNode).content.trim())
    }

    @Test
    fun `bullet list - multiple children at same level under one parent`() {
        val doc = parse("- parent\n  - child1\n  - child2\n  - child3")
        val parent = doc.children[0] as BulletBlockNode
        assertEquals(3, parent.children.size)
        assertEquals("child1", (parent.children[0].content[0] as TextNode).content.trim())
        assertEquals("child2", (parent.children[1].content[0] as TextNode).content.trim())
        assertEquals("child3", (parent.children[2].content[0] as TextNode).content.trim())
    }

    @Test
    fun `bullet list - sibling bullets after nested block are not nested`() {
        val doc = parse("- root1\n  - child\n- root2")
        assertEquals(2, doc.children.size, "root2 must be a sibling of root1, not nested")
        val root1 = doc.children[0] as BulletBlockNode
        assertEquals(1, root1.children.size)
        val root2 = doc.children[1] as BulletBlockNode
        assertEquals("root2", (root2.content[0] as TextNode).content.trim())
    }

    @Test
    fun `bullet list - plain text paragraph without bullet creates ParagraphBlockNode`() {
        val doc = parse("just some text")
        assertEquals(1, doc.children.size)
        assertIs<ParagraphBlockNode>(doc.children[0])
        assertEquals("just some text", (doc.children[0].content[0] as TextNode).content.trim())
    }

    @Test
    fun `bullet list - mixed paragraph and bullet at root level`() {
        val doc = parse("paragraph text\n- bullet item")
        assertEquals(2, doc.children.size)
        assertIs<ParagraphBlockNode>(doc.children[0])
        assertIs<BulletBlockNode>(doc.children[1])
    }

    // -------------------------------------------------------------------------
    // INDENTATION LEVEL CALCULATION — CURRENTLY WORKING
    // -------------------------------------------------------------------------

    @Test
    fun `indentation - two spaces indent is level 1 child`() {
        val doc = parse("- root\n  - child")
        val root = doc.children[0] as BulletBlockNode
        assertEquals(1, root.children.size)
    }

    @Test
    fun `indentation - four spaces indent is level 2 grandchild`() {
        val doc = parse("- root\n  - child\n    - grandchild")
        val root = doc.children[0] as BulletBlockNode
        val child = root.children[0] as BulletBlockNode
        assertEquals(1, child.children.size)
    }

    @Test
    fun `indentation - tab character counts as one level`() {
        // calculateLevel: tabs contribute 1 level each.
        // A single tab at line start should place the block at level 1.
        val doc = parse("- root\n\t- tabchild")
        val root = doc.children[0] as BulletBlockNode
        assertEquals(1, root.children.size, "Tab-indented child should be nested under root")
    }

    @Test
    fun `indentation - three space indent is level 1 not level 2 (known calculateLevel bug)`() {
        // KNOWN BUG: calculateLevel uses (spaces + 1) / 2 (integer division).
        // For 3 spaces: (3 + 1) / 2 = 2, which incorrectly assigns level 2.
        // The correct result should be level 1 (round-down semantics).
        // This test is left without @Ignore so the failure is permanently visible.
        // Fix: change formula to spaces / 2 (floor division) without the +1 bias.
        val doc = parse("- root\n   - child with 3 spaces")
        val root = doc.children[0] as BulletBlockNode
        assertEquals(1, root.children.size, "3-space indent should produce a level-1 child, not level 2")
    }

    @Test
    fun `indentation - six spaces is level 3`() {
        // calculateLevel("      ") = (6 + 1) / 2 = 3
        val doc = parse("- root\n  - l1\n    - l2\n      - l3")
        val root = doc.children[0] as BulletBlockNode
        val l1 = root.children[0] as BulletBlockNode
        val l2 = l1.children[0] as BulletBlockNode
        assertEquals(1, l2.children.size, "Six-space indent should produce a level-3 block")
    }

    // -------------------------------------------------------------------------
    // BLOCK PROPERTIES — IMPLEMENTED
    // -------------------------------------------------------------------------

    @Test
    fun `block properties - single key-value property is parsed into properties map`() {
        val doc = parse("- block\n  key:: value")
        val block = doc.children[0] as BulletBlockNode
        assertEquals(1, block.properties.size)
        assertEquals("value", block.properties["key"]?.trim())
    }

    @Test
    fun `block properties - multiple properties in one block all appear in map`() {
        val doc = parse("- block\n  id:: abc\n  type:: task\n  priority:: high")
        val block = doc.children[0] as BulletBlockNode
        assertEquals(3, block.properties.size)
        assertEquals("abc", block.properties["id"]?.trim())
        assertEquals("task", block.properties["type"]?.trim())
        assertEquals("high", block.properties["priority"]?.trim())
    }

    @Test
    fun `block properties - property value with spaces is preserved`() {
        val doc = parse("- block\n  title:: Hello World")
        val block = doc.children[0] as BulletBlockNode
        assertEquals("Hello World", block.properties["title"]?.trim())
    }

    @Test
    fun `block properties - properties do not appear in block content`() {
        val doc = parse("- my block\n  meta:: data")
        val block = doc.children[0] as BulletBlockNode
        val rawContent = (block.content[0] as TextNode).content
        assertFalse(rawContent.contains("meta::"), "Property line must not appear in block content")
        assertFalse(rawContent.contains("data"), "Property value must not appear in block content")
    }

    @Test
    fun `block properties - block with no properties has empty properties map`() {
        val doc = parse("- plain block")
        val block = doc.children[0] as BulletBlockNode
        assertTrue(block.properties.isEmpty())
    }

    @Test
    fun `block properties - properties on a child block are scoped to that child`() {
        val doc = parse("- parent\n  - child\n    tag:: childprop")
        val parent = doc.children[0] as BulletBlockNode
        assertTrue(parent.properties.isEmpty(), "Parent should have no properties")
        val child = parent.children[0] as BulletBlockNode
        assertEquals("childprop", child.properties["tag"]?.trim())
    }

    @Test
    fun `block properties - properties coexist with children`() {
        val doc = parse("- block\n  key:: val\n  - child")
        val block = doc.children[0] as BulletBlockNode
        assertEquals("val", block.properties["key"]?.trim())
        assertEquals(1, block.children.size)
    }

    // -------------------------------------------------------------------------
    // INLINE CONTENT IN BULLETS — via LogseqParser (full parse)
    // -------------------------------------------------------------------------

    @Test
    fun `inline content - bold text in bullet is parsed to BoldNode`() {
        val doc = parseFull("- **bold text**")
        val block = doc.children[0] as BulletBlockNode
        assertTrue(block.content.any { it is BoldNode }, "Expected BoldNode in content")
    }

    @Test
    fun `inline content - wiki link in bullet is parsed to WikiLinkNode`() {
        val doc = parseFull("- see [[PageName]]")
        val block = doc.children[0] as BulletBlockNode
        val wikiLinks = block.content.filterIsInstance<WikiLinkNode>()
        assertEquals(1, wikiLinks.size)
        assertEquals("PageName", wikiLinks[0].target)
    }

    @Test
    fun `inline content - mixed bold and wiki-link in same bullet`() {
        val doc = parseFull("- **bold** and [[Link]]")
        val block = doc.children[0] as BulletBlockNode
        assertTrue(block.content.any { it is BoldNode }, "Expected BoldNode")
        assertTrue(block.content.any { it is WikiLinkNode }, "Expected WikiLinkNode")
    }

    @Test
    fun `inline content - tag in bullet is parsed to TagNode`() {
        val doc = parseFull("- #mytag")
        val block = doc.children[0] as BulletBlockNode
        val tags = block.content.filterIsInstance<TagNode>()
        assertEquals(1, tags.size)
        assertEquals("mytag", tags[0].tag)
    }

    @Test
    fun `inline content - inline code in bullet is parsed to CodeNode`() {
        val doc = parseFull("- use `val x = 1` here")
        val block = doc.children[0] as BulletBlockNode
        assertTrue(block.content.any { it is CodeNode }, "Expected CodeNode for backtick span")
    }

    // -------------------------------------------------------------------------
    // INDENTED CODE BLOCKS — DEFERRED (P2)
    // -------------------------------------------------------------------------

    @Ignore("P2: Indented code blocks conflict with outliner indentation. In mldoc outline mode, Example nodes are stripped. SteleKit should also suppress indented code blocks in outline mode.")
    @Test
    fun `indented code block - four space indented text creates CodeFenceBlockNode in flat mode`() {
        // In flat-document (non-outline) mode a block of text indented by 4+ spaces is a
        // fenced code block with language=null per the CommonMark spec.
        // In Logseq's outline mode the same indentation signals nesting, so the outliner
        // must intercept these before reaching code-block parsing.
        //
        // When implemented, the expected AST for "    val x = 1" in flat mode is:
        //   CodeFenceBlockNode(language = null, rawContent = "val x = 1")
        //
        // Deferral reason: Requires (a) a distinct flat-document parse mode, (b) the
        // CodeFenceBlockNode AST node, and (c) agreement with mldoc reference behaviour.
        val doc = parse("    val x = 1")
        // Placeholder — parser currently treats this as a deeply nested empty block or paragraph.
        // No assertion: this test is @Ignore.
    }

    // -------------------------------------------------------------------------
    // ATX HEADINGS — DEFERRED (P1: HeadingBlockNode not yet in AST)
    // -------------------------------------------------------------------------

    @Test
    fun `headings - h1 hash heading creates HeadingBlockNode level 1`() {
        val doc = parse("# Heading One")
        val heading = doc.children[0] as HeadingBlockNode
        assertEquals(1, heading.level)
        assertEquals("Heading One", (heading.content[0] as TextNode).content.trim())
    }

    @Test
    fun `headings - h2 creates HeadingBlockNode level 2`() {
        val doc = parse("## Section")
        val heading = doc.children[0] as HeadingBlockNode
        assertEquals(2, heading.level)
        assertEquals("Section", (heading.content[0] as TextNode).content.trim())
    }

    @Test
    fun `headings - h3 creates HeadingBlockNode level 3`() {
        val doc = parse("### Subsection")
        val heading = doc.children[0] as HeadingBlockNode
        assertEquals(3, heading.level)
    }

    @Test
    fun `headings - h4 creates HeadingBlockNode level 4`() {
        val doc = parse("#### Deep")
        val heading = doc.children[0] as HeadingBlockNode
        assertEquals(4, heading.level)
    }

    @Test
    fun `headings - h5 creates HeadingBlockNode level 5`() {
        val doc = parse("##### Deeper")
        val heading = doc.children[0] as HeadingBlockNode
        assertEquals(5, heading.level)
    }

    @Test
    fun `headings - h6 creates HeadingBlockNode level 6`() {
        val doc = parse("###### Deepest")
        val heading = doc.children[0] as HeadingBlockNode
        assertEquals(6, heading.level)
    }

    @Test
    fun `headings - hash without trailing space is NOT a heading`() {
        // CommonMark requires at least one space after the hash sequence.
        // Input: "#5 not a heading" — '#' followed by digit (no space) is NOT a heading.
        val doc = parse("#5 not a heading")
        // Should produce a ParagraphBlockNode (HASH token followed by TEXT, no WS after HASH)
        assertIs<ParagraphBlockNode>(doc.children[0], "Hash without trailing space must not be a heading")
    }

    @Test
    fun `headings - heading with bold inside contains BoldNode in content`() {
        val doc = parseFull("# Heading with **bold** inside")
        val heading = doc.children[0] as HeadingBlockNode
        assertTrue(heading.content.any { it is BoldNode }, "Expected BoldNode in heading content")
    }

    // -------------------------------------------------------------------------
    // FENCED CODE BLOCKS — DEFERRED (P1: CodeFenceBlockNode not yet in AST)
    // -------------------------------------------------------------------------

    @Test
    fun `fenced code block - with language identifier captures language and raw content`() {
        val doc = parse("```kotlin\nval x = 1\n```")
        val code = doc.children[0] as CodeFenceBlockNode
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.rawContent)
    }

    @Test
    fun `fenced code block - without language identifier has null language`() {
        val doc = parse("```\nsome code\n```")
        val code = doc.children[0] as CodeFenceBlockNode
        assertNull(code.language)
        assertEquals("some code", code.rawContent)
    }

    @Test
    fun `fenced code block - preserves internal newlines in rawContent`() {
        val doc = parse("```\nline1\nline2\nline3\n```")
        val code = doc.children[0] as CodeFenceBlockNode
        assertEquals("line1\nline2\nline3", code.rawContent)
    }

    @Test
    fun `fenced code block - unclosed fence degrades gracefully without exception`() {
        // Expected: no crash; parser should treat everything to EOF as rawContent.
        val doc = parse("```kotlin\nval x = 1")
        // Should not throw; must produce a CodeFenceBlockNode
        assertIs<CodeFenceBlockNode>(doc.children[0])
    }

    @Test
    fun `fenced code block - tilde fence is also recognized`() {
        val doc = parse("~~~\ncode here\n~~~")
        val code = doc.children[0] as CodeFenceBlockNode
        assertNull(code.language)
        assertEquals("code here", code.rawContent)
    }

    // -------------------------------------------------------------------------
    // BLOCKQUOTES — DEFERRED (P2: BlockquoteBlockNode not yet in AST)
    // -------------------------------------------------------------------------

    @Test
    fun `blockquote - single line produces BlockquoteBlockNode`() {
        val doc = parse("> a quote")
        val bq = doc.children[0] as BlockquoteBlockNode
        val inner = bq.children[0] as ParagraphBlockNode
        assertEquals("a quote", (inner.content[0] as TextNode).content.trim())
    }

    @Test
    fun `blockquote - multiple consecutive lines merge into one BlockquoteBlockNode`() {
        val doc = parse("> line one\n> line two\n> line three")
        assertEquals(1, doc.children.size, "Consecutive > lines should be one blockquote")
        assertIs<BlockquoteBlockNode>(doc.children[0])
    }

    @Test
    fun `blockquote - bold inside blockquote parses to BoldNode`() {
        val doc = parseFull("> **important** note")
        val bq = doc.children[0] as BlockquoteBlockNode
        assertTrue(bq.children.flatMap { it.content }.any { it is BoldNode })
    }

    // -------------------------------------------------------------------------
    // ORDERED LISTS — DEFERRED (P2: OrderedListItemBlockNode not yet in AST)
    // -------------------------------------------------------------------------

    @Test
    fun `ordered list - single item creates OrderedListItemBlockNode`() {
        val doc = parse("1. first item")
        val item = doc.children[0] as OrderedListItemBlockNode
        assertEquals(1, item.number)
        assertEquals("first item", (item.content[0] as TextNode).content.trim())
    }

    @Test
    fun `ordered list - two consecutive items produce two sibling nodes`() {
        val doc = parse("1. first\n2. second")
        assertEquals(2, doc.children.size)
        assertEquals(1, (doc.children[0] as OrderedListItemBlockNode).number)
        assertEquals(2, (doc.children[1] as OrderedListItemBlockNode).number)
    }

    @Test
    fun `ordered list - nested ordered list creates child OrderedListItemBlockNode`() {
        val doc = parse("1. parent\n   1. nested")
        val parent = doc.children[0] as OrderedListItemBlockNode
        assertEquals(1, parent.children.size)
    }

    @Test
    fun `ordered list - mixed ordered and bullet list at root level`() {
        val doc = parse("1. ordered\n- bullet")
        assertEquals(2, doc.children.size)
        assertIs<OrderedListItemBlockNode>(doc.children[0])
        assertIs<BulletBlockNode>(doc.children[1])
    }

    // -------------------------------------------------------------------------
    // THEMATIC BREAKS — DEFERRED (P2: ThematicBreakBlockNode not yet in AST)
    // -------------------------------------------------------------------------

    @Test
    fun `thematic break - three dashes produce ThematicBreakBlockNode`() {
        val doc = parse("---")
        assertIs<ThematicBreakBlockNode>(doc.children[0])
    }

    @Test
    fun `thematic break - three asterisks produce ThematicBreakBlockNode`() {
        val doc = parse("***")
        assertIs<ThematicBreakBlockNode>(doc.children[0])
    }

    @Ignore("P2: spaced dashes '- - -' ambiguous with bullet — deferred")
    @Test
    fun `thematic break - spaced dashes produce ThematicBreakBlockNode`() {
        // Input: "- - -"
        // CommonMark allows spaces between the repeated characters.
        // Expected: ThematicBreakBlockNode (not a bullet list item)
        val doc = parse("- - -")
        // assertIs<ThematicBreakBlockNode>(doc.children[0])
    }

    @Test
    fun `thematic break - does NOT consume surrounding content`() {
        val doc = parse("before\n---\nafter")
        assertEquals(3, doc.children.size)
        assertIs<ThematicBreakBlockNode>(doc.children[1])
    }

    // -------------------------------------------------------------------------
    // GFM TABLES — DEFERRED (P2: TableBlockNode not yet in AST)
    // -------------------------------------------------------------------------

    @Test
    fun `table - basic pipe table with header row and one data row`() {
        val input = "| Header 1 | Header 2 |\n|----------|----------|\n| Cell 1   | Cell 2   |"
        val doc = parse(input)
        val table = doc.children[0] as TableBlockNode
        assertEquals(listOf("Header 1", "Header 2"), table.headers.map { it.trim() })
        assertEquals(1, table.rows.size)
        assertEquals(listOf("Cell 1", "Cell 2"), table.rows[0].map { it.trim() })
    }

    @Test
    fun `table - alignment markers are captured`() {
        val input = "| L    | C     | R    |\n| :--- | :---: | ---: |\n| a    | b     | c    |"
        val doc = parse(input)
        val table = doc.children[0] as TableBlockNode
        assertEquals(TableAlignment.LEFT, table.alignments[0])
        assertEquals(TableAlignment.CENTER, table.alignments[1])
        assertEquals(TableAlignment.RIGHT, table.alignments[2])
    }

    @Test
    fun `table - multiple data rows`() {
        val input = "| A | B |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |\n| 5 | 6 |"
        val doc = parse(input)
        val table = doc.children[0] as TableBlockNode
        assertEquals(3, table.rows.size)
    }

    @Test
    fun `table - table without separator row is NOT a table`() {
        val input = "| not | a |\n| table | row |"
        val doc = parse(input)
        assertFalse(doc.children.any { it is TableBlockNode }, "No separator row → not a table")
    }
}
