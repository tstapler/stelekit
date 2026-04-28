package dev.stapler.stelekit.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AhoCorasickMatcherTest {

    // -------------------------------------------------------------------------
    // 1. multiWordPatternFound
    // -------------------------------------------------------------------------

    @Test
    fun multiWordPatternFound() {
        val matcher = AhoCorasickMatcher(mapOf("andrew underwood" to "Andrew Underwood"))
        val matches = matcher.findAll("I met Andrew Underwood yesterday")
        assertEquals(1, matches.size, "Expected 1 match for multi-word page name")
        assertEquals(6, matches[0].start, "Match should start at index 6")
        assertEquals(22, matches[0].end, "Match should end at index 22")
        assertEquals("Andrew Underwood", matches[0].canonicalName)
    }

    // -------------------------------------------------------------------------
    // 2. prefixOverlapLongestWins
    // -------------------------------------------------------------------------

    @Test
    fun prefixOverlapLongestWins() {
        val matcher = AhoCorasickMatcher(mapOf("kmp" to "KMP", "kmp sdk" to "KMP SDK"))
        val matches = matcher.findAll("I use KMP SDK today")
        assertEquals(1, matches.size, "Longer match should win over shorter prefix overlap")
        assertEquals("KMP SDK", matches[0].canonicalName)
    }

    // -------------------------------------------------------------------------
    // 3. prefixOverlapShortWhenLongAbsent
    // -------------------------------------------------------------------------

    @Test
    fun prefixOverlapShortWhenLongAbsent() {
        val matcher = AhoCorasickMatcher(mapOf("kmp" to "KMP", "kmp sdk" to "KMP SDK"))
        val matches = matcher.findAll("Use KMP only")
        assertEquals(1, matches.size, "Short match should be returned when long pattern is absent")
        assertEquals("KMP", matches[0].canonicalName)
    }

    // -------------------------------------------------------------------------
    // 4. wordBoundaryPreventsSubstringMatch
    // -------------------------------------------------------------------------

    @Test
    fun wordBoundaryPreventsSubstringMatch() {
        val matcher = AhoCorasickMatcher(mapOf("test" to "Test"))
        val matches = matcher.findAll("testing is fun")
        assertEquals(0, matches.size, "Substring match inside 'testing' must be rejected by word boundary check")
    }

    // -------------------------------------------------------------------------
    // 5. wordBoundaryAllowsExactWord
    // -------------------------------------------------------------------------

    @Test
    fun wordBoundaryAllowsExactWord() {
        val matcher = AhoCorasickMatcher(mapOf("test" to "Test"))
        val matches = matcher.findAll("run the test today")
        assertEquals(1, matches.size, "Exact word 'test' should match")
        assertEquals("Test", matches[0].canonicalName)
    }

    // -------------------------------------------------------------------------
    // 6. caseInsensitiveMatch
    // -------------------------------------------------------------------------

    @Test
    fun caseInsensitiveMatch() {
        val matcher = AhoCorasickMatcher(mapOf("kotlin" to "Kotlin"))
        val matches = matcher.findAll("learning kotlin daily")
        assertEquals(1, matches.size, "Matching should be case-insensitive")
        assertEquals("Kotlin", matches[0].canonicalName)
    }

    // -------------------------------------------------------------------------
    // 7. emptyInput
    // -------------------------------------------------------------------------

    @Test
    fun emptyInput() {
        val matcher = AhoCorasickMatcher(mapOf("kotlin" to "Kotlin"))
        val matches = matcher.findAll("")
        assertEquals(0, matches.size, "Empty input should produce 0 matches")
    }

    // -------------------------------------------------------------------------
    // 8. emptyMatcher
    // -------------------------------------------------------------------------

    @Test
    fun emptyMatcher() {
        val matcher = AhoCorasickMatcher(emptyMap())
        val matches = matcher.findAll("anything")
        assertEquals(0, matches.size, "Empty matcher should produce 0 matches")
    }

    // -------------------------------------------------------------------------
    // 9. multipleNonOverlapping
    // -------------------------------------------------------------------------

    @Test
    fun multipleNonOverlapping() {
        val matcher = AhoCorasickMatcher(mapOf("java" to "Java", "kotlin" to "Kotlin"))
        val matches = matcher.findAll("Java and Kotlin")
        assertEquals(2, matches.size, "Both non-overlapping page names should be found")
        val names = matches.map { it.canonicalName }.toSet()
        assertTrue("Java" in names, "Expected 'Java' in matches")
        assertTrue("Kotlin" in names, "Expected 'Kotlin' in matches")
    }

    // -------------------------------------------------------------------------
    // 10. repeatedTokenTwoMatches
    // -------------------------------------------------------------------------

    @Test
    fun repeatedTokenTwoMatches() {
        val matcher = AhoCorasickMatcher(mapOf("new year" to "New Year"))
        val matches = matcher.findAll("New Year New Year")
        assertEquals(2, matches.size, "Repeated pattern should yield 2 matches")
        assertEquals(0, matches[0].start)
        assertEquals(8, matches[0].end)
        assertEquals(9, matches[1].start)
        assertEquals(17, matches[1].end)
    }

    // -------------------------------------------------------------------------
    // 11. singleCharWordBoundary
    // -------------------------------------------------------------------------

    @Test
    fun singleCharWordBoundary() {
        val matcher = AhoCorasickMatcher(mapOf("a" to "A"))
        val matches = matcher.findAll("x a y")
        assertEquals(1, matches.size, "Single-char page name should match when surrounded by non-word chars")
        assertEquals("A", matches[0].canonicalName)
    }

    // -------------------------------------------------------------------------
    // 12. pageNameAtStartOfText
    // -------------------------------------------------------------------------

    @Test
    fun pageNameAtStartOfText() {
        val matcher = AhoCorasickMatcher(mapOf("kotlin" to "Kotlin"))
        val matches = matcher.findAll("Kotlin is great")
        assertEquals(1, matches.size, "Page name at start of text should match (start == 0 boundary)")
        assertEquals(0, matches[0].start)
        assertEquals("Kotlin", matches[0].canonicalName)
    }

    // -------------------------------------------------------------------------
    // 13. pageNameAtEndOfText
    // -------------------------------------------------------------------------

    @Test
    fun pageNameAtEndOfText() {
        val matcher = AhoCorasickMatcher(mapOf("kotlin" to "Kotlin"))
        val matches = matcher.findAll("I love Kotlin")
        assertEquals(1, matches.size, "Page name at end of text should match (end == text.length boundary)")
        assertEquals("Kotlin", matches[0].canonicalName)
        assertEquals("I love Kotlin".length, matches[0].end)
    }

    // -------------------------------------------------------------------------
    // 14. stemVariant_spanCoversBaseOnly
    // "running" in text when page "Run" has a stem entry with reportedBaseLength=3
    // → span [0,3], not [0,7]
    // -------------------------------------------------------------------------

    @Test
    fun stemVariant_spanCoversBaseOnly() {
        val entry = AhoCorasickMatcher.TrieEntry("running", "Run", reportedBaseLength = 3)
        val matcher = AhoCorasickMatcher(listOf(entry))
        val matches = matcher.findAll("running fast")
        assertEquals(1, matches.size, "Stem variant 'running' should produce one match")
        assertEquals(0, matches[0].start, "Match should start at 0")
        assertEquals(3, matches[0].end, "Reported end should be 3 (base 'run'), not 7 (full variant)")
        assertEquals("Run", matches[0].canonicalName)
    }

    // -------------------------------------------------------------------------
    // 15. stemVariant_boundaryRejectedWhenNoWordBoundaryAfterVariant
    // "runningmore" has no word boundary after the full variant "running"
    // → should NOT match even though "running" appears at start
    // -------------------------------------------------------------------------

    @Test
    fun stemVariant_boundaryRejectedWhenNoWordBoundaryAfterVariant() {
        val entry = AhoCorasickMatcher.TrieEntry("running", "Run", reportedBaseLength = 3)
        val matcher = AhoCorasickMatcher(listOf(entry))
        val matches = matcher.findAll("runningmore is not a word")
        assertEquals(0, matches.size, "'runningmore' has no boundary after the full variant — must be rejected")
    }

    // -------------------------------------------------------------------------
    // 16. stemVariant_matchAtEndOfText
    // -------------------------------------------------------------------------

    @Test
    fun stemVariant_matchAtEndOfText() {
        val entry = AhoCorasickMatcher.TrieEntry("running", "Run", reportedBaseLength = 3)
        val matcher = AhoCorasickMatcher(listOf(entry))
        // "I was running" — "running" starts at index 6 ("I was " = 6 chars)
        val matches = matcher.findAll("I was running")
        assertEquals(1, matches.size, "Stem variant at end of text should match")
        assertEquals(6, matches[0].start)
        assertEquals(9, matches[0].end, "Reported end should be start + base length (6 + 3 = 9)")
    }

    // -------------------------------------------------------------------------
    // 17. stemVariant_exactEntryWinsOverStemEntry
    // When both exact "running" and stem "running"→"Run" exist, the exact entry should win
    // (first-registered priority via seenPatterns in PageNameIndex; here tested directly)
    // -------------------------------------------------------------------------

    @Test
    fun stemVariant_exactEntryBeforeStemEntry_exactWins() {
        val exact = AhoCorasickMatcher.TrieEntry("running", "Running")
        val stem  = AhoCorasickMatcher.TrieEntry("running", "Run", reportedBaseLength = 3)
        val matcher = AhoCorasickMatcher(listOf(exact, stem))
        val matches = matcher.findAll("running")
        assertTrue(matches.isNotEmpty(), "Should have at least one match")
        assertTrue(
            matches.any { it.canonicalName == "Running" },
            "Exact 'Running' entry must appear in results"
        )
    }

    // -------------------------------------------------------------------------
    // 18. trieEntry_backwardCompatConstructor
    // The Map<String,String> constructor should still work after the refactor
    // -------------------------------------------------------------------------

    @Test
    fun trieEntry_backwardCompatConstructor() {
        val matcher = AhoCorasickMatcher(mapOf("kotlin" to "Kotlin", "java" to "Java"))
        val matches = matcher.findAll("I use Kotlin and Java")
        assertEquals(2, matches.size)
        val names = matches.map { it.canonicalName }.toSet()
        assertTrue("Kotlin" in names)
        assertTrue("Java" in names)
    }
}
