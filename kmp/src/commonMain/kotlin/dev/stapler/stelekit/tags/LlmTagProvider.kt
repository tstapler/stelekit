package dev.stapler.stelekit.tags

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.voice.LlmFormatterProvider
import dev.stapler.stelekit.voice.LlmResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

class LlmTagProvider(
    private val provider: LlmFormatterProvider,
    private val timeoutSeconds: Long = 8,
) {
    companion object {
        private const val MAX_BLOCK_CHARS = 500
        private const val MAX_VOCABULARY_SIZE = 200
        private const val CONFIDENCE_MAX = 0.85f
        private const val CONFIDENCE_DECAY = 0.02f
        private const val CONFIDENCE_MIN = 0.50f
        private val WORD_SPLIT = Regex("\\W+")
    }

    suspend fun suggestTags(
        request: TagSuggestionRequest,
    ): Either<DomainError, List<TagSuggestion>> {
        val truncatedContent = request.blockContent.take(MAX_BLOCK_CHARS)
        val filtered = tokenOverlapFilter(truncatedContent, request.pageVocabulary)
            .take(MAX_VOCABULARY_SIZE)
        if (filtered.isEmpty()) return emptyList<TagSuggestion>().right()

        val systemPrompt = buildSystemPrompt(filtered)
        return try {
            withTimeout(timeoutSeconds.seconds) {
                when (val result = provider.format(truncatedContent, systemPrompt)) {
                    is LlmResult.Success -> parseResponse(result.formattedText, filtered).right()
                    is LlmResult.Failure.ApiError -> DomainError.NetworkError.HttpError(
                        result.code, result.message
                    ).left()
                    is LlmResult.Failure.NetworkError -> DomainError.NetworkError.RequestFailed(
                        "Network error"
                    ).left()
                    // Epic 5 (iOS on-device): guardrail content rejection. Tag suggestion has no
                    // dedicated DomainError case for this yet (contract intentionally unchanged
                    // — see validation.md's Integration/Regression Tests list) — map to
                    // RequestFailed like other non-retryable provider failures, preserving the
                    // reason for diagnostics.
                    is LlmResult.Failure.ContentRejected -> DomainError.NetworkError.RequestFailed(
                        "Content rejected: ${result.reason}"
                    ).left()
                }
            }
        } catch (e: TimeoutCancellationException) {
            DomainError.NetworkError.Timeout("LLM tag suggestion timed out after ${timeoutSeconds}s").left()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.NetworkError.RequestFailed(e.message ?: "LLM request failed").left()
        }
    }

    /** Keep only vocabulary names that share at least one token with the block text. */
    private fun tokenOverlapFilter(blockText: String, vocabulary: List<String>): List<String> {
        val blockTokens = blockText.lowercase().split(WORD_SPLIT).filter { it.isNotBlank() }.toSet()
        if (blockTokens.isEmpty()) return vocabulary
        return vocabulary.filter { name ->
            name.lowercase().split(WORD_SPLIT).filter { it.isNotBlank() }.any { it in blockTokens }
        }
    }

    private fun buildSystemPrompt(vocabulary: List<String>): String {
        val tagList = vocabulary.joinToString("\n") { "- $it" }
        return """
You are a knowledge-graph tagging assistant.
Given a block of text and a list of existing page names, return ONLY the page names from the
list below that are genuinely relevant to the block content.
Output one page name per line. No explanation, no markdown, no extra text.
If nothing is relevant, output nothing.

<tags>
$tagList
</tags>
""".trimIndent()
    }

    private fun parseResponse(responseText: String, vocabulary: List<String>): List<TagSuggestion> {
        val vocabLower = vocabulary.associateBy { it.lowercase() }
        val lines = responseText.lines()
        val results = mutableListOf<TagSuggestion>()
        lines.forEachIndexed { index, line ->
            val cleaned = line.trim().removePrefix("- ").trim()
            if (cleaned.isBlank()) return@forEachIndexed
            val canonical = vocabLower[cleaned.lowercase()] ?: return@forEachIndexed
            // Positional confidence decay: CONFIDENCE_MAX at position 0, decrement CONFIDENCE_DECAY per position
            val confidence = (CONFIDENCE_MAX - index * CONFIDENCE_DECAY).coerceIn(CONFIDENCE_MIN, CONFIDENCE_MAX)
            results += TagSuggestion(
                term = canonical,
                confidence = confidence,
                source = TagSuggestion.Source.LLM,
            )
        }
        return results
    }
}
