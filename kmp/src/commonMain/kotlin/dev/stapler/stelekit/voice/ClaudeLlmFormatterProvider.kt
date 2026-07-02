// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import arrow.resilience.CircuitBreaker
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import io.ktor.utils.io.errors.IOException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ClaudeLlmFormatterProvider(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val circuitBreaker: CircuitBreaker = defaultCircuitBreaker(),
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

    fun close() = httpClient.close()

    companion object {
        private const val MESSAGES_URL = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val CLAUDE_MODEL = "claude-haiku-4-5-20251001"

        /**
         * Default circuit breaker: opens after 3 consecutive failures, resets after 30 seconds,
         * with exponential backoff up to 5 minutes.
         */
        fun defaultCircuitBreaker(): CircuitBreaker = CircuitBreaker(
            openingStrategy = CircuitBreaker.OpeningStrategy.Count(maxFailures = 3),
            resetTimeout = 30.seconds,
            exponentialBackoffFactor = 2.0,
            maxResetTimeout = 5.minutes,
        )

        fun withDefaults(apiKey: String, maxTokensOverride: Int? = null): ClaudeLlmFormatterProvider {
            val client = HttpClient {
                install(ContentNegotiation) { json(LlmProviderSupport.voiceLenientJson) }
            }
            return ClaudeLlmFormatterProvider(client, apiKey, maxTokensOverride = maxTokensOverride)
        }
    }

    override suspend fun format(transcript: String, systemPrompt: String): LlmResult {
        val maxTokens = maxTokensOverride ?: LlmProviderSupport.estimateMaxTokens(transcript)

        val protectedResult = circuitBreaker.protectEither {
            httpCallInternal(systemPrompt, maxTokens)
        }

        return protectedResult.fold(
            ifLeft = { LlmResult.Failure.NetworkError },
            ifRight = { it }
        )
    }

    private suspend fun httpCallInternal(
        systemPrompt: String,
        maxTokens: Int,
    ): LlmResult {
        return try {
            val response = httpClient.post(MESSAGES_URL) {
                headers {
                    append("x-api-key", apiKey)
                    append("anthropic-version", ANTHROPIC_VERSION)
                }
                contentType(ContentType.Application.Json)
                setBody(
                    ClaudeRequest(
                        model = CLAUDE_MODEL,
                        maxTokens = maxTokens,
                        system = systemPrompt,
                        // Epic 8 Story 8.3: feature-neutral wording — this provider is now
                        // reused by ClaudeTopicEnricher (candidate re-ranking), not just voice
                        // formatting, and the previous "Output the formatted Logseq note."
                        // wording was voice-specific and would have been a confusing
                        // instruction for the topic-enrichment prompt.
                        messages = listOf(ClaudeMessage(role = "user", content = "Follow the instructions in the system prompt.")),
                    )
                )
            }

            when (response.status.value) {
                200 -> {
                    val body = response.body<ClaudeResponse>()
                    val text = body.content.firstOrNull { it.type == "text" }?.text?.trim()
                        ?: return LlmResult.Failure.NetworkError
                    LlmResult.Success(
                        formattedText = text,
                        isLikelyTruncated = LlmProviderSupport.detectTruncation(text),
                    )
                }
                else -> LlmProviderSupport.mapHttpError(response.status.value)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            LlmResult.Failure.NetworkError
        } catch (e: Exception) {
            LlmResult.Failure.ApiError(-1, "Unexpected error")
        }
    }
}

@Serializable
private data class ClaudeRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String,
    val messages: List<ClaudeMessage>,
)

@Serializable
private data class ClaudeMessage(val role: String, val content: String)

@Serializable
private data class ClaudeResponse(val content: List<ClaudeContentBlock>)

@Serializable
private data class ClaudeContentBlock(val type: String, val text: String)
