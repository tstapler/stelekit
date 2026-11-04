// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

import arrow.resilience.CircuitBreaker
import dev.stapler.stelekit.voice.LlmProviderSupport
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One block of the user message. [Image] carries base64 bytes and never prints them. */
sealed interface AnthropicContentBlock {
    data class Text(val text: String) : AnthropicContentBlock
    data class Image(val mediaType: String, val base64Data: String) : AnthropicContentBlock {
        override fun toString(): String = "Image(mediaType=$mediaType, base64Data=<${base64Data.length} chars>)"
    }
}

data class AnthropicRequest(
    val model: String,
    val maxTokens: Int,
    val system: String,
    val content: List<AnthropicContentBlock>,
)

sealed interface AnthropicResult {
    data class Success(val text: String) : AnthropicResult

    sealed interface Failure : AnthropicResult {
        /** No key in the credential store; no request was made. */
        data object MissingKey : Failure
        data class Http(val status: Int) : Failure
        /** I/O failure, an empty response, or an open circuit. */
        data object Network : Failure
        data object Unexpected : Failure
    }
}

/**
 * The one Anthropic Messages transport (URL, version header, `x-api-key`, circuit breaker),
 * shared by voice formatting, topic enrichment and the cloud asset plugin. [apiKey] is read on
 * every call so a deleted key stops the next request; it is only ever placed in a header.
 */
class AnthropicMessagesClient(
    private val httpClient: HttpClient,
    private val apiKey: () -> String?,
    private val circuitBreaker: CircuitBreaker = LlmProviderSupport.defaultCircuitBreaker(),
) {
    fun close() = httpClient.close()

    suspend fun send(request: AnthropicRequest): AnthropicResult {
        val key = apiKey()
        if (key == null) return AnthropicResult.Failure.MissingKey
        val protectedResult = circuitBreaker.protectEither { call(key, request) }
        return protectedResult.fold(ifLeft = { AnthropicResult.Failure.Network }, ifRight = { it })
    }

    private suspend fun call(key: String, request: AnthropicRequest): AnthropicResult = try {
        val response = httpClient.post(MESSAGES_URL) {
            headers {
                append("x-api-key", key)
                append("anthropic-version", ANTHROPIC_VERSION)
            }
            setBody(TextContent(JSON.encodeToString(JsonObject.serializer(), request.toJson()), ContentType.Application.Json.withParameter("charset", "UTF-8")))
        }
        if (response.status.value == 200) {
            parseText(response.bodyAsText()) ?: AnthropicResult.Failure.Network
        } else {
            AnthropicResult.Failure.Http(response.status.value)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        AnthropicResult.Failure.Network
    } catch (e: Exception) {
        AnthropicResult.Failure.Unexpected
    }

    private fun parseText(body: String): AnthropicResult? {
        val blocks = JSON.parseToJsonElement(body).jsonObject["content"]?.jsonArray ?: return null
        val text = blocks.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
            ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.trim()
            ?: return null
        return AnthropicResult.Success(text)
    }

    companion object {
        const val MESSAGES_URL = "https://api.anthropic.com/v1/messages"
        const val ANTHROPIC_VERSION = "2023-06-01"

        /** A client on the platform's default engine; [apiKey] is read on every request. */
        fun withDefaults(apiKey: () -> String?): AnthropicMessagesClient = AnthropicMessagesClient(HttpClient(), apiKey)

        private val JSON = Json { ignoreUnknownKeys = true }

        private fun AnthropicRequest.toJson(): JsonObject = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", system)
            put("messages", JsonArray(listOf(userMessage())))
        }

        // A lone text block is sent as a bare string, which keeps the voice and enricher requests byte-identical.
        private fun AnthropicRequest.userMessage(): JsonObject = buildJsonObject {
            put("role", "user")
            val single = content.singleOrNull() as? AnthropicContentBlock.Text
            put("content", if (single != null) JsonPrimitive(single.text) else JsonArray(content.map { it.toJson() }))
        }

        private fun AnthropicContentBlock.toJson(): JsonObject = when (this) {
            is AnthropicContentBlock.Text -> buildJsonObject {
                put("type", "text")
                put("text", text)
            }
            is AnthropicContentBlock.Image -> buildJsonObject {
                put("type", "image")
                put("source", buildJsonObject {
                    put("type", "base64")
                    put("media_type", mediaType)
                    put("data", JsonPrimitive(base64Data))
                })
            }
        }
    }
}
