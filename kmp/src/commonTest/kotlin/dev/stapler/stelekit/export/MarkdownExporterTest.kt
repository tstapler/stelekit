package dev.stapler.stelekit.export

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.datetime.LocalDate

class MarkdownExporterTest {

    private val now = Clock.System.now()
    private val exporter = MarkdownExporter()

    private fun page(
        name: String = "Test Page",
        properties: Map<String, String> = emptyMap(),
        isJournal: Boolean = false,
        journalDate: LocalDate? = null,
    ) = Page(
        uuid = PageUuid("page-1"),
        name = name,
        createdAt = now,
        updatedAt = now,
        properties = properties,
        isJournal = isJournal,
        journalDate = journalDate,
    )

    private fun block(
        content: String,
        level: Int = 0,
        position: String = "a0",
        uuid: String = "block-1",
        parentUuid: String? = null,
        properties: Map<String, String> = emptyMap(),
    ) = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid("page-1"),
        parentUuid = parentUuid?.let { BlockUuid(it) },
        content = content,
        level = level,
        position = position,
        createdAt = now,
        updatedAt = now,
        properties = properties,
    )

    // U-MD-01: empty page → "# Test Page\n\n" (no frontmatter for empty properties)
    @Test
    fun `U-MD-01 empty page produces heading only without frontmatter`() {
        val output = exporter.export(page(), emptyList(), emptyMap())
        assertEquals("# Test Page\n\n", output)
    }

    // U-MD-02: level=0 block renders as paragraph (no "- " prefix)
    @Test
    fun `U-MD-02 level 0 block renders as paragraph without bullet`() {
        val output = exporter.export(page(), listOf(block("hello world", level = 0)), emptyMap())
        assertContains(output, "hello world\n\n")
        assertFalse(output.contains("- hello world"), "Level-0 block must not have a bullet prefix")
    }

    // U-MD-03: level=1 block renders as "- child\n"
    @Test
    fun `U-MD-03 level 1 block renders as bullet item`() {
        val parent = block("parent", level = 0, position = "a0", uuid = "parent")
        val child = block("child", level = 1, position = "a0", uuid = "child", parentUuid = "parent")
        val output = exporter.export(page(), listOf(parent, child), emptyMap())
        assertContains(output, "- child\n")
    }

    // U-MD-04: level=2 block renders as "  - grandchild\n" (2-space indent)
    @Test
    fun `U-MD-04 level 2 block renders with two-space indent`() {
        val parent = block("parent", level = 0, position = "a0", uuid = "parent")
        val child = block("child", level = 1, position = "a0", uuid = "child", parentUuid = "parent")
        val grandchild = block("grandchild", level = 2, position = "a0", uuid = "grand", parentUuid = "child")
        val output = exporter.export(page(), listOf(parent, child, grandchild), emptyMap())
        assertContains(output, "  - grandchild\n")
    }

    // U-MD-05: tab-indented input normalizes to 2-space bullet indent
    @Test
    fun `U-MD-05 level 1 block produces two-space-based indentation not tab`() {
        val parent = block("parent", level = 0, position = "a0", uuid = "parent")
        val child = block("item", level = 1, position = "a0", uuid = "child", parentUuid = "parent")
        val output = exporter.export(page(), listOf(parent, child), emptyMap())
        assertContains(output, "- item\n")
        assertFalse(output.contains("\t- item"), "Tab indentation must not appear; exporter uses 2-space indent")
    }

    // U-MD-06: HighlightNode renders as bold ("==highlighted==" → "**highlighted**")
    @Test
    fun `U-MD-06 highlight node renders as bold markdown`() {
        val output = exporter.export(page(), listOf(block("==highlighted==")), emptyMap())
        assertContains(output, "**highlighted**")
        assertFalse(output.contains("==highlighted=="), "Logseq highlight syntax must be converted to bold")
    }

    // U-MD-07: WikiLinkNode with no alias renders as "[[target]]"
    @Test
    fun `U-MD-07 wiki link without alias renders as double-bracket link`() {
        val output = exporter.export(page(), listOf(block("[[Page Name]]")), emptyMap())
        assertContains(output, "[[Page Name]]")
    }

    // U-MD-08: WikiLinkNode with alias renders using alias text
    @Test
    fun `U-MD-08 wiki link with alias renders as markdown link with alias`() {
        val output = exporter.export(page(), listOf(block("[[Page Name|display text]]")), emptyMap())
        assertContains(output, "[display text](Page Name)")
        assertFalse(output.contains("[[Page Name|display text]]"), "Aliased wiki link must use Markdown link syntax")
    }

    // U-MD-09: BlockRefNode resolved renders inline text
    @Test
    fun `U-MD-09 resolved block ref renders as referenced text`() {
        val output = exporter.export(
            page(),
            listOf(block("((ref-uuid))")),
            resolvedRefs = mapOf("ref-uuid" to "resolved content"),
        )
        assertContains(output, "resolved content")
        assertFalse(output.contains("((ref-uuid))"), "Raw block-ref syntax must not appear when resolved")
    }

    // U-MD-10: BlockRefNode dangling renders as "[block ref]"
    @Test
    fun `U-MD-10 dangling block ref renders as placeholder text`() {
        val output = exporter.export(page(), listOf(block("((missing-uuid))")), resolvedRefs = emptyMap())
        assertContains(output, "[block ref]")
    }

    // U-MD-11: TODO marker renders as GFM checkbox unchecked
    // The exporter appends "[ ] " for the TaskMarkerNode, then the original space
    // between the marker and text is preserved as a WS token, producing "[ ]  task text".
    @Test
    fun `U-MD-11 TODO marker renders as unchecked GFM checkbox`() {
        val output = exporter.export(page(), listOf(block("TODO task text")), emptyMap())
        assertContains(output, "[ ] ")
        assertContains(output, "task text")
        assertFalse(output.contains("TODO "), "Raw TODO marker text must not appear")
    }

    // U-MD-12: DONE marker renders as GFM checkbox checked
    @Test
    fun `U-MD-12 DONE marker renders as checked GFM checkbox`() {
        val output = exporter.export(page(), listOf(block("DONE finished task")), emptyMap())
        assertContains(output, "[x] ")
        assertContains(output, "finished task")
        assertFalse(output.contains("DONE "), "Raw DONE marker text must not appear")
    }

    // U-MD-13: NOW marker renders as bold text prefix
    @Test
    fun `U-MD-13 NOW marker renders as bold prefix`() {
        val output = exporter.export(page(), listOf(block("NOW active task")), emptyMap())
        assertContains(output, "**NOW**")
        assertContains(output, "active task")
    }

    // U-MD-14: YAML frontmatter emitted when page has properties
    @Test
    fun `U-MD-14 YAML frontmatter emitted for page with properties`() {
        val p = page(properties = mapOf("author" to "Tyler"))
        val output = exporter.export(p, emptyList(), emptyMap())
        assertTrue(output.startsWith("---\n"), "Output must start with YAML front-matter delimiter")
        assertContains(output, "title: Test Page")
        assertContains(output, "author: Tyler")
        assertContains(output, "---\n\n")
    }

    // U-MD-15: "id" key excluded from YAML frontmatter
    @Test
    fun `U-MD-15 id key excluded from YAML frontmatter`() {
        val p = page(properties = mapOf("id" to "some-uuid", "tag" to "kotlin"))
        val output = exporter.export(p, emptyList(), emptyMap())
        assertFalse(output.contains("id: some-uuid"), "The 'id' property must not appear in YAML frontmatter")
        assertContains(output, "tag: kotlin")
    }

    // U-MD-16: YAML value containing ":" is double-quoted
    @Test
    fun `U-MD-16 YAML value with colon is double-quoted`() {
        val p = page(properties = mapOf("title" to "key: value"))
        val output = exporter.export(p, emptyList(), emptyMap())
        assertContains(output, "title: \"key: value\"")
    }

    // U-MD-17: YAML value containing "[" is double-quoted
    @Test
    fun `U-MD-17 YAML value with open bracket is double-quoted`() {
        val p = page(properties = mapOf("tags" to "[kotlin, jvm]"))
        val output = exporter.export(p, emptyList(), emptyMap())
        assertContains(output, "tags: \"[kotlin, jvm]\"")
    }

    // U-MD-18: YAML value containing "&" is double-quoted
    @Test
    fun `U-MD-18 YAML value with ampersand is double-quoted`() {
        val p = page(properties = mapOf("author" to "Alice & Bob"))
        val output = exporter.export(p, emptyList(), emptyMap())
        assertContains(output, "author: \"Alice & Bob\"")
    }

    // U-MD-19: property-only blocks (blank content + non-empty properties) are skipped
    @Test
    fun `U-MD-19 property-only blocks are skipped in output`() {
        val propBlock = block(
            content = "",
            properties = mapOf("alias" to "some-alias"),
            uuid = "prop-block",
        )
        val output = exporter.export(page(), listOf(propBlock), emptyMap())
        // Property-only block produces no bullet or paragraph line in the body
        assertEquals("# Test Page\n\n", output)
    }

    // U-MD-20: code fence block content preserved (block with "\n" in content)
    @Test
    fun `U-MD-20 block content with newline is preserved verbatim`() {
        val multilineBlock = Block(
            uuid = BlockUuid("code-block"),
            pageUuid = PageUuid("page-1"),
            content = "line one\nline two",
            level = 0,
            position = "a0",
            createdAt = now,
            updatedAt = now,
        )
        val output = exporter.export(page(), listOf(multilineBlock), emptyMap())
        assertContains(output, "line one")
        assertContains(output, "line two")
    }

    // U-MD-21: journal page uses journalDate.toString() as H1 instead of page name
    @Test
    fun `U-MD-21 journal page heading uses ISO date from journalDate not page name`() {
        val journalPage = page(
            name = "2026_04_13",
            isJournal = true,
            journalDate = LocalDate(2026, 4, 13),
        )
        val output = exporter.export(journalPage, emptyList(), emptyMap())
        assertContains(output, "# 2026-04-13")
        assertFalse(output.contains("# 2026_04_13"), "Underscore journal name must not be used as heading")
    }

    // U-MD-22: namespace page name with "/" in "[[link]]" kept verbatim
    @Test
    fun `U-MD-22 namespace page link with slash kept verbatim`() {
        val output = exporter.export(page(), listOf(block("[[Category/SubPage]]")), emptyMap())
        assertContains(output, "[[Category/SubPage]]")
    }

    // U-MD-23: nested block (level > 0) with embedded newline indents continuation
    // lines one level deeper than the bullet, matching LogseqPageSerializer's convention.
    @Test
    fun `U-MD-23 nested block with newline indents continuation line under bullet`() {
        val parent = block("parent", level = 0, position = "a0", uuid = "parent")
        val child = block(
            content = "line one\nline two",
            level = 1,
            position = "a0",
            uuid = "child",
            parentUuid = "parent",
        )
        val output = exporter.export(page(), listOf(parent, child), emptyMap())
        assertContains(output, "- line one\n  line two\n")
    }
}
