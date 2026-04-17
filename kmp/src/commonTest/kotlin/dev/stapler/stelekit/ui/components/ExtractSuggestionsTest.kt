package dev.stapler.stelekit.ui.components

import dev.stapler.stelekit.domain.AhoCorasickMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [extractSuggestions] — the function that scans block content for
 * page-name matches while excluding already-linked and structurally-special spans.
 */
class ExtractSuggestionsTest {

    private fun matcher(vararg names: String): AhoCorasickMatcher =
        AhoCorasickMatcher(names.associateBy { it.lowercase() })

    // ── Null / empty cases ────────────────────────────────────────────────────

    @Test
    fun nullMatcher_returnsEmpty() {
        val result = extractSuggestions("Kotlin programming", null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun emptyContent_returnsEmpty() {
        val result = extractSuggestions("", matcher("Kotlin"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun contentWithNoMatches_returnsEmpty() {
        val result = extractSuggestions("plain text no matches here", matcher("Kotlin"))
        assertTrue(result.isEmpty())
    }

    // ── Basic detection ───────────────────────────────────────────────────────

    @Test
    fun singlePageNameInPlainText_isFound() {
        val result = extractSuggestions("I use Kotlin every day", matcher("Kotlin"))
        assertEquals(1, result.size)
        assertEquals("Kotlin", result[0].canonicalName)
    }

    @Test
    fun multiplePageNamesInContent_allFound() {
        val result = extractSuggestions(
            "Kotlin and Python are popular",
            matcher("Kotlin", "Python")
        )
        assertEquals(2, result.size)
        val names = result.map { it.canonicalName }.toSet()
        assertEquals(setOf("Kotlin", "Python"), names)
    }

    @Test
    fun matchSpanPositions_areCorrect() {
        val content = "I use Kotlin today"
        val result = extractSuggestions(content, matcher("Kotlin"))
        assertEquals(1, result.size)
        val span = result[0]
        assertEquals("Kotlin", content.substring(span.start, span.end))
    }

    @Test
    fun caseInsensitiveMatch_returnsCanonicalName() {
        val result = extractSuggestions("i use kotlin every day", matcher("Kotlin"))
        assertEquals(1, result.size)
        assertEquals("Kotlin", result[0].canonicalName)  // canonical casing preserved
    }

    // ── Exclusion zones ───────────────────────────────────────────────────────

    @Test
    fun pageNameInsideWikiLink_isNotSuggested() {
        // "Kotlin" is already linked — should not produce a suggestion
        val result = extractSuggestions("[[Kotlin]] is great", matcher("Kotlin"))
        assertTrue(result.isEmpty(), "Should not suggest already-linked page name")
    }

    @Test
    fun pageNameInsideHashtag_isNotSuggested() {
        val result = extractSuggestions("#Kotlin is great", matcher("Kotlin"))
        assertTrue(result.isEmpty(), "Should not suggest tag text")
    }

    @Test
    fun pageNameInsideInlineCode_isNotSuggested() {
        val result = extractSuggestions("use `Kotlin` syntax", matcher("Kotlin"))
        assertTrue(result.isEmpty(), "Should not suggest text inside inline code")
    }

    @Test
    fun pageNameInMixedContent_onlyPlainTextMatchSuggested() {
        // "Kotlin" appears twice: once linked (excluded), once plain (included)
        val result = extractSuggestions("[[Kotlin]] vs Kotlin", matcher("Kotlin"))
        assertEquals(1, result.size, "Only the plain-text occurrence should be suggested")
        // Verify the span covers the second "Kotlin"
        val content = "[[Kotlin]] vs Kotlin"
        val span = result[0]
        assertEquals("Kotlin", content.substring(span.start, span.end))
    }

    // ── Word boundary enforcement ─────────────────────────────────────────────

    @Test
    fun pageNameAsSubstring_notMatchedDueToWordBoundary() {
        // "the" should not match inside "there" or "other"
        val result = extractSuggestions("there is another thing", matcher("the"))
        assertTrue(result.isEmpty(), "Substring-only match should be excluded by word boundary")
    }

    @Test
    fun pageNameAtWordBoundary_isMatched() {
        val result = extractSuggestions("the dog runs", matcher("the"))
        assertEquals(1, result.size)
    }

    // ── Multi-word page names ─────────────────────────────────────────────────

    @Test
    fun multiWordPageName_foundInContent() {
        val result = extractSuggestions(
            "Review the Meeting Notes from yesterday",
            matcher("Meeting Notes")
        )
        assertEquals(1, result.size)
        assertEquals("Meeting Notes", result[0].canonicalName)
    }

    @Test
    fun multiWordPageNameInsideWikiLink_isExcluded() {
        val result = extractSuggestions(
            "See [[Meeting Notes]] for details",
            matcher("Meeting Notes")
        )
        assertTrue(result.isEmpty())
    }

    // ── Task 1.2 — Multi-word rendering / forward-only cursor fix ─────────────

    @Test
    fun multiWordPatternFound() {
        val result = extractSuggestions(
            "I met Andrew Underwood yesterday",
            matcher("Andrew Underwood")
        )
        assertEquals(1, result.size)
        val span = result[0]
        assertEquals(6, span.start)
        assertEquals(22, span.end)
        assertEquals("Andrew Underwood", span.canonicalName)
    }

    @Test
    fun repeatedTokenTwoMatches() {
        // Core regression: indexOf would return the wrong (first) position for
        // the second occurrence, breaking multi-word span mapping.
        val result = extractSuggestions("New Year New Year", matcher("New Year"))
        assertEquals(2, result.size)
        val sorted = result.sortedBy { it.start }
        assertEquals(0, sorted[0].start)
        assertEquals(8, sorted[0].end)
        assertEquals(9, sorted[1].start)
        assertEquals(17, sorted[1].end)
    }

    // ── Task 3.2 — Additional edge cases ─────────────────────────────────────

    @Test
    fun repeatedMultiWordTwoMatches() {
        val result = extractSuggestions(
            "Andrew Underwood and Andrew Underwood",
            matcher("Andrew Underwood")
        )
        assertEquals(2, result.size)
    }

    @Test
    fun suggestionInsideWikiLinkExcluded() {
        val result = extractSuggestions("[[Python]] is great", matcher("Python"))
        assertTrue(result.isEmpty(), "Should not suggest page name already inside wiki link")
    }

    @Test
    fun suggestionInsideInlineCodeExcluded() {
        val result = extractSuggestions("`Kotlin` rocks", matcher("Kotlin"))
        assertTrue(result.isEmpty(), "Should not suggest page name inside inline code")
    }

    @Test
    fun caseInsensitiveIndexRoundTrip() {
        val result = extractSuggestions("learn KOTLIN daily", matcher("Kotlin"))
        assertEquals(1, result.size)
        assertEquals("Kotlin", result[0].canonicalName)
    }

    @Test
    fun suggestionAfterWikiLink() {
        val result = extractSuggestions("See [[Java]] and Python today", matcher("Python"))
        assertEquals(1, result.size, "Plain-text 'Python' after a wiki link should be suggested")
        assertEquals("Python", result[0].canonicalName)
    }
}
