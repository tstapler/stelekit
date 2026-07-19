package dev.stapler.stelekit.parsing

import dev.stapler.stelekit.parsing.ast.HardBreakNode
import dev.stapler.stelekit.parsing.ast.TextNode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Specification-driven tests for [InlineParser.parseWithRanges], specifically the hard-break
 * offset arithmetic in the private `parseSlots()` merge branches (search "Hard line break").
 *
 * `parseWithRanges()` builds each range as `it.start until it.end` (an exclusive-end
 * `IntRange`), so for a range `r`, `text.substring(r.first, r.last + 1)` recovers the exact
 * source slice the node covers.
 *
 * `parseSlots()`'s lexer tokenizes whitespace one character at a time (`WS` tokens are always
 * length 1 — see `Lexer.nextToken()`), so a run of trailing spaces before a hard-break newline
 * always arrives as multiple single-space slots, never as one `TextNode` whose content already
 * ends in `"  "`. That means only the two `else if` merge branches are reachable from real
 * input; the ground truth below was derived by tracing `parseSlots()` directly, not assumed.
 */
class InlineParseWithRangesSpec {

    private fun ranges(input: String): List<Pair<Any, IntRange>> = InlineParser(input).parseWithRanges()

    private fun IntRange.slice(text: String): String = text.substring(first, last + 1)

    // -------------------------------------------------------------------------
    // Hard break: two single-space WS tokens preceding the newline.
    //
    // The immediate previous slot (single space) is blank, and the slot before that
    // (also a single space) ends with " " — this takes the "second-to-last also has
    // trailing space" branch: the second-to-last slot is rewritten to an EMPTY TextNode
    // (its trailing space char is dropped) rather than being removed outright, and the
    // HardBreakNode absorbs the two space characters plus the newline.
    // -------------------------------------------------------------------------

    @Test
    fun `parseWithRanges - two trailing spaces before newline merges into HardBreakNode with an empty TextNode remainder`() {
        val text = "foo  \nbar"
        val result = ranges(text)

        assertEquals(4, result.size, "Expected foo / empty remainder / hard-break / bar")

        val (fooNode, fooRange) = result[0]
        assertEquals(TextNode("foo"), fooNode)
        assertEquals("foo", fooRange.slice(text))

        val (emptyNode, emptyRange) = result[1]
        assertEquals(TextNode(""), emptyNode)
        assertEquals("", emptyRange.slice(text))

        val (breakNode, breakRange) = result[2]
        assertEquals(HardBreakNode, breakNode)
        assertEquals("  \n", breakRange.slice(text), "HardBreakNode range must cover exactly the two spaces + newline")

        val (barNode, barRange) = result[3]
        assertEquals(TextNode("bar"), barNode)
        assertEquals("bar", barRange.slice(text))
    }

    // -------------------------------------------------------------------------
    // Hard break: both preceding slots are blank, but the second-to-last one does NOT
    // end in a literal space char (a tab), so it fails the "endsWith(\" \")" check and
    // falls through to the final "both prev nodes are blank" branch — both blank slots
    // are removed outright (no empty-TextNode remainder) and the HardBreakNode spans
    // from the earlier slot's start straight through the newline.
    // -------------------------------------------------------------------------

    @Test
    fun `parseWithRanges - two separate blank slots (tab then space) merge cleanly into HardBreakNode with no remainder`() {
        val text = "foo\t \nbar"
        val result = ranges(text)

        assertEquals(3, result.size, "Expected foo / hard-break / bar with no leftover blank TextNode")

        val (fooNode, fooRange) = result[0]
        assertEquals(TextNode("foo"), fooNode)
        assertEquals("foo", fooRange.slice(text))

        val (breakNode, breakRange) = result[1]
        assertEquals(HardBreakNode, breakNode)
        assertEquals("\t \n", breakRange.slice(text), "HardBreakNode range must cover exactly the tab + space + newline")

        val (barNode, barRange) = result[2]
        assertEquals(TextNode("bar"), barNode)
        assertEquals("bar", barRange.slice(text))
    }
}
