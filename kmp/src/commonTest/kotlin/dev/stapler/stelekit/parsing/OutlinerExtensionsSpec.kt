package dev.stapler.stelekit.parsing

import dev.stapler.stelekit.parser.MarkdownParser
import dev.stapler.stelekit.parsing.ast.*
import kotlin.test.*

/**
 * Spec-driven tests for Logseq-specific inline parsing extensions.
 *
 * Legend:
 *   - Tests with no annotation: IMPLEMENTED — expected to pass.
 *   - Tests marked @Ignore: NOT YET IMPLEMENTED — stubbed so they compile and document
 *     expected behaviour; un-ignore when the feature is added.
 *   - Tests with a "Bug P0" comment: KNOWN BUG — written without @Ignore so they
 *     show as failing in CI until fixed.
 */
class OutlinerExtensionsSpec {

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun parse(input: String): List<InlineNode> = InlineParser(input).parse()

    private fun parseBlock(input: String): DocumentNode = BlockParser(input).parse()

    private fun parsePage(input: String) = MarkdownParser().parsePage(input)

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 1 — Hashtags  (#tag)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `hashtag produces TagNode`() {
        val result = parse("#mytag")
        val tags = result.filterIsInstance<TagNode>()
        assertEquals(1, tags.size)
        assertEquals("mytag", tags.single().tag)
    }

    @Test
    fun `hashtag tag value does not include the hash character`() {
        val result = parse("#hello")
        val tag = result.filterIsInstance<TagNode>().single()
        assertFalse(tag.tag.startsWith("#"), "tag.tag must not contain the leading '#'")
    }

    @Test
    fun `hashtag followed by space stops at space boundary`() {
        val result = parse("#tag rest of text")
        val tag = result.filterIsInstance<TagNode>().single()
        assertEquals("tag", tag.tag)
    }

    @Test
    fun `hashtag followed by space leaves trailing text as TextNodes`() {
        val result = parse("#tag rest")
        val texts = result.filterIsInstance<TextNode>()
        val combined = texts.joinToString("") { it.content }
        assertTrue(combined.contains("rest"), "Trailing text should be preserved as TextNode(s)")
    }

    @Test
    fun `multiple hashtags in one line produce multiple TagNodes`() {
        val result = parse("#foo #bar #baz")
        val tags = result.filterIsInstance<TagNode>()
        assertEquals(3, tags.size)
        assertEquals(listOf("foo", "bar", "baz"), tags.map { it.tag })
    }

    @Test
    fun `hashtag mixed with wiki-link produces both node types`() {
        val result = parse("#tag and [[Page]]")
        assertTrue(result.filterIsInstance<TagNode>().any { it.tag == "tag" })
        assertTrue(result.filterIsInstance<WikiLinkNode>().any { it.target == "Page" })
    }

    // Terminator tests — the chars below should end the tag.
    // The Lexer's isSpecial() does NOT include ',', '.', '!', '?', '"', ':' in the TEXT
    // break set (only whitespace, brackets, formatting chars, and ':' for colon token).
    // As a result parseTag() receives a TEXT token that includes the trailing punctuation,
    // so these tests are EXPECTED TO FAIL with the current implementation and are written
    // without @Ignore so they show as red in CI.

    @Test
    fun `hashtag stops at comma`() {
        // Bug: Lexer TEXT token includes the comma, so tag becomes "tag," instead of "tag"
        val result = parse("#tag, more text")
        val tag = result.filterIsInstance<TagNode>().single()
        assertEquals("tag", tag.tag)
    }

    @Test
    fun `hashtag stops at period`() {
        // Bug: Lexer TEXT token includes the period, so tag becomes "tag." instead of "tag"
        val result = parse("#tag. sentence continues")
        val tag = result.filterIsInstance<TagNode>().single()
        assertEquals("tag", tag.tag)
    }

    @Test
    fun `hashtag stops at exclamation mark`() {
        // Bug: Lexer TEXT token includes '!', so tag becomes "tag!" instead of "tag"
        val result = parse("#tag!")
        val tag = result.filterIsInstance<TagNode>().single()
        assertEquals("tag", tag.tag)
    }

    @Test
    fun `hashtag stops at question mark`() {
        // Bug: Lexer TEXT token includes '?', so tag becomes "tag?" instead of "tag"
        val result = parse("#tag?")
        val tag = result.filterIsInstance<TagNode>().single()
        assertEquals("tag", tag.tag)
    }

