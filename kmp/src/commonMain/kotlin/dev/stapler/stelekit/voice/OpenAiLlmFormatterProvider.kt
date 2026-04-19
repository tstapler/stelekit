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
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.ktor.utils.io.errors.IOException

class OpenAiLlmFormatterProvider(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com",
) : LlmFormatterProvider {

    companion object {
        private const val OPENAI_MODEL = "gpt-4o-mini"
        private val lenientJson = Json { ignoreUnknownKeys = true }

        fun withDefaults(apiKey: String, baseUrl: String = "https://api.openai.com"): OpenAiLlmFormatterProvider {
            val client = HttpClient {
                install(ContentNegotiation) { json(lenientJson) }
            }
            return OpenAiLlmFormatterProvider(client, apiKey, baseUrl)
        }
    }

    override suspend fun format(transcript: String, systemPrompt: String): LlmResult {
        val maxTokens = LlmProviderSupport.estimateMaxTokens(transcript)
        val completionsUrl = "$baseUrl/v1/chat/completions"

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
                            OpenAiMessage(role = "system", content = systemPrompt),
                            OpenAiMessage(role = "user", content = transcript),
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
            LlmResult.Failure.ApiError(-1, "Unexpected error: ${e.message}")
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
