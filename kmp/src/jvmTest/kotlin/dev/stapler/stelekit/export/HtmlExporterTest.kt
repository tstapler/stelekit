package dev.stapler.stelekit.export

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.time.Clock

class HtmlExporterTest {

    private val now = Clock.System.now()

    private fun makePage(name: String = "Test Page") = Page(
        uuid = "html-page",
        name = name,
        createdAt = now,
        updatedAt = now
    )

    private fun makeBlock(
        uuid: String,
        content: String,
        level: Int,
        position: Int,
        parentUuid: String? = null
    ) = Block(
        uuid = uuid,
        pageUuid = "html-page",
        parentUuid = parentUuid,
        content = content,
        level = level,
        position = position,
        createdAt = now,
        updatedAt = now
    )

    // -------------------------------------------------------------------------
    // Test 1: Deeply nested blocks produce balanced <ul> tags
    // -------------------------------------------------------------------------

    @Test
    fun testDeeplyNestedBlocksProduceBalancedUlTags() {
        // Build a 6-level-deep chain, then a top-level block afterwards to force list close
        val blocks = listOf(
            makeBlock("dn-0", "Level 0 root", level = 0, position = 0),
            makeBlock("dn-1", "Level 1", level = 1, position = 0, parentUuid = "dn-0"),
            makeBlock("dn-2", "Level 2", level = 2, position = 0, parentUuid = "dn-1"),
            makeBlock("dn-3", "Level 3", level = 3, position = 0, parentUuid = "dn-2"),
            makeBlock("dn-4", "Level 4", level = 4, position = 0, parentUuid = "dn-3"),
            makeBlock("dn-5", "Level 5", level = 5, position = 0, parentUuid = "dn-4"),
            makeBlock("dn-back", "Back to level 0", level = 0, position = 1)
        )

        val output = HtmlExporter().export(makePage(), blocks)

        val openCount = "<ul>".toRegex().findAll(output).count()
        val closeCount = "</ul>".toRegex().findAll(output).count()
        assertEquals(openCount, closeCount, "Every <ul> must have a matching </ul>")
    }

    // -------------------------------------------------------------------------
    // Test 2: Mixed task markers render correct checkbox / class attributes
    // -------------------------------------------------------------------------

    @Test
    fun testMixedTaskMarkers() {
        val blocks = listOf(
            makeBlock("task-todo", "TODO do something", level = 0, position = 0),
            makeBlock("task-done", "DONE finished", level = 0, position = 1),
            makeBlock("task-now", "NOW in progress", level = 0, position = 2)
        )

        val output = HtmlExporter().export(makePage(), blocks)

        // TODO block: checkbox without checked
        assertContains(output, "type=\"checkbox\"", message = "TODO must render a checkbox input")
        // The TODO checkbox must not be checked — isolate the todo <p> by CSS class
        val todoSection = output.substringAfter("class=\"todo\"").substringBefore("class=\"done\"")
        assertFalse(
            todoSection.contains("checked"),
            "TODO checkbox must not have checked attribute"
        )

        // DONE block: checkbox with checked
        assertContains(output, "checked", message = "DONE must render a checked checkbox")

        // NOW block: task-marker class with lowercase marker name
        assertContains(output, "task-marker", message = "NOW must use task-marker CSS class")
        assertContains(output, "now", message = "NOW task-marker class must include lowercase marker")
    }

    // -------------------------------------------------------------------------
    // Test 3: Page with no blocks renders empty article without crash
    // -------------------------------------------------------------------------

    @Test
    fun testPageWithNoBlocks() {
        val output = HtmlExporter().export(makePage("Empty Page"), emptyList())

        assertContains(output, "<h1>", message = "Output must contain <h1> heading")
        assertContains(output, "</article>", message = "Output must contain closing </article> tag")
        assertFalse(output.contains("<ul>"), "Output must not contain <ul> when there are no blocks")
    }

    // -------------------------------------------------------------------------
    // Test 4: XSS in code fence is escaped
    // -------------------------------------------------------------------------

    @Test
    fun testXssInCodeFence() {
        // A block with a newline triggers the code-fence (<pre><code>) path
        val xssContent = "<script>alert('xss')</script>\nmore code"
        val block = makeBlock("xss-block", xssContent, level = 0, position = 0)

        val output = HtmlExporter().export(makePage(), listOf(block))

        assertContains(output, "&lt;script&gt;", message = "Script open tag must be escaped")
        assertFalse(output.contains("<script>"), "Literal <script> tag must not appear")
    }

    // -------------------------------------------------------------------------
    // Test 5: WikiLinks render as anchor elements
    // -------------------------------------------------------------------------

    @Test
    fun testWikiLinksRenderAsAnchors() {
        val block = makeBlock("wl-block", "[[Page Name]]", level = 0, position = 0)

        val output = HtmlExporter().export(makePage(), listOf(block))

        assertContains(output, "<a", message = "WikiLink must render as an anchor tag")
        assertContains(output, "Page Name", message = "WikiLink display text must be present")
        assertFalse(output.contains("[["), "Raw WikiLink syntax must not appear in output")
    }
}
