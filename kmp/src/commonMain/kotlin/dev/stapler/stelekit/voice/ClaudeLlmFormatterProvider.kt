// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

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
import kotlinx.serialization.json.Json

class ClaudeLlmFormatterProvider(
    private val httpClient: HttpClient,
    private val apiKey: String,
) : LlmFormatterProvider {

    companion object {
        private const val MESSAGES_URL = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val CLAUDE_MODEL = "claude-haiku-4-5-20251001"
        private val SENTENCE_END = setOf('.', '?', '!', ']', '\n')
        private val lenientJson = Json { ignoreUnknownKeys = true }

        fun withDefaults(apiKey: String): ClaudeLlmFormatterProvider {
            val client = HttpClient {
                install(ContentNegotiation) { json(lenientJson) }
            }
            return ClaudeLlmFormatterProvider(client, apiKey)
        }
    }

    override suspend fun format(transcript: String, systemPrompt: String): LlmResult {
        val wordCount = transcript.split(Regex("\\s+")).count { it.isNotBlank() }
        val maxTokens = (wordCount * 2).coerceIn(512, 4096)

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
                        messages = listOf(ClaudeMessage(role = "user", content = systemPrompt)),
                    )
                )
            }

            when (response.status.value) {
                200 -> {
                    val body = response.body<ClaudeResponse>()
                    val text = body.content.firstOrNull()?.text?.trim() ?: return LlmResult.Failure.NetworkError
                    val truncated = text.isNotEmpty() && text.last() !in SENTENCE_END
                    LlmResult.Success(formattedText = text, isLikelyTruncated = truncated)
                }
                401 -> LlmResult.Failure.ApiError(401, "Invalid API key")
                429 -> LlmResult.Failure.ApiError(429, "Rate limit exceeded")
                else -> LlmResult.Failure.ApiError(response.status.value, "HTTP ${response.status.value}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LlmResult.Failure.NetworkError
        }
    }
}

@Serializable
private data class ClaudeRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<ClaudeMessage>,
)

@Serializable
private data class ClaudeMessage(val role: String, val content: String)

@Serializable
private data class ClaudeResponse(val content: List<ClaudeContentBlock>)

@Serializable
private data class ClaudeContentBlock(val type: String, val text: String)