    @Test
    fun `hashtag stops at double-quote`() {
        // Bug: Lexer TEXT token includes '"', so tag becomes `tag"` instead of "tag"
        val result = parse("""#tag" some text""")
        val tag = result.filterIsInstance<TagNode>().single()
        assertEquals("tag", tag.tag)
    }

    // Note: ':' IS in the Lexer isSpecial set and produces a COLON token, so
    // parseTag() will not see it inside the TEXT token — this one should pass.
    @Test
    fun `hashtag stops at colon`() {
        val result = parse("#tag: description")
        val tags = result.filterIsInstance<TagNode>()
        assertEquals(1, tags.size)
        assertEquals("tag", tags.single().tag)
    }

    @Test
    fun `lone hash with no following text produces TextNode not TagNode`() {
        val result = parse("# ")
        assertFalse(result.any { it is TagNode }, "Lone '#' should not produce a TagNode")
        assertTrue(result.any { it is TextNode }, "Lone '#' should produce a TextNode")
    }

    @Test
    fun `hash at end of input produces TextNode not TagNode`() {
        val result = parse("#")
        assertFalse(result.any { it is TagNode }, "Lone '#' at EOF should not produce a TagNode")
    }

    @Test
    fun `numeric-only hashtag behaviour is deterministic`() {
        // We do not mandate pass/fail; we mandate that the parser does not crash
        // and that the '#' token and digits are accounted for in the output.
        val result = parse("#123")
        // Either a TagNode("123") or two TextNodes — both are acceptable, but the
        // output must be non-empty and must contain no null elements.
        assertTrue(result.isNotEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 2 — WikiLinks  ([[target]])
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `simple wiki-link produces WikiLinkNode`() {
        val result = parse("[[Page Name]]")
        val link = result.filterIsInstance<WikiLinkNode>().single()
        assertEquals("Page Name", link.target)
    }

    @Test
    fun `wiki-link alias is null when no pipe separator is present`() {
        val result = parse("[[Page]]")
        val link = result.filterIsInstance<WikiLinkNode>().single()
        assertNull(link.alias)
    }

    @Test
    fun `wiki-link in sentence preserves surrounding text`() {
        val result = parse("see [[Concepts]] for details")
        assertTrue(result.filterIsInstance<WikiLinkNode>().any { it.target == "Concepts" })
        val text = result.filterIsInstance<TextNode>().joinToString("") { it.content }
        assertTrue(text.contains("see") || text.contains("for details"),
            "Surrounding text must be preserved")
    }

    @Test
    fun `multiple wiki-links on same line are all parsed`() {
        val result = parse("[[A]] and [[B]]")
        val links = result.filterIsInstance<WikiLinkNode>()
        assertEquals(2, links.size)
        assertEquals(setOf("A", "B"), links.map { it.target }.toSet())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 3 — Block References  ((uuid))
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `block reference produces BlockRefNode`() {
        val uuid = "64a8c4f2-1234-5678-abcd-9ef012345678"
        val result = parse("(($uuid))")
        val ref = result.filterIsInstance<BlockRefNode>().single()
        assertEquals(uuid, ref.blockUuid)
    }

    @Test
    fun `block reference uuid does not include surrounding parens`() {
        val result = parse("((abc-123))")
        val ref = result.filterIsInstance<BlockRefNode>().single()
        assertFalse(ref.blockUuid.startsWith("("))
        assertFalse(ref.blockUuid.endsWith(")"))
    }

    @Test
    fun `single parenthesis is plain text not a block ref`() {
        val result = parse("(not a ref)")
        assertFalse(result.any { it is BlockRefNode },
            "Single parens should not produce BlockRefNode")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 4 — Properties  (key:: value)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `simple property is parsed from block`() {
        val doc = parseBlock("- block\n  id:: abc123")
        val block = doc.children[0] as BulletBlockNode
        assertEquals("abc123", block.properties["id"]?.trim())
    }

    @Test
    fun `property with string value containing spaces is parsed`() {
        val doc = parseBlock("- block\n  tags:: foo, bar, baz")
        val block = doc.children[0] as BulletBlockNode
        assertEquals("foo, bar, baz", block.properties["tags"]?.trim())
    }

    @Test
    fun `property key is case-sensitive`() {
        val doc = parseBlock("- block\n  MyKey:: value")
        val block = doc.children[0] as BulletBlockNode
        assertNotNull(block.properties["MyKey"], "Properties should preserve key casing")
    }

    @Test
    fun `multiple properties on separate lines are all captured`() {
        val doc = parseBlock("- block\n  id:: abc\n  type:: page")
        val block = doc.children[0] as BulletBlockNode
        assertEquals("abc", block.properties["id"]?.trim())
        assertEquals("page", block.properties["type"]?.trim())
    }

    @Test
    fun `property with empty value is captured with blank string`() {
        val doc = parseBlock("- block\n  key:: ")
        val block = doc.children[0] as BulletBlockNode
        // Property key must exist; value must be empty or blank
        assertTrue(block.properties.containsKey("key"),
            "Property with empty value must still be present in the map")
        assertTrue(block.properties["key"]?.trim()?.isEmpty() ?: false,
            "Property value must be blank")
    }

    @Test
    fun `hyphenated property key is parsed`() {
        val doc = parseBlock("- block\n  created-at:: 2024-01-01")
        val block = doc.children[0] as BulletBlockNode
        assertEquals("2024-01-01", block.properties["created-at"]?.trim())
    }

    @Test
    fun `underscore property key is parsed`() {
        // Bug: Lexer emits '_' as UNDERSCORE token (it is in isSpecial()), which breaks
        // the TEXT token for "page_type" into "page" + UNDERSCORE + "type".
        // BlockParser.tryParseProperty then sees "page" as the key candidate but finds
        // UNDERSCORE (not COLON) at peek(1), so the whole line falls through to content.
        // Fix: either add '_' to property-key lexing or teach tryParseProperty to handle
        // underscore-joined identifiers.
        val doc = parseBlock("- block\n  page_type:: reference")
        val block = doc.children[0] as BulletBlockNode
        assertEquals("reference", block.properties["page_type"]?.trim())
    }

    @Test
    fun `property with dotted key name`() {
        // BlockParser.tryParseProperty relies on the Lexer, which does NOT treat '.' as
        // a special character — so "date.format" arrives as a single TEXT token and the
        // key is parsed correctly. This test passes via BlockParser.
        //
        // Note: PropertiesParser (used for page-level property extraction elsewhere) has
        // a separate regex [\w\-_]+ that WOULD exclude dotted keys. That path is not
        // exercised here but is a latent P0 bug for the legacy PropertiesParser code path.
        val doc = parseBlock("- block\n  date.format:: YYYY-MM-DD")
        val block = doc.children[0] as BulletBlockNode
        assertEquals("YYYY-MM-DD", block.properties["date.format"]?.trim())
    }

    @Test
    fun `property line is not included in block content`() {
        val doc = parseBlock("- visible content\n  id:: abc123")
        val block = doc.children[0] as BulletBlockNode
        val rawContent = block.content.filterIsInstance<TextNode>()
            .joinToString("") { it.content }
        assertFalse(rawContent.contains("id::"),
            "Property lines must not appear in block content")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 5 — Task Markers  (TODO / DONE / etc.)
    // ─────────────────────────────────────────────────────────────────────────
    //
    // TaskMarkerNode is not yet in the AST — all tests below are @Ignored.
    // Un-ignore and adjust assertions once TaskMarkerNode is implemented.

    @Test
    fun `TODO marker produces TaskMarkerNode with marker TODO`() {
        val result = parse("TODO task content")
        val taskMarker = result.filterIsInstance<TaskMarkerNode>().single()
        assertEquals("TODO", taskMarker.marker)
    }

    @Test
    fun `DONE marker produces TaskMarkerNode with marker DONE`() {
        val result = parse("DONE completed task")
        val taskMarker = result.filterIsInstance<TaskMarkerNode>().single()
        assertEquals("DONE", taskMarker.marker)
    }

    @Test
    fun `NOW marker produces TaskMarkerNode with marker NOW`() {
        val result = parse("NOW urgent thing")
        val taskMarker = result.filterIsInstance<TaskMarkerNode>().single()
        assertEquals("NOW", taskMarker.marker)
    }

    @Test
    fun `LATER marker produces TaskMarkerNode with marker LATER`() {
        val result = parse("LATER someday")
        val taskMarker = result.filterIsInstance<TaskMarkerNode>().single()
        assertEquals("LATER", taskMarker.marker)
    }

    @Test
    fun `WAITING marker produces TaskMarkerNode with marker WAITING`() {
        val result = parse("WAITING on input")
        val taskMarker = result.filterIsInstance<TaskMarkerNode>().single()
        assertEquals("WAITING", taskMarker.marker)
    }

    @Test
    fun `CANCELLED marker produces TaskMarkerNode with marker CANCELLED`() {
        val result = parse("CANCELLED abandoned")
        val taskMarker = result.filterIsInstance<TaskMarkerNode>().single()
        assertEquals("CANCELLED", taskMarker.marker)
    }

    @Test
    fun `task with priority marker produces both TaskMarkerNode and PriorityNode`() {
        val result = parse("TODO [#A] high priority task")
        val taskMarker = result.filterIsInstance<TaskMarkerNode>().single()
        assertEquals("TODO", taskMarker.marker)
        val priority = result.filterIsInstance<PriorityNode>().single()
        assertEquals("A", priority.priority)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 6 — Macros  ({{name args}})
    // ─────────────────────────────────────────────────────────────────────────
    //
    // MacroNode is not yet in the AST. All tests are @Ignored.
    // CRITICAL NOTE: when MacroNode is added, ensure that references inside macros
    // (e.g. the [[Page]] inside {{embed [[Page]]}}) are still extractable for
    // back-link indexing — see the reference extraction test below.

    @Test
    fun `embed macro produces MacroNode with name embed`() {
        val result = parse("{{embed [[Page Name]]}}")
        val macro = result.filterIsInstance<MacroNode>().single()
        assertEquals("embed", macro.name)
    }

    @Test
    fun `embed macro args contain the raw wiki-link string`() {
        val result = parse("{{embed [[Page Name]]}}")
        val macro = result.filterIsInstance<MacroNode>().single()
        assertTrue(macro.arguments.any { it.contains("Page Name") })
    }

    @Test
    fun `query macro produces MacroNode with name query`() {
        val result = parse("{{query (todo todo)}}")
        val macro = result.filterIsInstance<MacroNode>().single()
        assertEquals("query", macro.name)
    }

    @Test
    fun `renderer macro produces MacroNode with name renderer`() {
        val result = parse("{{renderer :logseq/view, data}}")
        val macro = result.filterIsInstance<MacroNode>().single()
        assertEquals("renderer", macro.name)
    }

    @Test
    fun `wiki-link inside embed macro contributes to block references`() {
        val page = parsePage("- {{embed [[Target Page]]}}")
        val block = page.blocks[0]
        assertTrue(block.references.contains("Target Page"),
            "References inside macros must be indexed even when wrapped in MacroNode")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 7 — Timestamps  (SCHEDULED / DEADLINE)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `SCHEDULED timestamp is extracted from block content`() {
        val page = parsePage("- task\n  SCHEDULED: <2024-01-15 Mon>")
        val block = page.blocks[0]
        assertNotNull(block.scheduled, "SCHEDULED must be extracted from block")
    }

    @Test
    fun `SCHEDULED timestamp value contains the date string`() {
        val page = parsePage("- task\n  SCHEDULED: <2024-01-15 Mon>")
        val block = page.blocks[0]
        assertTrue(block.scheduled?.contains("2024-01-15") ?: false,
            "Scheduled date must contain the ISO date portion")
    }

    @Test
    fun `SCHEDULED timestamp is removed from visible block content`() {
        val page = parsePage("- task\n  SCHEDULED: <2024-01-15 Mon>")
        val block = page.blocks[0]
        assertFalse(block.content.contains("SCHEDULED:"),
            "SCHEDULED directive must not appear in block.content after extraction")
    }

    @Test
    fun `DEADLINE timestamp is extracted from block content`() {
        val page = parsePage("- task\n  DEADLINE: <2024-02-28 Wed>")
        val block = page.blocks[0]
        assertNotNull(block.deadline, "DEADLINE must be extracted from block")
    }

    @Test
    fun `DEADLINE timestamp value contains the date string`() {
        val page = parsePage("- task\n  DEADLINE: <2024-02-28 Wed>")
        val block = page.blocks[0]
        assertTrue(block.deadline?.contains("2024-02-28") ?: false,
            "Deadline date must contain the ISO date portion")
    }

    @Test
    fun `DEADLINE timestamp is removed from visible block content`() {
        val page = parsePage("- task\n  DEADLINE: <2024-02-28 Wed>")
        val block = page.blocks[0]
        assertFalse(block.content.contains("DEADLINE:"),
            "DEADLINE directive must not appear in block.content after extraction")
    }

    @Test
    fun `block with both SCHEDULED and DEADLINE extracts both`() {
        val page = parsePage(
            "- task\n  SCHEDULED: <2024-01-15 Mon>\n  DEADLINE: <2024-01-20 Sat>"
        )
        val block = page.blocks[0]
        assertNotNull(block.scheduled, "Both SCHEDULED and DEADLINE should be extracted")
        assertNotNull(block.deadline, "Both SCHEDULED and DEADLINE should be extracted")
    }

    @Test
    fun `block without timestamp has null scheduled and deadline`() {
        val page = parsePage("- plain task with no timestamps")
        val block = page.blocks[0]
        assertNull(block.scheduled, "scheduled must be null when not present")
        assertNull(block.deadline, "deadline must be null when not present")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 8 — Reference Extraction (via MarkdownParser / ParsedBlock)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `wiki-link in block content is included in references`() {
        val page = parsePage("- see [[Target Page]] for more")
        val block = page.blocks[0]
        assertTrue(block.references.contains("Target Page"),
            "WikiLink target must appear in block.references")
    }

    @Test
    fun `hashtag in block content is included in references`() {
        val page = parsePage("- note about #topic")
        val block = page.blocks[0]
        assertTrue(block.references.contains("topic"),
            "Tag name must appear in block.references")
    }

    @Test
    fun `block reference uuid is included in references`() {
        val uuid = "64a8c4f2-1234-5678-abcd-9ef012345678"
        val page = parsePage("- refers to (($uuid))")
        val block = page.blocks[0]
        assertTrue(block.references.contains(uuid),
            "Block ref UUID must appear in block.references")
    }

    @Test
    fun `multiple wiki-links all appear in references`() {
        val page = parsePage("- [[A]] and [[B]] and [[C]]")
        val block = page.blocks[0]
        assertTrue(block.references.containsAll(listOf("A", "B", "C")),
            "All wiki-link targets must appear in block.references")
    }

    @Test
    fun `wiki-link inside bold formatting is included in references`() {
        val page = parsePage("- **[[BoldLink]]**")
        val block = page.blocks[0]
        assertTrue(block.references.contains("BoldLink"),
            "WikiLink nested inside BoldNode must still be extracted as a reference")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 9 — Edge Cases and Robustness
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `empty input produces empty node list`() {
        val result = parse("")
        assertTrue(result.isEmpty(), "Empty input must produce an empty InlineNode list")
    }

    @Test
    fun `plain text with no markup produces a single TextNode`() {
        val result = parse("just plain text")
        val texts = result.filterIsInstance<TextNode>()
        val combined = texts.joinToString("") { it.content }
        assertEquals("just plain text", combined)
        assertFalse(result.any { it !is TextNode },
            "Plain text must not produce any non-TextNode nodes")
    }

    @Test
    fun `unclosed wiki-link bracket is treated as plain text`() {
        // Bug: InlineParser.parseLink() loops until ]] or EOF without backtracking.
        // When EOF is reached, it still returns WikiLinkNode with whatever content was
        // accumulated. This means "[[incomplete" → WikiLinkNode(target="incomplete")
        // instead of plain text. The parser also does not throw (good), but the node
        // type is wrong. Fix: backtrack on EOF and return TextNode("[[") + remaining text.
        val result = parse("[[incomplete")
        assertFalse(result.any { it is WikiLinkNode },
            "Unclosed [[ must not produce a WikiLinkNode — it should fall back to plain text")
    }

    @Test
    fun `unclosed block ref double-paren is treated as plain text`() {
        // "((no-close" — no closing )) — must not throw
        val result = parse("((no-close")
        // Parser may produce a BlockRefNode with partial content or plain text.
        // The critical requirement is that it does not crash.
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `inline parser does not throw on arbitrary unicode`() {
        val result = parse("日本語テキスト [[ページ]] #タグ")
        // Must not throw; WikiLinkNode and TagNode should be present
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `block parser handles deeply nested blocks`() {
        val input = """
            - level 0
              - level 1
                - level 2
                  - level 3
        """.trimIndent()
        val doc = parseBlock(input)
        val l0 = doc.children[0] as BulletBlockNode
        val l1 = l0.children[0] as BulletBlockNode
        val l2 = l1.children[0] as BulletBlockNode
        val l3 = l2.children[0] as BulletBlockNode
        assertEquals(0, l0.level)
        assertEquals(1, l1.level)
        assertEquals(2, l2.level)
        assertEquals(3, l3.level)
    }
}
