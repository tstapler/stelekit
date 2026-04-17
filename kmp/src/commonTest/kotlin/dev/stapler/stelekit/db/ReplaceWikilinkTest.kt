package dev.stapler.stelekit.db

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the [replaceWikilink] utility function.
 *
 * Covers simple links, aliased links, multiple occurrences, no-op cases,
 * regex-special characters in page names, and partial-name non-matches.
 */
class ReplaceWikilinkTest {

    // ---- simple [[OldName]] ----

    @Test
    fun simple_link_is_replaced() {
        val result = replaceWikilink("See [[Alpha]] for details", "Alpha", "Beta")
        assertEquals("See [[Beta]] for details", result)
    }

    @Test
    fun multiple_simple_links_are_all_replaced() {
        val result = replaceWikilink("[[Alpha]] and [[Alpha]] again", "Alpha", "Beta")
        assertEquals("[[Beta]] and [[Beta]] again", result)
    }

    // ---- aliased [[OldName|Display]] ----

    @Test
    fun aliased_link_preserves_alias() {
        val result = replaceWikilink("See [[Alpha|the alpha page]] here", "Alpha", "Beta")
        assertEquals("See [[Beta|the alpha page]] here", result)
    }

    @Test
    fun mixed_simple_and_aliased_links_are_both_replaced() {
        val result = replaceWikilink("[[Alpha]] and [[Alpha|alias]]", "Alpha", "Beta")
        assertEquals("[[Beta]] and [[Beta|alias]]", result)
    }

    // ---- no-op cases ----

    @Test
    fun unrelated_link_is_not_changed() {
        val result = replaceWikilink("See [[Gamma]] for details", "Alpha", "Beta")
        assertEquals("See [[Gamma]] for details", result)
    }

    @Test
    fun partial_name_is_not_matched() {
        // [[AlphaBeta]] must NOT be changed when renaming Alpha → Beta
        val result = replaceWikilink("See [[AlphaBeta]]", "Alpha", "Beta")
        assertEquals("See [[AlphaBeta]]", result)
    }

    @Test
    fun content_with_no_links_is_unchanged() {
        val result = replaceWikilink("Plain text content", "Alpha", "Beta")
        assertEquals("Plain text content", result)
    }

    @Test
    fun empty_content_is_unchanged() {
        val result = replaceWikilink("", "Alpha", "Beta")
        assertEquals("", result)
    }

    // ---- regex-special characters in page name ----

    @Test
    fun page_name_with_parentheses_is_escaped() {
        val result = replaceWikilink("See [[A (1)]] here", "A (1)", "A (2)")
        assertEquals("See [[A (2)]] here", result)
    }

    @Test
    fun page_name_with_dot_is_escaped() {
        val result = replaceWikilink("See [[v1.0]] here", "v1.0", "v2.0")
        assertEquals("See [[v2.0]] here", result)
    }

    @Test
    fun page_name_with_brackets_is_escaped() {
        val result = replaceWikilink("See [[A[1]]] here", "A[1]", "B[1]")
        assertEquals("See [[B[1]]] here", result)
    }
}
