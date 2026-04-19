// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.headers
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class WhisperSpeechToTextProvider(
    private val httpClient: HttpClient,
    private val apiKey: String,
) : SpeechToTextProvider {

    companion object {
        private const val TRANSCRIPTIONS_URL = "https://api.openai.com/v1/audio/transcriptions"
        private val lenientJson = Json { ignoreUnknownKeys = true }

        fun withDefaults(apiKey: String): WhisperSpeechToTextProvider {
            val client = HttpClient {
                install(ContentNegotiation) { json(lenientJson) }
            }
            return WhisperSpeechToTextProvider(client, apiKey)
        }
    }

    override suspend fun transcribe(audioData: ByteArray): TranscriptResult {
        if (audioData.isEmpty()) return TranscriptResult.Empty

        return try {
            val response = httpClient.submitFormWithBinaryData(
                url = TRANSCRIPTIONS_URL,
                formData = formData {
                    append("model", "gpt-4o-mini-transcribe")
                    append("response_format", "text")
                    append("file", audioData, Headers.build {
                        append(HttpHeaders.ContentType, "audio/mp4")
                        append(HttpHeaders.ContentDisposition, "filename=\"recording.m4a\"")
                    })
                }
            ) {
                headers { append(HttpHeaders.Authorization, "Bearer $apiKey") }
            }

            when (response.status.value) {
                200 -> {
                    val text = response.body<String>().trim()
                    if (text.isBlank()) TranscriptResult.Empty else TranscriptResult.Success(text)
                }
                401 -> TranscriptResult.Failure.ApiError(401, "Invalid API key")
                429 -> TranscriptResult.Failure.ApiError(429, "Rate limit exceeded")
                else -> TranscriptResult.Failure.ApiError(
                    response.status.value, "HTTP ${response.status.value}"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            TranscriptResult.Failure.NetworkError
        }
    }
}
