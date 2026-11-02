// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import arrow.resilience.CircuitBreaker
import dev.stapler.stelekit.llm.AnthropicContentBlock
import dev.stapler.stelekit.llm.AnthropicMessagesClient
import dev.stapler.stelekit.llm.AnthropicModels
import dev.stapler.stelekit.llm.AnthropicRequest
import dev.stapler.stelekit.llm.AnthropicResult
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

class ClaudeLlmFormatterProvider(
    httpClient: HttpClient,
    apiKey: String,
    circuitBreaker: CircuitBreaker = defaultCircuitBreaker(),
    /**
     * Epic 8 Story 8.3: when non-null, overrides [LlmProviderSupport.estimateMaxTokens]'s
     * transcript-length-based estimate (which is clamped to `[512, 4096]` and can never
     * produce a smaller value). `ClaudeTopicEnricher` needs a fixed `max_tokens: 256` — a
     * load-bearing wire-compatibility constant for that feature — which the dynamic estimate
     * cannot express. `null` (the default, used by voice formatting) preserves the existing
     * dynamic-estimate behavior exactly.
     */
    private val maxTokensOverride: Int? = null,
) : LlmFormatterProvider {
    private val client = AnthropicMessagesClient(httpClient, { apiKey }, circuitBreaker)

    fun close() = client.close()

    companion object {
        /**
         * Default circuit breaker: opens after 3 consecutive failures, resets after 30 seconds,
         * with exponential backoff up to 5 minutes. Delegates to the shared definition in
         * [LlmProviderSupport] (MA4) — see that function's kdoc.
         */
        fun defaultCircuitBreaker(): CircuitBreaker = LlmProviderSupport.defaultCircuitBreaker()

        fun withDefaults(apiKey: String, maxTokensOverride: Int? = null): ClaudeLlmFormatterProvider {
            val client = HttpClient {
                install(ContentNegotiation) { json(LlmProviderSupport.voiceLenientJson) }
            }
            return ClaudeLlmFormatterProvider(client, apiKey, maxTokensOverride = maxTokensOverride)
        }
    }

    override suspend fun format(transcript: String, systemPrompt: String): LlmResult {
        val request = AnthropicRequest(
            model = AnthropicModels.DEFAULT,
            maxTokens = maxTokensOverride ?: LlmProviderSupport.estimateMaxTokens(transcript),
            system = systemPrompt,
            // Feature-neutral wording: this provider is also reused by ClaudeTopicEnricher (candidate
            // re-ranking), so the instruction must not be voice-specific.
            content = listOf(AnthropicContentBlock.Text("Follow the instructions in the system prompt.")),
        )
        return when (val result = client.send(request)) {
            is AnthropicResult.Success -> LlmResult.Success(
                formattedText = result.text,
                isLikelyTruncated = LlmProviderSupport.detectTruncation(result.text),
            )
            is AnthropicResult.Failure.Http -> LlmProviderSupport.mapHttpError(result.status)
            AnthropicResult.Failure.Unexpected -> LlmResult.Failure.ApiError(-1, "Unexpected error")
            AnthropicResult.Failure.MissingKey,
            AnthropicResult.Failure.Network -> LlmResult.Failure.NetworkError
        }
    }
}
