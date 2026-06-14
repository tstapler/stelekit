package dev.stapler.stelekit.export

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Clock

class HtmlExporterUnitTest {

    private val now = Clock.System.now()

    private fun makePage(
        name: String = "Test Page",
        uuid: String = "html-unit-page",
        isJournal: Boolean = false
    ) = Page(
        uuid = PageUuid(uuid),
        name = name,
        createdAt = now,
        updatedAt = now,
        isJournal = isJournal
    )

    private fun makeBlock(
        uuid: String,
        content: String,
        level: Int,
        position: Int,
        parentUuid: String? = null
    ) = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid("html-unit-page"),
        parentUuid = parentUuid,
        content = content,
        level = level,
        position = position,
        createdAt = now,
        updatedAt = now
    )

    // U-HT-01: empty page → output contains <h1>Test Page</h1> and no <ul> or <li>
    @Test
    fun emptyPageRendersH1AndNoList() {
        val output = HtmlExporter().export(makePage("Test Page"), emptyList())
        assertContains(output, "<h1>Test Page</h1>")
        assertFalse(output.contains("<ul>"), "No <ul> expected for empty page")
        assertFalse(output.contains("<li>"), "No <li> expected for empty page")
    }

    // U-HT-02: output is an HTML fragment — does NOT contain <html>, <head>, <body>
    @Test
    fun outputIsHtmlFragment() {
        val output = HtmlExporter().export(makePage(), emptyList())
        assertFalse(output.contains("<html>"), "Output must not contain <html>")
        assertFalse(output.contains("<head>"), "Output must not contain <head>")
        assertFalse(output.contains("<body>"), "Output must not contain <body>")
    }

    // U-HT-03: top-level block (level=0) with no children → <p>content</p>
    @Test
    fun topLevelBlockRendersAsParagraph() {
        val block = makeBlock("b-p", "Simple paragraph", level = 0, position = 0)
        val output = HtmlExporter().export(makePage(), listOf(block))
        assertContains(output, "<p>")
        assertContains(output, "Simple paragraph")
    }

    // U-HT-04: nested block (level=1) renders inside <ul><li>
    @Test
    fun nestedBlockRendersInList() {
        val parent = makeBlock("b-parent", "Parent", level = 0, position = 0)
        val child = makeBlock("b-child", "Child item", level = 1, position = 0, parentUuid = "b-parent")
        val output = HtmlExporter().export(makePage(), listOf(parent, child))
        assertContains(output, "<ul>")
        assertContains(output, "<li>")
        assertContains(output, "Child item")
    }

    // U-HT-05: deep nesting — <ul> count equals </ul> count
    @Test
    fun deepNestingBalancedUlTags() {
        val blocks = listOf(
            makeBlock("dn-0", "Level 0", level = 0, position = 0),
            makeBlock("dn-1", "Level 1", level = 1, position = 0, parentUuid = "dn-0"),
            makeBlock("dn-2", "Level 2", level = 2, position = 0, parentUuid = "dn-1"),
            makeBlock("dn-3", "Level 3", level = 3, position = 0, parentUuid = "dn-2")
        )
        val output = HtmlExporter().export(makePage(), blocks)
        val openCount = "<ul>".toRegex().findAll(output).count()
        val closeCount = "</ul>".toRegex().findAll(output).count()
        assertEquals(openCount, closeCount, "<ul> and </ul> counts must be equal")
    }

    // U-HT-06: code fence (content with \n) with <script> → output contains &lt;script&gt;
    @Test
    fun codeFenceEscapesScriptTag() {
        val block = makeBlock("b-code", "<script>alert(1)</script>\nmore code", level = 0, position = 0)
        val output = HtmlExporter().export(makePage(), listOf(block))
        assertContains(output, "&lt;script&gt;", message = "Script tag must be HTML-escaped")
        assertFalse(output.contains("<script>"), "Literal <script> must not appear")
    }

    // U-HT-07: code fence emits <pre><code>
    @Test
    fun codeFenceEmitsPreCode() {
        val block = makeBlock("b-pre", "line one\nline two", level = 0, position = 0)
        val output = HtmlExporter().export(makePage(), listOf(block))
        assertContains(output, "<pre><code>")
        assertContains(output, "</code></pre>")
    }

    // U-HT-08: WikiLinkNode renders as <a href="#Page Name">Page Name</a>
    @Test
    fun wikiLinkRendersAsAnchor() {
        val block = makeBlock("b-wl", "[[Page Name]]", level = 0, position = 0)
        val output = HtmlExporter().export(makePage(), listOf(block))
        assertContains(output, "<a href=\"#Page Name\">Page Name</a>")
    }

    // U-HT-09: WikiLinkNode with alias uses alias as link text
    @Test
    fun wikiLinkAliasUsedAsLinkText() {
        val block = makeBlock("b-wl-alias", "[[Target|Display Text]]", level = 0, position = 0)
        val output = HtmlExporter().export(makePage(), listOf(block))
        assertContains(output, "Display Text")
        assertContains(output, "href=\"#Target\"")
        assertFalse(output.contains("[["), "Raw WikiLink syntax must not appear")
    }

    // U-HT-10: XSS: page named "Hello <World>" → href contains &lt;World&gt;
    @Test
    fun xssInWikiLinkTargetIsEscaped() {
        val block = makeBlock("b-xss", "[[Hello <World>]]", level = 0, position = 0)
        val output = HtmlExporter().export(makePage(), listOf(block))
        assertContains(output, "&lt;World&gt;")
        assertFalse(output.contains("<World>"), "Literal < > in link target must be escaped")
    }

    // U-HT-11: bold → <strong>
    @Test
    fun boldRendersAsStrong() {
        val block = makeBlock("b-bold", "**bold text**", level = 0, position = 0)
        val output = HtmlExporter().export(makePage(), listOf(block))
        assertContains(output, "<strong>")
        assertContains(output, "</strong>")
        assertContains(output, "bold text")
    }

    // U-HT-12: italic → <em>
    @Test
    fun italicRendersAsEm() {
        val block = makeBlock("b-italic", "*italic text*", level = 0, position = 0)
        val output = HtmlExporter().export(makePage(), listOf(block))
        assertContains(output, "<em>")
        assertContains(output, "</em>")
        assertContains(output, "italic text")
    }

    // U-HT-13: strikethrough → <s>
    @Test
    fun strikethroughRendersAsS() {
        val block = makeBlock("b-strike", "~~struck~~", level = 0, position = 0)
        val output = HtmlExporter().export(makePage(), listOf(block))
        assertContains(output, "<s>")
        assertContains(output, "</s>")
    }

    // U-HT-14: highlight → <mark>
    @Test
    fun highlightRendersAsMark() {
        val block = makeBlock("b-mark", "==highlighted==", level = 0, position = 0)
        val output = HtmlExporter().export(makePage(), listOf(block))
        assertContains(output, "<mark>")
        assertContains(output, "</mark>")
    }

    // U-HT-15: inline code → <code>code</code>
    @Test
    fun inlineCodeRendersAsCode() {
        val block = makeBlock("b-code-inline", "`some code`", level = 0, position = 0)
        val output = HtmlExporter().export(makePage(), listOf(block))
        assertContains(output, "<code>some code</code>")
    }

    // U-HT-16: TODO block adds class="todo" to container (p or li)
    @Test
    fun todoBlockAddsClassTodo() {
        val block = makeBlock("b-todo", "TODO do the thing", level = 0, position = 0)
        val output = HtmlExporter().export(makePage(), listOf(block))
        assertContains(output, "class=\"todo\"")
    }

    // U-HT-17: DONE block adds class="done" and a checked checkbox
    @Test
    fun doneBlockAddsClassDoneAndCheckedCheckbox() {
        val block = makeBlock("b-done", "DONE finished task", level = 0, position = 0)
        val output = HtmlExporter().export(makePage(), listOf(block))
        assertContains(output, "class=\"done\"")
        assertContains(output, "checked")
    }

    // U-HT-18: NOW renders as <span class="task-marker now">NOW</span>
    @Test
    fun nowRendersAsTaskMarkerSpan() {
        val block = makeBlock("b-now", "NOW in progress", level = 0, position = 0)
        val output = HtmlExporter().export(makePage(), listOf(block))
        assertContains(output, "<span class=\"task-marker now\">NOW</span>")
    }

    // U-HT-19: BlockRefNode resolved → <blockquote data-block-ref="uuid">resolved text</blockquote>
    @Test
    fun resolvedBlockRefRendersAsBlockquote() {
        val block = makeBlock("b-ref-resolved", "((ref-block-uuid))", level = 0, position = 0)
        val output = HtmlExporter().export(
            makePage(),
            listOf(block),
            resolvedRefs = mapOf("ref-block-uuid" to "resolved text")
        )
        assertContains(output, "data-block-ref=\"ref-block-uuid\"")
        assertContains(output, "resolved text")
        assertContains(output, "<blockquote")
        assertContains(output, "</blockquote>")
    }

    // U-HT-20: BlockRefNode dangling → <span class="unresolved-ref">[block ref]</span>
    @Test
    fun danglingBlockRefRendersAsUnresolvedSpan() {
        val block = makeBlock("b-ref-dangling", "((dangling-uuid))", level = 0, position = 0)
        val output = HtmlExporter().export(makePage(), listOf(block))
        assertContains(output, "<span class=\"unresolved-ref\">[block ref]</span>")
    }

    // U-HT-21: image node → <img alt="alt" src="url.png">
    @Test
    fun imageNodeRendersAsImgTag() {
        val block = makeBlock("b-img", "![alt](url.png)", level = 0, position = 0)
        val output = HtmlExporter().export(makePage(), listOf(block))
        assertContains(output, "<img")
        assertContains(output, "alt=\"alt\"")
        assertContains(output, "src=\"url.png\"")
    }

    // U-HT-22: markdown link → <a href="https://example.com">text</a>
    @Test
    fun markdownLinkRendersAsAnchor() {
        val block = makeBlock("b-mdlink", "[text](https://example.com)", level = 0, position = 0)
        val output = HtmlExporter().export(makePage(), listOf(block))
        assertContains(output, "<a href=\"https://example.com\">text</a>")
    }

    // U-HT-23: 6-level nesting — <ul> and </ul> counts are equal
    @Test
    fun sixLevelNestingBalancedUlTags() {
        val blocks = listOf(
            makeBlock("l0", "Level 0", level = 0, position = 0),
            makeBlock("l1", "Level 1", level = 1, position = 0, parentUuid = "l0"),
            makeBlock("l2", "Level 2", level = 2, position = 0, parentUuid = "l1"),
            makeBlock("l3", "Level 3", level = 3, position = 0, parentUuid = "l2"),
            makeBlock("l4", "Level 4", level = 4, position = 0, parentUuid = "l3"),
            makeBlock("l5", "Level 5", level = 5, position = 0, parentUuid = "l4"),
            makeBlock("l6", "Level 6", level = 6, position = 0, parentUuid = "l5"),
            makeBlock("back", "Back to top", level = 0, position = 1)
        )
        val output = HtmlExporter().export(makePage(), blocks)
        val openCount = "<ul>".toRegex().findAll(output).count()
        val closeCount = "</ul>".toRegex().findAll(output).count()
        assertEquals(openCount, closeCount, "<ul> and </ul> counts must be equal for 6-level nesting")
    }
}
