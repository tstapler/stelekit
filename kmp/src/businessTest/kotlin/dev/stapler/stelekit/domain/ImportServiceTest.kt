package dev.stapler.stelekit.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportServiceTest {

    private fun matcher(vararg names: String): AhoCorasickMatcher =
        AhoCorasickMatcher(names.associateBy { it.lowercase() })

    // ── 1. Single-word match ──────────────────────────────────────────────────

    @Test
    fun singleWordMatch_producesWikiLink() {
        val result = ImportService.scan("I use Kotlin daily", matcher("Kotlin"))
        assertTrue(result.linkedText.contains("[[Kotlin]]"), "Expected [[Kotlin]] in output")
        assertEquals(listOf("Kotlin"), result.matchedPageNames)
    }

    // ── 2. Multi-word match ───────────────────────────────────────────────────

    @Test
    fun multiWordMatch_producesWikiLink() {
        val result = ImportService.scan("machine learning is great", matcher("machine learning"))
        assertTrue(result.linkedText.contains("[[machine learning]]"), "Expected [[machine learning]] in output")
        assertEquals(listOf("machine learning"), result.matchedPageNames)
    }

    // ── 3. Multiple matches in one text ──────────────────────────────────────

    @Test
    fun multipleMatchesInText_allLinked() {
        val result = ImportService.scan("Java and Kotlin are great", matcher("Java", "Kotlin"))
        assertTrue(result.linkedText.contains("[[Java]]"), "Expected [[Java]]")
        assertTrue(result.linkedText.contains("[[Kotlin]]"), "Expected [[Kotlin]]")
        assertEquals(2, result.matchedPageNames.size)
        assertTrue("Java" in result.matchedPageNames)
        assertTrue("Kotlin" in result.matchedPageNames)
    }

    // ── 4. Word boundary: "the" should NOT match inside "there" ──────────────

    @Test
    fun wordBoundary_doesNotMatchSubstring() {
        val result = ImportService.scan("there is another thing", matcher("the"))
        assertFalse(result.linkedText.contains("[[the]]"), "Should not link 'the' inside 'there'")
        assertTrue(result.matchedPageNames.isEmpty())
    }

    @Test
    fun wordBoundary_matchesExactWord() {
        val result = ImportService.scan("the dog runs", matcher("the"))
        assertTrue(result.linkedText.contains("[[the]]"), "Should link exact word 'the'")
        assertEquals(listOf("the"), result.matchedPageNames)
    }

    // ── 6. Empty text ─────────────────────────────────────────────────────────

    @Test
    fun emptyText_returnsEmptyResult() {
        val result = ImportService.scan("", matcher("Kotlin"))
        assertEquals("", result.linkedText)
        assertTrue(result.matchedPageNames.isEmpty())
    }

    // ── 7. No matches ─────────────────────────────────────────────────────────

    @Test
    fun noMatches_returnsOriginalText() {
        val text = "plain text with no page names"
        val result = ImportService.scan(text, matcher("Kotlin", "Java"))
        assertEquals(text, result.linkedText)
        assertTrue(result.matchedPageNames.isEmpty())
    }

    // ── 9. Deduplication of matchedPageNames ─────────────────────────────────

    @Test
    fun deduplication_sameNameAppearsOnceInMatchedPageNames() {
        val text = "Kotlin rocks. I love Kotlin. Kotlin is great."
        val result = ImportService.scan(text, matcher("Kotlin"))
        // All three should be linked in the text
        assertEquals(3, "\\[\\[Kotlin\\]\\]".toRegex().findAll(result.linkedText).count(),
            "All three occurrences should be linked")
        // But matchedPageNames should only contain Kotlin once
        assertEquals(1, result.matchedPageNames.size, "matchedPageNames should deduplicate")
        assertEquals("Kotlin", result.matchedPageNames[0])
    }

}
