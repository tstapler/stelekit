// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HtmlBlockConverterTest {
    private fun convert(html: String) = assertNotNull(HtmlBlockConverter.convert(html), "expected HTML to be recognised: $html")

    @Test
    fun plainTextIsNotHtml() = runTest {
        assertNull(HtmlBlockConverter.convert("just some text"))
        assertNull(HtmlBlockConverter.convert(""))
        checkAll(Arb.string().filter { !it.trimStart().startsWith("<") }) { text ->
            assertNull(HtmlBlockConverter.convert(text), "[$text]")
        }
    }

    @Test
    fun leadingWhitespaceBeforeTheFirstTagStillCountsAsHtml() {
        assertEquals(listOf(RawBlock("hi", 0)), convert("  \n <p>hi</p>"))
    }

    @Test
    fun headingsKeepTheirMarkdownDepthAtLevelZero() {
        val html = (1..6).joinToString("") { "<h$it>Title $it</h$it>" }

        val expected = (1..6).map { RawBlock("${"#".repeat(it)} Title $it", level = 0) }

        assertEquals(expected, convert(html))
    }

    @Test
    fun paragraphsFlattenInlineMarkupAndDropBlankOnes() {
        val blocks = convert("<p>Hello <b>big</b>   world</p><p>   </p><p>second</p>")

        assertEquals(listOf(RawBlock("Hello big world", 0), RawBlock("second", 0)), blocks)
    }

    @Test
    fun blockquoteIsPrefixedAndCodeIsFenced() {
        val blocks = convert("<blockquote>wise words</blockquote><pre>val x = 1</pre><code>inline</code>")

        assertEquals(
            listOf(
                RawBlock("> wise words", 0),
                RawBlock("```\nval x = 1\n```", 0),
                RawBlock("```\ninline\n```", 0),
            ),
            blocks,
        )
    }

    @Test
    fun unknownContainerBecomesOneBlockOfItsText() {
        assertEquals(listOf(RawBlock("a b", 0)), convert("<div>a <span>b</span></div>"))
    }

    @Test
    fun topLevelListItemsAreLevelOneAndNestedItemsLevelTwo() {
        val html = "<ul><li>a<ul><li>b<ul><li>c</li></ul></li></ul></li><li>d</li></ul>"

        assertEquals(
            listOf(RawBlock("a", 1), RawBlock("b", 2), RawBlock("c", 2), RawBlock("d", 1)),
            convert(html),
        )
    }

    @Test
    fun orderedAndUnorderedListsNestTogether() {
        val html = "<ol><li>one<ul><li>inner</li></ul></li><li>two</li></ol>"

        assertEquals(listOf(RawBlock("one", 1), RawBlock("inner", 2), RawBlock("two", 1)), convert(html))
    }

    @Test
    fun emptyListItemsAreSkipped() {
        assertEquals(listOf(RawBlock("kept", 1)), convert("<ul><li> </li><li>kept</li></ul>"))
    }

    @Test
    fun mixedDocumentKeepsSourceOrder() {
        val html = "<h2>Plan</h2><p>intro</p><ul><li>step</li></ul><blockquote>note</blockquote>"

        assertEquals(
            listOf(RawBlock("## Plan", 0), RawBlock("intro", 0), RawBlock("step", 1), RawBlock("> note", 0)),
            convert(html),
        )
    }

    @Test
    fun htmlWithNoTextualContentYieldsAnEmptyListNotNull() {
        assertEquals(emptyList(), convert("<p></p><div> </div>"))
    }
}
