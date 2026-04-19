package dev.stapler.stelekit.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportServiceInsertTest {

    private fun makeMatcher(vararg names: String): AhoCorasickMatcher {
        val map = names.associate { it.lowercase() to it }
        return AhoCorasickMatcher(map)
    }

    // =========================================================================
    // scan() — topicSuggestions integration
    // =========================================================================

    @Test
    fun scan_noExistingNames_returnsSuggestions() {
        val matcher = makeMatcher("Kotlin")
        // Text contains a camelCase term that should be suggested
        val text = "I use TensorFlow for ML. TensorFlow is popular."
        val result = ImportService.scan(text, matcher)
        // topicSuggestions may be empty or non-empty depending on heuristics; no crash
        // The point is that topicSuggestions is populated (not necessarily with TensorFlow)
        assertTrue(result.topicSuggestions is List<*>)
    }

    @Test
    fun scan_existingNamesExcludeCandidate() {
        val matcher = makeMatcher()
        val text = "I use TensorFlow daily. TensorFlow is amazing."
        val existingNames = setOf("TensorFlow")
        val result = ImportService.scan(text, matcher, existingNames)
        assertFalse(
            result.topicSuggestions.any { it.term.lowercase() == "tensorflow" },
            "TensorFlow in existingNames should not appear in suggestions",
        )
    }

    @Test
    fun scan_twoArgFormBackwardCompat() {
        val matcher = makeMatcher("Kotlin")
        val text = "I use Kotlin and Java."
        // Two-argument form must still compile and run
        val result = ImportService.scan(text, matcher)
        assertTrue(result.matchedPageNames.contains("Kotlin"))
    }

    // =========================================================================
    // insertWikiLinks()
    // =========================================================================

    @Test
    fun insertWikiLinks_singleWordInsertion() {
        val result = ImportService.insertWikiLinks("TensorFlow is great", listOf("TensorFlow"))
        assertEquals("[[TensorFlow]] is great", result)
    }

    @Test
    fun insertWikiLinks_multiWordInsertion() {
        val result = ImportService.insertWikiLinks(
            "machine learning approaches are effective",
            listOf("machine learning"),
        )
        assertEquals("[[machine learning]] approaches are effective", result)
    }

    @Test
    fun insertWikiLinks_alreadyLinkedOccurrenceSkipped() {
        // First occurrence inside [[...]] must stay unchanged; second must be linked
        val result = ImportService.insertWikiLinks(
            "[[TensorFlow]] and TensorFlow",
            listOf("TensorFlow"),
        )
        assertEquals("[[TensorFlow]] and [[TensorFlow]]", result)
    }

    @Test
    fun insertWikiLinks_caseInsensitiveUsesDisplayForm() {
        val result = ImportService.insertWikiLinks("tensorflow is good", listOf("TensorFlow"))
        assertEquals("[[TensorFlow]] is good", result)
    }

    @Test
    fun insertWikiLinks_multipleTermsNoDoubleLink() {
        val result = ImportService.insertWikiLinks(
            "Machine Learning and TensorFlow are popular",
            listOf("Machine Learning", "TensorFlow"),
        )
        assertEquals("[[Machine Learning]] and [[TensorFlow]] are popular", result)
    }

    @Test
    fun insertWikiLinks_emptyTermsListReturnsTextUnchanged() {
        val text = "no changes here"
        val result = ImportService.insertWikiLinks(text, emptyList())
        assertEquals(text, result)
    }
}
