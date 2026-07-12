package dev.stapler.stelekit.parsing

import dev.stapler.stelekit.parsing.ast.HardBreakNode
import dev.stapler.stelekit.parsing.ast.TextNode
import dev.stapler.stelekit.parsing.ast.WikiLinkNode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [InlineParser.parseWithSpans] — each top-level node's raw `[start, end)`
 * span must exactly match where it sits in the source string, since downstream
 * consumers (MarkdownEngine's suggestion offsets, link-insertion splicing) rely on
 * the span instead of re-deriving it via `indexOf`.
 */
class InlineParserSpansTest {

    private fun spansOf(input: String) = InlineParser(input).parseWithSpans()

    @Test
    fun `span of each node matches its raw source text`() {
        val content = "hello [[Page]] world"
        val result = spansOf(content)
        for ((node, span) in result) {
            if (node is TextNode) {
                assertEquals(node.content, content.substring(span.first, span.last + 1))
            }
        }
        val link = result.first { it.first is WikiLinkNode }
        assertEquals("[[Page]]", content.substring(link.second.first, link.second.last + 1))
    }

    @Test
    fun `text node immediately following identical wikilink target spans the copy outside the brackets`() {
        // Regression for the "insert out of order with links" bug: the text node's span
        // must point at the "abc" after "]]", not the "abc" inside "[[abc]]".
        val content = "[[abc]]abc"
        val result = spansOf(content)
        val textAfterLink = result.first { it.first is TextNode }
        assertEquals(7, textAfterLink.second.first)
        assertEquals(10, textAfterLink.second.last + 1)
        assertEquals("abc", content.substring(textAfterLink.second.first, textAfterLink.second.last + 1))
    }

    @Test
    fun `parse and parseWithSpans agree on node sequence`() {
        val content = "**bold** and [[Link]] and #tag plain"
        assertEquals(InlineParser(content).parse(), InlineParser(content).parseWithSpans().map { it.first })
    }

    @Test
    fun `hard break span adjustment does not corrupt later node offsets`() {
        // Two trailing spaces before a newline collapse into a HardBreakNode; the spans of
        // every node emitted *after* it must still line up with the real source content.
        val content = "line one  \nline two"
        val result = spansOf(content)
        val hardBreakIndex = result.indexOfFirst { it.first is HardBreakNode }
        assertEquals(true, hardBreakIndex >= 0, "Expected a HardBreakNode")
        for ((node, span) in result.drop(hardBreakIndex + 1)) {
            if (node is TextNode) {
                assertEquals(node.content, content.substring(span.first, span.last + 1))
            }
        }
    }
}
