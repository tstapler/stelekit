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

    @Ignore
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

    @Ignore
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

    // -------------------------------------------------------------------------
    // BULLET-DECORATED CONSTRUCTS — regression coverage for the structural bug
    // where a construct's marker was only detected BEFORE bullet-token
    // consumption (never after), so decorating a bullet with it fell through
    // to plain bullet/paragraph parsing and rendered as literal Markdown text.
    // This is the same class of bug already fixed for ATX headings
    // (see BlockParserTest's "bulleted ATX heading" tests); these tests cover
    // the remaining constructs: fenced code blocks, blockquotes, ordered list
    // items, thematic breaks, and GFM tables.
    // -------------------------------------------------------------------------

    @Test
    fun `bulleted fenced code block is classified as CodeFenceBlockNode`() {
        val doc = parse("- ```kotlin\nval x = 1\n```")
        assertEquals(1, doc.children.size)
        val code = doc.children[0] as CodeFenceBlockNode
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.rawContent)
    }

    @Test
    fun `bulleted blockquote is classified as BlockquoteBlockNode`() {
        val doc = parse("- > a quote")
        assertEquals(1, doc.children.size)
        val bq = doc.children[0] as BlockquoteBlockNode
        val inner = bq.children[0] as ParagraphBlockNode
        assertEquals("a quote", (inner.content[0] as TextNode).content.trim())
    }

    @Test
    fun `bulleted ordered list item is classified as OrderedListItemBlockNode`() {
        val doc = parse("- 1. first item")
        assertEquals(1, doc.children.size)
        val item = doc.children[0] as OrderedListItemBlockNode
        assertEquals(1, item.number)
        assertEquals("first item", (item.content[0] as TextNode).content.trim())
    }

    @Test
    fun `bulleted thematic break is classified as ThematicBreakBlockNode`() {
        val doc = parse("- ---")
        assertEquals(1, doc.children.size)
        assertIs<ThematicBreakBlockNode>(doc.children[0])
    }

    @Test
    fun `bulleted table is classified as TableBlockNode`() {
        val input = "- | Header 1 | Header 2 |\n|----------|----------|\n| Cell 1   | Cell 2   |"
        val doc = parse(input)
        assertEquals(1, doc.children.size)
        val table = doc.children[0] as TableBlockNode
        assertEquals(listOf("Header 1", "Header 2"), table.headers.map { it.trim() })
        assertEquals(1, table.rows.size)
        assertEquals(listOf("Cell 1", "Cell 2"), table.rows[0].map { it.trim() })
    }

    // -------------------------------------------------------------------------
    // BULLET-DECORATED CONSTRUCTS — nested children & properties regression
    // coverage. Fixing the classification bug above (returning the construct
    // node immediately) originally left a second, more severe bug: any outline
    // children or "key:: value" properties following the decorated bullet were
    // silently reparented to the grandparent level instead of attaching to the
    // construct itself, since the early return skipped parseBlock's shared
    // step-3 handling entirely. These tests cover that data-loss regression.
    // -------------------------------------------------------------------------

    @Test
    fun `bulleted fenced code block keeps a nested outline child`() {
        val doc = parse("- ```kotlin\nval x = 1\n```\n  - child note")
        assertEquals(1, doc.children.size)
        val code = doc.children[0] as CodeFenceBlockNode
        assertEquals(1, code.children.size)
        val child = code.children[0] as BulletBlockNode
        assertEquals("child note", (child.content[0] as TextNode).content.trim())
    }

    @Test
    fun `bulleted fenced code block parses a trailing property`() {
        val doc = parse("- ```kotlin\nval x = 1\n```\n  id:: abc")
        val code = doc.children[0] as CodeFenceBlockNode
        assertEquals("abc", code.properties["id"]?.trim())
    }

    @Test
    fun `bulleted fenced code block hands a non-property, non-bullet indented line to its children (tryConsumeIndentedProperty fallback)`() {
        // Regression test for the shared tryConsumeIndentedProperty() speculative-parse
        // helper: "  just some text" is indented past the code fence's level but is
        // neither a "key:: value" property nor a bullet. tryConsumeIndentedProperty must
        // fully restore the lexer/token state (including the leading INDENT) on its
        // failed property match so parseBlocksAtLevel can re-parse the line from scratch
        // as an ordinary child paragraph — not silently drop it or misparse it.
        val doc = parse("- ```kotlin\nval x = 1\n```\n  just some text")
        val code = doc.children[0] as CodeFenceBlockNode
        assertEquals(1, code.children.size)
        val child = code.children[0] as ParagraphBlockNode
        assertEquals("just some text", (child.content[0] as TextNode).content.trim())
    }

    @Test
    fun `bulleted blockquote keeps a nested outline child alongside its own quote lines`() {
        val doc = parse("- > a quote\n  - child note")
        val bq = doc.children[0] as BlockquoteBlockNode
        // First child is the quote's own paragraph content (existing behaviour).
        assertIs<ParagraphBlockNode>(bq.children[0])
        // Second child is the nested outline bullet — must not be dropped.
        assertEquals(2, bq.children.size, "Outline child must be preserved alongside the quote's own paragraph")
        val outlineChild = bq.children[1] as BulletBlockNode
        assertEquals("child note", (outlineChild.content[0] as TextNode).content.trim())
    }

    @Test
    fun `bulleted ordered list item keeps a nested outline child`() {
        val doc = parse("- 1. first item\n  - child note")
        val item = doc.children[0] as OrderedListItemBlockNode
        assertEquals(1, item.children.size)
        val child = item.children[0] as BulletBlockNode
        assertEquals("child note", (child.content[0] as TextNode).content.trim())
    }

    @Test
    fun `bulleted ordered list item parses a trailing property`() {
        val doc = parse("- 1. first item\n  id:: xyz")
        val item = doc.children[0] as OrderedListItemBlockNode
        assertEquals("xyz", item.properties["id"]?.trim())
    }

    @Test
    fun `bulleted thematic break keeps a nested outline child`() {
        val doc = parse("- ---\n  - child note")
        val brk = doc.children[0] as ThematicBreakBlockNode
        assertEquals(1, brk.children.size)
        val child = brk.children[0] as BulletBlockNode
        assertEquals("child note", (child.content[0] as TextNode).content.trim())
    }

    @Test
    fun `bulleted table keeps a nested outline child`() {
        val input = "- | Header 1 | Header 2 |\n|----------|----------|\n| Cell 1   | Cell 2   |\n  - child note"
        val doc = parse(input)
        val table = doc.children[0] as TableBlockNode
        assertEquals(1, table.children.size)
        val child = table.children[0] as BulletBlockNode
        assertEquals("child note", (child.content[0] as TextNode).content.trim())
    }

    @Test
    fun `bulleted fenced code block resumes sibling parsing after its children`() {
        val doc = parse("- ```kotlin\nval x = 1\n```\n  - child note\n- next sibling")
        assertEquals(2, doc.children.size, "next sibling must be a root sibling, not nested under the code block")
        assertIs<CodeFenceBlockNode>(doc.children[0])
        val next = doc.children[1] as BulletBlockNode
        assertEquals("next sibling", (next.content[0] as TextNode).content.trim())
    }

    @Test
    fun `bulleted ordered list item without a space after the dot is NOT classified as an ordered list`() {
        // Boundary case: "1.item" (no space) must not match the ordered-list marker,
        // matching the top-level ORDERED_LIST_EXTRACT_REGEX + WS/EOF/NEWLINE guard.
        val doc = parse("- 1.item not a list")
        assertEquals(1, doc.children.size)
        assertIs<BulletBlockNode>(doc.children[0], "Missing space after the dot must fall back to a plain bullet")
    }

    @Test
    fun `bulleted dashes with trailing text are NOT classified as a thematic break`() {
        // Boundary case: "---text" is not a bare thematic break line (no NEWLINE/EOF
        // immediately after the run of dashes), so it must fall back to a plain bullet.
        val doc = parse("- ---text")
        assertEquals(1, doc.children.size)
        assertIs<BulletBlockNode>(doc.children[0], "Dashes followed by text must fall back to a plain bullet")
    }

    // -------------------------------------------------------------------------
    // INDENT LEVEL TRACKING — CodeFenceBlockNode, BlockquoteBlockNode,
    // ThematicBreakBlockNode, TableBlockNode. Prior to this fix these four
    // constructs always hardcoded indentLevel=0 (via MarkdownParser.convertBlock's
    // `level = 0` branches), so any of them decorating a nested bullet lost their
    // true outline nesting depth on conversion to ParsedBlock. Mirrors the
    // HeadingBlockNode.indentLevel fix.
    // -------------------------------------------------------------------------

    @Test
    fun `top-level fenced code block has indentLevel 0`() {
        val doc = parse("```kotlin\nval x = 1\n```")
        val code = doc.children[0] as CodeFenceBlockNode
        assertEquals(0, code.indentLevel)
    }

    @Test
    fun `bulleted fenced code block carries the bullet's outline level as indentLevel`() {
        val doc = parse("- root\n  - ```kotlin\nval x = 1\n```")
        val root = doc.children[0] as BulletBlockNode
        val code = root.children[0] as CodeFenceBlockNode
        assertEquals(1, code.indentLevel)
    }

    @Test
    fun `top-level blockquote has indentLevel 0`() {
        val doc = parse("> a quote")
        val bq = doc.children[0] as BlockquoteBlockNode
        assertEquals(0, bq.indentLevel)
    }

    @Test
    fun `bulleted blockquote carries the bullet's outline level as indentLevel`() {
        val doc = parse("- root\n  - > a quote")
        val root = doc.children[0] as BulletBlockNode
        val bq = root.children[0] as BlockquoteBlockNode
        assertEquals(1, bq.indentLevel)
    }

    @Test
    fun `top-level thematic break has indentLevel 0`() {
        val doc = parse("---")
        val brk = doc.children[0] as ThematicBreakBlockNode
        assertEquals(0, brk.indentLevel)
    }

    @Test
    fun `bulleted thematic break carries the bullet's outline level as indentLevel`() {
        val doc = parse("- root\n  - ---")
        val root = doc.children[0] as BulletBlockNode
        val brk = root.children[0] as ThematicBreakBlockNode
        assertEquals(1, brk.indentLevel)
    }

    @Test
    fun `top-level table has indentLevel 0`() {
        val input = "| A | B |\n|---|---|\n| 1 | 2 |"
        val doc = parse(input)
        val table = doc.children[0] as TableBlockNode
        assertEquals(0, table.indentLevel)
    }

    @Test
    fun `bulleted table carries the bullet's outline level as indentLevel`() {
        val input = "- root\n  - | A | B |\n|---|---|\n| 1 | 2 |"
        val doc = parse(input)
        val root = doc.children[0] as BulletBlockNode
        val table = root.children[0] as TableBlockNode
        assertEquals(1, table.indentLevel)
    }

    // -------------------------------------------------------------------------
    // RAW HTML BLOCKS — CommonMark §4.6. Previously RawHtmlBlockNode existed in
    // the AST (and was fully wired through OutlinerParser, MarkdownParser, and
    // BlockTypeMapper) but BlockParser never constructed one, so literal HTML
    // fell through to plain paragraph/bullet parsing and rendered as inline
    // text — the same class of bug as the original ATX-heading gap.
    // -------------------------------------------------------------------------

    @Test
    fun `top-level HTML block tag is classified as RawHtmlBlockNode`() {
        val doc = parse("<div>\nsome content\n</div>")
        assertIs<RawHtmlBlockNode>(doc.children[0])
    }

    @Test
    fun `multi-line raw HTML block is a single node, not split into siblings`() {
        // CommonMark §4.6 type-6 raw HTML blocks continue consuming lines until a
        // blank line or EOF. A parser that only reads the opening tag's line would
        // split "some content" and "</div>" off into their own sibling blocks.
        val doc = parse("<div>\nsome content\n</div>")
        assertEquals(1, doc.children.size, "Multi-line raw HTML must parse as one block, got: ${doc.children}")
        val html = doc.children[0] as RawHtmlBlockNode
        assertEquals("<div>\nsome content\n</div>", html.rawHtml.trim())
    }

    @Test
    fun `raw HTML block captures the opening tag line verbatim`() {
        val doc = parse("<div class=\"note\">")
        val html = doc.children[0] as RawHtmlBlockNode
        assertEquals("<div class=\"note\">", html.rawHtml.trim())
    }

    @Test
    fun `HTML comment is classified as RawHtmlBlockNode`() {
        val doc = parse("<!-- a comment -->")
        assertIs<RawHtmlBlockNode>(doc.children[0])
    }

    @Test
    fun `closing HTML tag alone is classified as RawHtmlBlockNode`() {
        val doc = parse("</div>")
        assertIs<RawHtmlBlockNode>(doc.children[0])
    }

    @Test
    fun `bulleted HTML block tag is classified as RawHtmlBlockNode`() {
        val doc = parse("- <div>inline html</div>")
        assertEquals(1, doc.children.size)
        assertIs<RawHtmlBlockNode>(doc.children[0])
    }

    @Test
    fun `bulleted raw HTML block carries the bullet's outline level as indentLevel`() {
        val doc = parse("- root\n  - <div>nested html</div>")
        val root = doc.children[0] as BulletBlockNode
        val html = root.children[0] as RawHtmlBlockNode
        assertEquals(1, html.indentLevel)
    }

    @Test
    fun `top-level raw HTML block has indentLevel 0`() {
        val doc = parse("<div>top level</div>")
        val html = doc.children[0] as RawHtmlBlockNode
        assertEquals(0, html.indentLevel)
    }

    @Test
    fun `bulleted raw HTML block keeps a nested outline child`() {
        val doc = parse("- <div>html</div>\n  - child note")
        val html = doc.children[0] as RawHtmlBlockNode
        assertEquals(1, html.children.size)
        val child = html.children[0] as BulletBlockNode
        assertEquals("child note", (child.content[0] as TextNode).content.trim())
    }

    @Test
    fun `bulleted raw HTML block parses a trailing property`() {
        val doc = parse("- <div>html</div>\n  id:: abc")
        val html = doc.children[0] as RawHtmlBlockNode
        assertEquals("abc", html.properties["id"]?.trim())
    }

    @Test
    fun `nested bulleted heading carries the bullet's outline level as indentLevel`() {
        val doc = parse("- parent\n  - # Nested Heading")
        val root = doc.children[0] as BulletBlockNode
        val heading = root.children[0] as HeadingBlockNode
        assertEquals(1, heading.indentLevel)
    }

    @Test
    fun `inline-only span tag is NOT classified as raw HTML (falls back to paragraph)`() {
        // <span> is an inline HTML element, not one of the CommonMark §4.6 type-6
        // block-level tags, so it must not be picked up by the BLOCK_HTML_TAGS check.
        val doc = parse("<span>inline html</span>")
        assertIs<ParagraphBlockNode>(doc.children[0], "Inline <span> must fall back to a paragraph, not RawHtmlBlockNode")
    }

    @Test
    fun `DOCTYPE declaration is NOT classified as raw HTML (falls back to paragraph)`() {
        // <!DOCTYPE html> is a type-7 HTML construct in CommonMark, not one of the
        // recognized block tag names or an HTML comment, so BLOCK_HTML_TAGS must not
        // match it.
        val doc = parse("<!DOCTYPE html>")
        assertIs<ParagraphBlockNode>(doc.children[0], "<!DOCTYPE html> must fall back to a paragraph, not RawHtmlBlockNode")
    }

    @Test
    fun `unrecognized angle-bracket text is NOT classified as raw HTML (falls back to paragraph)`() {
        // Boundary case: "<3 not html" starts with '<' but the following token is not a
        // recognized block-level tag name (nor an HTML comment), so it must fall through
        // to plain paragraph parsing rather than being misclassified as RawHtmlBlockNode.
        val doc = parse("<3 not html")
        assertIs<ParagraphBlockNode>(doc.children[0], "Unrecognized angle-bracket text must fall back to a paragraph")
    }

    @Test
    fun `MarkdownParser round-trips RawHtmlBlockNode content and indentLevel`() {
        val page = dev.stapler.stelekit.parser.MarkdownParser().parsePage("- root\n  - <div>hi</div>")
        val root = page.blocks[0]
        val html = root.children[0]
        assertEquals(dev.stapler.stelekit.model.BlockType.RawHtml, html.blockType)
        assertEquals(1, html.level)
        assertTrue(html.content.contains("<div>hi</div>"), "Raw HTML content must round-trip, got: ${html.content}")
    }

    // -------------------------------------------------------------------------
    // REGRESSION — PR #260 adversarial review findings.
    // -------------------------------------------------------------------------

    @Test
    fun `non-bulleted fenced code block nested under a heading carries the true outline depth as indentLevel`() {
        // Regression for tryConsumeNonHeadingConstruct hardcoding indentLevel=0 for any
        // non-bullet-decorated construct, even when it is an outline child parsed via
        // parseBlocksAtLevel(level + 1) at a nonzero depth. `level` is always the correct
        // outline nesting depth (see the ATX heading path in parseBlock, which uses
        // `indentLevel = level` unconditionally) — it must not be zeroed just because the
        // construct itself isn't bullet-decorated.
        val doc = parse("# Heading\n  ```kotlin\nval x = 1\n```")
        val heading = doc.children[0] as HeadingBlockNode
        val code = heading.children[0] as CodeFenceBlockNode
        assertEquals(1, code.indentLevel, "Non-bulleted child construct must carry its true outline depth, not 0")
    }

    @Test
    fun `raw HTML block continuation consumes lines indented deeper than the opening tag, not just equal`() {
        // Regression: tryParseRawHtmlConstruct's continuation loop previously required
        // peekIndentLevel() == level exactly, so any continuation line indented *deeper*
        // than the construct's own level (e.g. naturally-indented nested markup, or a
        // bulleted HTML block's content indented one level past the bullet) incorrectly
        // terminated the block early instead of continuing to a blank line/EOF per
        // CommonMark §4.6.
        val doc = parse("- <div>\n  content\n  </div>")
        assertEquals(1, doc.children.size, "Must parse as a single block, got: ${doc.children}")
        val html = doc.children[0] as RawHtmlBlockNode
        val lines = html.rawHtml.trim().lines().map { it.trim() }
        assertEquals(listOf("<div>", "content", "</div>"), lines)
        assertEquals(0, html.children.size, "No spurious child blocks should be produced")
    }

    @Test
    fun `non-bulleted top-level raw HTML block consumes all indented continuation lines`() {
        val doc = parse("<ul>\n  <li>foo</li>\n</ul>")
        assertEquals(1, doc.children.size, "Must parse as a single block, got: ${doc.children}")
        val html = doc.children[0] as RawHtmlBlockNode
        val lines = html.rawHtml.trim().lines().map { it.trim() }
        assertEquals(listOf("<ul>", "<li>foo</li>", "</ul>"), lines)
    }
}
