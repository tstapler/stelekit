package dev.stapler.stelekit.ui.components

import androidx.compose.ui.graphics.Color
import dev.stapler.stelekit.domain.AhoCorasickMatcher
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [parseMarkdownWithStyling] — specifically the PAGE_SUGGESTION_TAG annotations
 * produced by the multi-word-aware run-merging path in renderNodes().
 *
 * Annotation item format: "canonicalName|origStart|origEnd"
 */
class ParseMarkdownWithStylingTest {

    private fun matcher(vararg names: String): AhoCorasickMatcher =
        AhoCorasickMatcher(names.associateBy { it.lowercase() })

    private fun annotatedSuggestions(text: String, matcher: AhoCorasickMatcher) =
        parseMarkdownWithStyling(
            text = text,
            linkColor = Color.Blue,
            textColor = Color.Black,
            suggestionSpans = extractSuggestions(text, matcher),
        ).getStringAnnotations(PAGE_SUGGESTION_TAG, 0, text.length)

    private fun suggestionNamesIn(text: String, matcher: AhoCorasickMatcher) =
        annotatedSuggestions(text, matcher).map { it.item.substringBefore("|") }

    // ── Single-word baseline ──────────────────────────────────────────────────

    @Test
    fun singleWordPageName_isHighlighted() {
        val result = annotatedSuggestions("I use Kotlin daily", matcher("Kotlin"))
        assertEquals(1, result.size)
        assertEquals("Kotlin", result[0].item.substringBefore("|"))
    }

    @Test
    fun noMatcher_noAnnotations() {
        val result = parseMarkdownWithStyling(
            text = "I use Kotlin daily",
            linkColor = Color.Blue,
            textColor = Color.Black,
        ).getStringAnnotations(PAGE_SUGGESTION_TAG, 0, 100)
        assertTrue(result.isEmpty())
    }

    // ── Multi-word core cases ─────────────────────────────────────────────────

    @Test
    fun multiWordPageName_isHighlighted() {
        val result = annotatedSuggestions(
            "Review the Meeting Notes from yesterday",
            matcher("Meeting Notes"),
        )
        assertEquals(1, result.size, "Multi-word page name should produce exactly one annotation")
        assertEquals("Meeting Notes", result[0].item.substringBefore("|"))
    }

    @Test
    fun multiWordPageName_spanCoversFullPhrase() {
        val content = "Review the Meeting Notes from yesterday"
        val result = annotatedSuggestions(content, matcher("Meeting Notes"))
        assertEquals(1, result.size)
        val span = result[0]
        assertEquals("Meeting Notes", content.substring(span.start, span.end))
    }

    @Test
    fun multiWordAtStartOfContent_isHighlighted() {
        val content = "Meeting Notes was helpful"
        val result = annotatedSuggestions(content, matcher("Meeting Notes"))
        assertEquals(1, result.size)
        assertEquals("Meeting Notes", content.substring(result[0].start, result[0].end))
    }

    @Test
    fun multiWordAtEndOfContent_isHighlighted() {
        val content = "I reviewed Meeting Notes"
        val result = annotatedSuggestions(content, matcher("Meeting Notes"))
        assertEquals(1, result.size)
        assertEquals("Meeting Notes", content.substring(result[0].start, result[0].end))
    }

    @Test
    fun threeWordPageName_isHighlighted() {
        val content = "See New Year Summary for context"
        val result = annotatedSuggestions(content, matcher("New Year Summary"))
        assertEquals(1, result.size)
        assertEquals("New Year Summary", content.substring(result[0].start, result[0].end))
    }

    @Test
    fun repeatedMultiWordPageName_bothHighlighted() {
        val result = annotatedSuggestions(
            "Andrew Underwood and Andrew Underwood",
            matcher("Andrew Underwood"),
        )
        assertEquals(2, result.size)
    }

    @Test
    fun repeatedMultiWord_offsetsAreCorrect() {
        val content = "New Year New Year"
        val result = annotatedSuggestions(content, matcher("New Year"))
        assertEquals(2, result.size)
        val sorted = result.sortedBy { it.start }
        assertEquals("New Year", content.substring(sorted[0].start, sorted[0].end))
        assertEquals("New Year", content.substring(sorted[1].start, sorted[1].end))
        assertEquals(0, sorted[0].start)
        assertEquals(8, sorted[0].end)
        assertEquals(9, sorted[1].start)
        assertEquals(17, sorted[1].end)
    }

    // ── Annotation metadata correctness ──────────────────────────────────────

