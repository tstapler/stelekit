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

/**
 * OpenAI-compatible chat-completions provider. Also covers any vendor implementing the same
 * `/v1/chat/completions` wire shape — Ollama, LM Studio, OpenRouter, and current-generation
 * (v1-GA) Azure OpenAI — via [model] and [baseUrl] ([CustomOpenAiCompatibleLlmProvider] wraps
 * this class for that use case; legacy pre-v1-GA Azure OpenAI's deployment-path/api-key auth
 * scheme is out of scope, see that class's kdoc).
 *
 * [allowInsecureHttp] relaxes the HTTPS-only guard for loopback hosts only (`localhost`,
 * `127.0.0.1`, `[::1]`) — e.g. a local Ollama/LM Studio instance. A non-loopback HTTP endpoint
 * is always rejected, flag or not; this is the security-relevant scope boundary that prevents
 * leaking an API key over plaintext to a non-local host.
 */
class OpenAiLlmFormatterProvider(
    private val httpClient: HttpClient,
    private val apiKey: String,
    baseUrl: String = "https://api.openai.com",
    private val model: String = OPENAI_MODEL,
    private val allowInsecureHttp: Boolean = false,
    private val circuitBreaker: CircuitBreaker = defaultCircuitBreaker(),
) : LlmFormatterProvider {

    private val completionsUrl: String

    init {
        require(baseUrl.startsWith("https://") || (allowInsecureHttp && isLoopbackHttpUrl(baseUrl))) {
            "baseUrl must use HTTPS, or HTTP to a loopback host with allowInsecureHttp=true"
        }
        completionsUrl = "$baseUrl/v1/chat/completions"
    }

    fun close() = httpClient.close()

    companion object {
        private const val OPENAI_MODEL = "gpt-4o-mini"

        /** Delegates to the shared definition in [LlmProviderSupport] (MA4) — see that function's kdoc. */
        fun defaultCircuitBreaker(): CircuitBreaker = LlmProviderSupport.defaultCircuitBreaker()

        fun withDefaults(apiKey: String, baseUrl: String = "https://api.openai.com"): OpenAiLlmFormatterProvider {
            val client = HttpClient {
                install(ContentNegotiation) { json(LlmProviderSupport.voiceLenientJson) }
            }
            return OpenAiLlmFormatterProvider(client, apiKey, baseUrl)
        }

        /**
         * `true` only for `http://` URLs whose host is a loopback address
         * (`localhost`, `127.0.0.1`, `[::1]`) — never a blanket "any HTTP allowed" check.
         * Delegates the actual host comparison to [LlmProviderSupport.isLoopbackHost] (MA6) —
         * the single shared exact-match implementation, also used by
         * [dev.stapler.stelekit.llm.CustomProviderUrlValidation].
         */
        internal fun isLoopbackHttpUrl(url: String): Boolean {
            if (!url.startsWith("http://")) return false
            val authority = url.removePrefix("http://").substringBefore("/")
            val host = if (authority.startsWith("[")) {
                authority.substringBefore("]").removePrefix("[")
            } else {
                authority.substringBefore(":")
            }
            return LlmProviderSupport.isLoopbackHost(host)
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
                if (apiKey.isNotBlank()) {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $apiKey")
                    }
                }
                contentType(ContentType.Application.Json)
                setBody(
                    OpenAiRequest(
                        model = model,
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
