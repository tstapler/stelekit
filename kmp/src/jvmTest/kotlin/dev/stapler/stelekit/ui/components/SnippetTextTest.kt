package dev.stapler.stelekit.ui.components

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnippetTextTest {

    @Test
    fun emTagParsing_stripsTagsAndRecordsRanges() {
        val (text, ranges) = parseEmTags("foo <em>bar</em> baz")
        assertEquals("foo bar baz", text)
        assertEquals(listOf(4..6), ranges)
    }

    @Test
    fun emTagParsing_multipleHighlights() {
        val (text, ranges) = parseEmTags("<em>a</em> b <em>c</em>")
        assertEquals("a b c", text)
        assertEquals(listOf(0..0, 4..4), ranges)
    }

    @Test
    fun emTagParsing_noTags_returnsOriginalText() {
        val (text, ranges) = parseEmTags("plain text")
        assertEquals("plain text", text)
        assertTrue(ranges.isEmpty())
    }
}