    @Test
    fun annotationItem_encodesOriginalOffsets() {
        val content = "Review the Meeting Notes from yesterday"
        val result = annotatedSuggestions(content, matcher("Meeting Notes"))
        assertEquals(1, result.size)
        val parts = result[0].item.split("|")
        val origStart = parts[1].toInt()
        val origEnd = parts[2].toInt()
        assertEquals("Meeting Notes", content.substring(origStart, origEnd))
    }

    @Test
    fun annotatedStringPositions_matchOriginalOffsets() {
        // The AnnotatedString span start/end should agree with the encoded offsets in the item
        val content = "some prefix Meeting Notes suffix"
        val result = annotatedSuggestions(content, matcher("Meeting Notes"))
        assertEquals(1, result.size)
        val ann = result[0]
        val parts = ann.item.split("|")
        assertEquals(parts[1].toInt(), ann.start, "AnnotatedString start should match encoded origStart")
        assertEquals(parts[2].toInt(), ann.end, "AnnotatedString end should match encoded origEnd")
    }

    // ── Markup interruption ───────────────────────────────────────────────────

    @Test
    fun multiWordInsideWikiLink_notHighlighted() {
        val result = annotatedSuggestions(
            "See [[Meeting Notes]] for details",
            matcher("Meeting Notes"),
        )
        assertTrue(result.isEmpty(), "Page name inside wiki link should not be highlighted")
    }

    @Test
    fun boldInterrupts_multiWordNotHighlighted() {
        // "**Meeting** Notes" — the bold node breaks the TextNode run, so the
        // full phrase cannot be matched and should produce no suggestion.
        val result = annotatedSuggestions("**Meeting** Notes", matcher("Meeting Notes"))
        assertTrue(result.isEmpty(), "Bold markup breaking a multi-word phrase should suppress it")
    }

    @Test
    fun inlineCodeInterrupts_multiWordNotHighlighted() {
        val result = annotatedSuggestions("`Meeting` Notes", matcher("Meeting Notes"))
        assertTrue(result.isEmpty(), "Inline code breaking a multi-word phrase should suppress it")
    }

    @Test
    fun tagInterrupts_multiWordNotHighlighted() {
        // "Meeting #tag Notes" — the #tag node breaks the run
        val result = annotatedSuggestions("Meeting #tag Notes", matcher("Meeting Notes"))
        assertTrue(result.isEmpty(), "Tag between words should suppress the multi-word match")
    }

    @Test
    fun multiWordAfterWikiLink_isHighlighted() {
        // Text run restarts after [[Java]], so "Meeting Notes" further in the string should match.
        val result = annotatedSuggestions(
            "See [[Java]] and Meeting Notes",
            matcher("Meeting Notes"),
        )
        assertEquals(1, result.size)
        assertEquals("Meeting Notes", result[0].item.substringBefore("|"))
    }

    @Test
    fun multiWordBeforeWikiLink_isHighlighted() {
        val content = "Meeting Notes see [[Java]]"
        val result = annotatedSuggestions(content, matcher("Meeting Notes"))
        assertEquals(1, result.size)
        assertEquals("Meeting Notes", content.substring(result[0].start, result[0].end))
    }

    // ── Case-insensitivity and canonical form ─────────────────────────────────

    @Test
    fun multiWordLowercase_returnsCanonicalName() {
        val result = annotatedSuggestions("see meeting notes today", matcher("Meeting Notes"))
        assertEquals(1, result.size)
        assertEquals("Meeting Notes", result[0].item.substringBefore("|"))
    }

    // ── Word-boundary enforcement on multi-word ───────────────────────────────

    @Test
    fun multiWordAsSubstring_notMatched() {
        // "aMeeting Notes" — "Meeting" is not at a word boundary, so no match
        val result = annotatedSuggestions("aMeeting Notes was it", matcher("Meeting Notes"))
        assertTrue(result.isEmpty(), "Multi-word name not at word boundary should not match")
    }

    // ── Multiple matchers ─────────────────────────────────────────────────────

    @Test
    fun singleAndMultiWord_bothHighlighted() {
        val content = "Kotlin and Meeting Notes are topics"
        val result = annotatedSuggestions(content, matcher("Kotlin", "Meeting Notes"))
        assertEquals(2, result.size)
        val names = result.map { it.item.substringBefore("|") }.toSet()
        assertEquals(setOf("Kotlin", "Meeting Notes"), names)
    }

    @Test
    fun overlappingCandidates_longestWins() {
        // If both "Meeting" and "Meeting Notes" are pages, the longer match wins
        val content = "Review Meeting Notes"
        val result = annotatedSuggestions(content, matcher("Meeting", "Meeting Notes"))
        assertEquals(1, result.size)
        assertEquals("Meeting Notes", result[0].item.substringBefore("|"))
    }
}
