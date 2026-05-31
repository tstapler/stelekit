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
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import io.ktor.utils.io.errors.IOException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class OpenAiLlmFormatterProvider(
    private val httpClient: HttpClient,
    private val apiKey: String,
    baseUrl: String = "https://api.openai.com",
    private val circuitBreaker: CircuitBreaker = defaultCircuitBreaker(),
) : LlmFormatterProvider {

    private val completionsUrl: String

    init {
        require(baseUrl.startsWith("https://")) { "baseUrl must use HTTPS" }
        completionsUrl = "$baseUrl/v1/chat/completions"
    }

    fun close() = httpClient.close()

    companion object {
        private const val OPENAI_MODEL = "gpt-4o-mini"

        fun defaultCircuitBreaker(): CircuitBreaker = CircuitBreaker(
            openingStrategy = CircuitBreaker.OpeningStrategy.Count(maxFailures = 3),
            resetTimeout = 30.seconds,
            exponentialBackoffFactor = 2.0,
            maxResetTimeout = 5.minutes,
        )

        fun withDefaults(apiKey: String, baseUrl: String = "https://api.openai.com"): OpenAiLlmFormatterProvider {
            val client = HttpClient {
                install(ContentNegotiation) { json(LlmProviderSupport.voiceLenientJson) }
            }
            return OpenAiLlmFormatterProvider(client, apiKey, baseUrl)
        }
    }

    override suspend fun format(transcript: String, systemPrompt: String): LlmResult {
        val maxTokens = LlmProviderSupport.estimateMaxTokens(transcript)
        return circuitBreaker.protectEither {
            httpCallInternal(systemPrompt, maxTokens)
        }.fold(
            ifLeft = { LlmResult.Failure.NetworkError },
            ifRight = { it },
        )
    }

    private suspend fun httpCallInternal(systemPrompt: String, maxTokens: Int): LlmResult {
        return try {
            val response = httpClient.post(completionsUrl) {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $apiKey")
                }
                contentType(ContentType.Application.Json)
                setBody(
                    OpenAiRequest(
                        model = OPENAI_MODEL,
                        maxTokens = maxTokens,
                        messages = listOf(
                            // systemPrompt already contains the transcript via {{TRANSCRIPT}} substitution;
                            // send it in the system role only — do not re-send the raw transcript as a user turn.
                            OpenAiMessage(role = "system", content = systemPrompt),
                            OpenAiMessage(role = "user", content = "Output the formatted Logseq note."),
                        ),
                    )
                )
            }

            when (response.status.value) {
                200 -> {
                    val body = response.body<OpenAiResponse>()
                    val text = body.choices.firstOrNull()?.message?.content?.trim()
                        ?: return LlmResult.Failure.NetworkError
                    LlmResult.Success(formattedText = text, isLikelyTruncated = LlmProviderSupport.detectTruncation(text))
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
private data class OpenAiRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<OpenAiMessage>,
)

@Serializable
private data class OpenAiMessage(val role: String, val content: String)

@Serializable
private data class OpenAiResponse(val choices: List<OpenAiChoice>)

@Serializable
private data class OpenAiChoice(val message: OpenAiMessage)
