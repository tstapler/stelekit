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

class PlainTextExporterTest {

    private val now = Clock.System.now()
    private val exporter = PlainTextExporter()

    private fun page(name: String = "Test Page") = Page(
        uuid = PageUuid("page-1"),
        name = name,
        createdAt = now,
        updatedAt = now,
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

    // U-PT-01: empty page → "Test Page\n=========\n\n" (underline matches name length)
    @Test
    fun `U-PT-01 empty page produces title with matching underline`() {
        val output = exporter.export(page(), emptyList(), emptyMap())
        val title = "Test Page"
        val underline = "=".repeat(title.length)
        assertEquals("$title\n$underline\n\n", output)
    }

    // U-PT-02: bold markup stripped ("**bold text**" → "bold text")
    @Test
    fun `U-PT-02 bold markup stripped from output`() {
        val output = exporter.export(page(), listOf(block("**bold text**")), emptyMap())
        assertContains(output, "bold text")
        assertFalse(output.contains("**"), "Bold markers must be stripped")
    }

    // U-PT-03: italic markup stripped
    @Test
    fun `U-PT-03 italic markup stripped from output`() {
        val output = exporter.export(page(), listOf(block("*italic text*")), emptyMap())
        assertContains(output, "italic text")
        assertFalse(output.contains("*"), "Italic markers must be stripped")
    }

    // U-PT-04: strikethrough markup stripped
    @Test
    fun `U-PT-04 strikethrough markup stripped from output`() {
        val output = exporter.export(page(), listOf(block("~~struck text~~")), emptyMap())
        assertContains(output, "struck text")
        assertFalse(output.contains("~~"), "Strikethrough markers must be stripped")
    }

    // U-PT-05: WikiLinkNode renders as target text (no brackets)
    @Test
    fun `U-PT-05 wiki link renders as target text without brackets`() {
        val output = exporter.export(page(), listOf(block("[[Page Name]]")), emptyMap())
        assertContains(output, "Page Name")
        assertFalse(output.contains("[["), "WikiLink open brackets must be stripped")
        assertFalse(output.contains("]]"), "WikiLink close brackets must be stripped")
    }

    // U-PT-06: WikiLinkNode with alias renders as alias
    @Test
    fun `U-PT-06 wiki link with alias renders as alias text`() {
        val output = exporter.export(page(), listOf(block("[[Page Name|display text]]")), emptyMap())
        assertContains(output, "display text")
        assertFalse(output.contains("[["), "WikiLink brackets must not appear in plain text")
    }

    // U-PT-07: hashtag renders without hash character
    @Test
    fun `U-PT-07 hashtag renders without hash character`() {
        val output = exporter.export(page(), listOf(block("#kotlin")), emptyMap())
        assertContains(output, "kotlin")
        assertFalse(output.contains("#kotlin"), "Hash character must be stripped from tag")
    }

    // U-PT-08: highlight markup stripped
    @Test
    fun `U-PT-08 highlight markup stripped from output`() {
        val output = exporter.export(page(), listOf(block("==highlighted text==")), emptyMap())
        assertContains(output, "highlighted text")
        // Verify the block content line has no "==" — skip past the header underline by
        // splitting on the blank line that follows the underline.
        val bodySection = output.substringAfter("\n\n")
        assertFalse(bodySection.contains("=="), "Highlight markers must be stripped from block content")
    }

    // U-PT-09: image node renders as "[Image: alt text]"
    @Test
    fun `U-PT-09 image node renders as bracketed image placeholder`() {
        val output = exporter.export(page(), listOf(block("![my photo](https://example.com/img.png)")), emptyMap())
        assertContains(output, "[Image: my photo]")
    }

    // U-PT-10: macro node renders as "[name: args]"
    @Test
    fun `U-PT-10 macro node renders as bracketed name and args`() {
        val output = exporter.export(page(), listOf(block("{{embed [[My Page]]}}")), emptyMap())
        assertContains(output, "[embed")
    }

    // U-PT-11: top-level blocks separated by blank lines
    @Test
    fun `U-PT-11 top-level blocks are separated by blank lines`() {
        val blocks = listOf(
            block("first block", level = 0, position = "a0", uuid = "block-a"),
            block("second block", level = 0, position = "a1", uuid = "block-b"),
        )
        val output = exporter.export(page(), blocks, emptyMap())
        // A blank line between two top-level blocks means two consecutive newlines between them
        assertContains(output, "first block\n\nsecond block")
    }

    // U-PT-12: nested block indented by 2 spaces per level
    @Test
    fun `U-PT-12 nested block indented by two spaces per level`() {
        val blocks = listOf(
            block("parent", level = 0, position = "a0", uuid = "parent"),
            block("child", level = 1, position = "a0", uuid = "child", parentUuid = "parent"),
            block("grandchild", level = 2, position = "a0", uuid = "grand", parentUuid = "child"),
        )
        val output = exporter.export(page(), blocks, emptyMap())
        assertContains(output, "  child")
        assertContains(output, "    grandchild")
    }

    // U-PT-13: nested blocks not separated by blank lines
    @Test
    fun `U-PT-13 nested blocks are not separated by blank lines`() {
        val blocks = listOf(
            block("parent", level = 0, position = "a0", uuid = "parent"),
            block("child one", level = 1, position = "a0", uuid = "child-a", parentUuid = "parent"),
            block("child two", level = 1, position = "a1", uuid = "child-b", parentUuid = "parent"),
        )
        val output = exporter.export(page(), blocks, emptyMap())
        // Children must appear on consecutive lines with no blank line between them
        assertContains(output, "  child one\n  child two")
    }

    // U-PT-14: BlockRefNode resolved renders inline text
    @Test
    fun `U-PT-14 resolved block ref renders as referenced text`() {
        val output = exporter.export(
            page(),
            listOf(block("((ref-uuid))")),
            resolvedRefs = mapOf("ref-uuid" to "referenced content"),
        )
        assertContains(output, "referenced content")
        assertFalse(output.contains("((ref-uuid))"), "Raw block-ref syntax must be resolved")
    }

    // U-PT-15: BlockRefNode dangling renders as "[block ref]"
    @Test
    fun `U-PT-15 dangling block ref renders as placeholder text`() {
        val output = exporter.export(page(), listOf(block("((missing-uuid))")), resolvedRefs = emptyMap())
        assertContains(output, "[block ref]")
    }

    // U-PT-16: task marker text prefix preserved
    // The PlainTextExporter renders TaskMarkerNode as "${node.marker} " (with trailing space).
    // The original space between marker and text is also preserved as a WS token, so the
    // marker and task text appear separated (e.g. "TODO " followed by " task text").
    @Test
    fun `U-PT-16 task marker preserved as text prefix in plain text`() {
        val output = exporter.export(page(), listOf(block("TODO task text")), emptyMap())
        assertContains(output, "TODO ")
        assertContains(output, "task text")
    }
}
