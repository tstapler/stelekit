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
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Google Gemini REST provider, following the same raw-Ktor-REST pattern as
 * [ClaudeLlmFormatterProvider]/[OpenAiLlmFormatterProvider] — no dedicated Gemini SDK
 * dependency (stack research confirmed no trustworthy KMP SDK exists).
 *
 * Unlike Claude/OpenAI, Gemini's `generateContent` API takes the system prompt via a
 * dedicated `systemInstruction` field rather than a "system" role message — do not smuggle
 * it into the first user turn.
 */
class GeminiLlmFormatterProvider(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val model: String = "gemini-2.0-flash",
    private val circuitBreaker: CircuitBreaker = defaultCircuitBreaker(),
) : LlmFormatterProvider {

    fun close() = httpClient.close()

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com"

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

        fun withDefaults(apiKey: String, model: String = "gemini-2.0-flash"): GeminiLlmFormatterProvider {
            val client = HttpClient {
                install(ContentNegotiation) { json(LlmProviderSupport.voiceLenientJson) }
            }
            return GeminiLlmFormatterProvider(client, apiKey, model)
        }
    }

    override suspend fun format(transcript: String, systemPrompt: String): LlmResult {
        val maxTokens = LlmProviderSupport.estimateMaxTokens(transcript)

        val protectedResult = circuitBreaker.protectEither {
            httpCallInternal(systemPrompt, maxTokens)
        }

        return protectedResult.fold(
            ifLeft = { LlmResult.Failure.NetworkError },
            ifRight = { it },
        )
    }

    private suspend fun httpCallInternal(
        systemPrompt: String,
        maxTokens: Int,
    ): LlmResult {
        return try {
            val response = httpClient.post("$BASE_URL/v1beta/models/$model:generateContent") {
                headers {
                    append("x-goog-api-key", apiKey)
                }
                contentType(ContentType.Application.Json)
                setBody(
                    GeminiRequest(
                        contents = listOf(
                            GeminiContent(parts = listOf(GeminiPart(text = "Output the formatted Logseq note."))),
                        ),
                        systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemPrompt))),
                        generationConfig = GeminiGenerationConfig(maxOutputTokens = maxTokens),
                    )
                )
            }

            when (response.status.value) {
                200 -> {
                    val body = response.body<GeminiResponse>()
                    val text = body.candidates.firstOrNull()
                        ?.content?.parts?.firstOrNull()?.text?.trim()
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
private data class GeminiRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiContent? = null,
    val generationConfig: GeminiGenerationConfig? = null,
)

@Serializable
private data class GeminiContent(val parts: List<GeminiPart>, val role: String? = null)

@Serializable
private data class GeminiPart(val text: String)

@Serializable
private data class GeminiGenerationConfig(@SerialName("maxOutputTokens") val maxOutputTokens: Int)

@Serializable
private data class GeminiResponse(val candidates: List<GeminiCandidate> = emptyList())

@Serializable
private data class GeminiCandidate(val content: GeminiContent? = null)
