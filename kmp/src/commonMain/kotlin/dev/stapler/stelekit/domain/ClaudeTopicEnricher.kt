package dev.stapler.stelekit.domain

import dev.stapler.stelekit.voice.ClaudeLlmFormatterProvider
import dev.stapler.stelekit.voice.LlmFormatterProvider
import dev.stapler.stelekit.voice.LlmResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Epic 8 Story 8.3: delegates to the shared, unified [LlmFormatterProvider] contract
 * ([ClaudeLlmFormatterProvider] by default via [withDefaults]) instead of owning an
 * independent `HttpClient` + hand-rolled retry-on-429 logic. Deletes the third, independent
 * error-handling code path pitfalls research flagged — retries/circuit-breaking now go through
 * [ClaudeLlmFormatterProvider]'s shared [arrow.resilience.CircuitBreaker] like every other
 * consumer of that provider.
 *
 * [TopicEnricher]'s external interface (`enhance(rawText, localSuggestions)`) — and every one
 * of its existing callers — is untouched.
 */
class ClaudeTopicEnricher(
    private val claudeProvider: LlmFormatterProvider,
) : TopicEnricher {

    companion object {
        private const val MAX_INPUT_CHARS = 60_000 // ~15k tokens at ~4 chars/token

        /**
         * `max_tokens: 256` is a load-bearing wire-compatibility constant for this feature —
         * distinct from voice formatting's dynamic, transcript-length-based estimate — so it's
         * threaded through as [ClaudeLlmFormatterProvider]'s `maxTokensOverride`.
         */
        private const val TOPIC_ENRICHER_MAX_TOKENS = 256

        private val lenientJson = Json { ignoreUnknownKeys = true }

        /**
         * Preserved for callers not yet threading the registry through — internally constructs
         * a [ClaudeLlmFormatterProvider] and delegates; no independent HTTP call, no
         * independent retry logic.
         */
        fun withDefaults(apiKey: String): ClaudeTopicEnricher =
            ClaudeTopicEnricher(ClaudeLlmFormatterProvider.withDefaults(apiKey, maxTokensOverride = TOPIC_ENRICHER_MAX_TOKENS))
    }

    override suspend fun enhance(
        rawText: String,
        localSuggestions: List<TopicSuggestion>,
    ): List<TopicSuggestion> {
        val truncatedText = rawText.take(MAX_INPUT_CHARS)
        val candidateJson = localSuggestions.joinToString { "\"${it.term}\"" }

        val prompt = buildString {
            append("You are a knowledge graph assistant. ")
            append("Given the document below and a list of candidate page names, ")
            append("return a JSON array of objects with 'term' (string) and 'confidence' (float 0-1). ")
            append("Re-rank the candidates by page-worthiness. You may add up to 5 net-new concepts. ")
            append("Return ONLY the JSON array, no markdown, no explanation.\n\n")
            append("Candidates: [$candidateJson]\n\n")
            append("<document>\n$truncatedText\n</document>")
        }

        // ClaudeLlmFormatterProvider's format(transcript, systemPrompt) contract sends a fixed
        // user turn and puts the real instructions in systemPrompt — match that convention by
        // putting the full prompt here rather than in transcript (which this feature doesn't
        // use; the circuit breaker/retry path only needs systemPrompt).
        return when (val result = claudeProvider.format(transcript = "", systemPrompt = prompt)) {
            is LlmResult.Success -> parseResponse(result.formattedText, localSuggestions)
            is LlmResult.Failure -> localSuggestions
        }
    }

    private fun parseResponse(
        rawJson: String,
        fallback: List<TopicSuggestion>,
    ): List<TopicSuggestion> = runCatching {
        val parsed = lenientJson.decodeFromString<List<ClaudeCandidate>>(rawJson)
        parsed.map { candidate ->
            TopicSuggestion(
                term = candidate.term,
                confidence = candidate.confidence.coerceIn(0f, 1f),
                source = TopicSuggestion.Source.AI_ENHANCED,
            )
        }
    }.getOrElse { fallback }
}

@Serializable
private data class ClaudeCandidate(val term: String, val confidence: Float)
