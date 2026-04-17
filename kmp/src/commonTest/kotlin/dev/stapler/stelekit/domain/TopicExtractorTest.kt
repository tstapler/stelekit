package dev.stapler.stelekit.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TopicExtractorTest {

    // -------------------------------------------------------------------------
    // 1. Single-word CamelCase term detected and scored
    // -------------------------------------------------------------------------

    @Test
    fun singleWordCamelCaseTermDetected() {
        val text = "I use TensorFlow for deep learning. TensorFlow is great."
        val suggestions = TopicExtractor.extract(text, emptySet())
        assertTrue(suggestions.any { it.term == "TensorFlow" }, "Expected TensorFlow to be detected")
        val tf = suggestions.first { it.term == "TensorFlow" }
        assertEquals(TopicSuggestion.Source.LOCAL, tf.source)
        assertTrue(tf.confidence > 0f)
    }

    // -------------------------------------------------------------------------
    // 2. Multi-word noun phrase detected
    // -------------------------------------------------------------------------

    @Test
    fun multiWordNounPhraseDetected() {
        val text = "Machine Learning approaches have transformed many fields. " +
            "We apply Machine Learning daily."
        val suggestions = TopicExtractor.extract(text, emptySet())
        assertTrue(suggestions.any { it.term == "Machine Learning" },
            "Expected 'Machine Learning' to be detected. Got: ${suggestions.map { it.term }}")
    }

    // -------------------------------------------------------------------------
    // 3. Stopwords filtered out
    // -------------------------------------------------------------------------

    @Test
    fun stopwordsFilteredOut() {
        // "Introduction" → in EXTRACTION_STOPWORDS; "API" → in EXTRACTION_STOPWORDS
        val text = "Introduction to this topic. The API is simple."
        val suggestions = TopicExtractor.extract(text, emptySet())
        assertFalse(suggestions.any { it.term.lowercase() == "introduction" },
            "'Introduction' should be filtered as a stopword")
        assertFalse(suggestions.any { it.term.uppercase() == "API" },
            "'API' should be filtered as a stopword")
    }

    // -------------------------------------------------------------------------
    // 4. Sentence-initial single word not surfaced unless it appears mid-sentence
    // -------------------------------------------------------------------------

    @Test
    fun sentenceInitialOnlyWordNotSurfaced() {
        // "Kotlin" appears only at the start of a sentence — should not be extracted
        val text = "Kotlin is a great language."
        val suggestions = TopicExtractor.extract(text, emptySet())
        assertFalse(suggestions.any { it.term == "Kotlin" },
            "'Kotlin' at sentence start only should not be surfaced")
    }

    @Test
    fun sentenceInitialWordSurfacedWhenAlsoMidSentence() {
        // "Kotlin" appears at sentence start AND mid-sentence → should be extracted
        val text = "Kotlin is a great language. I love Kotlin daily."
        val suggestions = TopicExtractor.extract(text, emptySet())
        assertTrue(suggestions.any { it.term == "Kotlin" },
            "'Kotlin' that also appears mid-sentence should be surfaced")
    }

    // -------------------------------------------------------------------------
    // 5. Term already in existingNames is excluded
    // -------------------------------------------------------------------------

    @Test
    fun termInExistingNamesExcluded() {
        val text = "I use TensorFlow and PyTorch. TensorFlow is popular."
        val existingNames = setOf("TensorFlow")
        val suggestions = TopicExtractor.extract(text, existingNames)
        assertFalse(suggestions.any { it.term.lowercase() == "tensorflow" },
            "TensorFlow is in existingNames and should be excluded")
    }

    // -------------------------------------------------------------------------
    // 6. Results capped at 15
    // -------------------------------------------------------------------------

    @Test
    fun resultsCappedAtFifteen() {
        // Generate 20 distinct camelCase terms, each appearing mid-sentence twice
        val terms = (1..20).map { "CamelTerm$it" }
        val text = terms.joinToString(" and ") { "the $it library is used, $it again" }
        val suggestions = TopicExtractor.extract(text, emptySet())
        assertTrue(suggestions.size <= 15, "Results should be capped at 15, got ${suggestions.size}")
    }

    // -------------------------------------------------------------------------
    // 7. Score below 0.2 confidence suppressed
    // -------------------------------------------------------------------------

    @Test
    fun scoreBelowThresholdSuppressed() {
        // "TensorFlow" appears 100 times (very high score); "Kotlin" appears once mid-sentence.
        // "Kotlin" confidence will be << 0.2 relative to "TensorFlow" and should be filtered.
        val repeatedTerm = "TensorFlow "
        val text = repeatedTerm.repeat(100) + " and I tried Kotlin once."
        val suggestions = TopicExtractor.extract(text, emptySet())

        // TensorFlow should definitely be present (highest scorer)
        assertTrue(suggestions.any { it.term == "TensorFlow" })

        // All returned suggestions should have confidence >= 0.2
        suggestions.forEach { suggestion ->
            assertTrue(
                suggestion.confidence >= 0.2f,
                "All suggestions should have confidence >= 0.2, but '${suggestion.term}' has ${suggestion.confidence}",
            )
        }
    }
}
