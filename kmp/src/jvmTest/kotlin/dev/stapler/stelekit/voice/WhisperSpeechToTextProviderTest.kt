// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals

class WhisperSpeechToTextProviderTest {

    private fun buildProvider(engine: MockEngine): WhisperSpeechToTextProvider {
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return WhisperSpeechToTextProvider(client, "test-key")
    }

    @Test
    fun `empty audio returns Empty without network call`() = runTest {
        val engine = MockEngine { error("Should not be called") }
        val result = buildProvider(engine).transcribe(ByteArray(0))
        assertIs<TranscriptResult.Empty>(result)
    }

    @Test
    fun `200 with text returns Success`() = runTest {
        val engine = MockEngine {
            respond(
                content = "Hello world transcript",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/plain"),
            )
        }
        val result = buildProvider(engine).transcribe(ByteArray(100))
        assertIs<TranscriptResult.Success>(result)
        assertEquals("Hello world transcript", result.text)
    }

    @Test
    fun `200 with blank response returns Empty`() = runTest {
        val engine = MockEngine {
            respond(
                content = "   ",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/plain"),
            )
        }
        val result = buildProvider(engine).transcribe(ByteArray(100))
        assertIs<TranscriptResult.Empty>(result)
    }

    @Test
    fun `401 returns ApiError with code 401`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":{"message":"Invalid API key"}}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val result = buildProvider(engine).transcribe(ByteArray(100))
        assertIs<TranscriptResult.Failure.ApiError>(result)
        assertEquals(401, result.code)
    }

    @Test
    fun `429 returns ApiError with code 429`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":{"message":"Rate limit exceeded"}}""",
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val result = buildProvider(engine).transcribe(ByteArray(100))
        assertIs<TranscriptResult.Failure.ApiError>(result)
        assertEquals(429, result.code)
    }

    @Test
    fun `network exception returns NetworkError`() = runTest {
        val engine = MockEngine { throw java.io.IOException("Connection refused") }
        val result = buildProvider(engine).transcribe(ByteArray(100))
        assertIs<TranscriptResult.Failure.NetworkError>(result)
    }

    @Test
    fun `500 response returns ApiError with code 500`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":{"message":"Internal server error"}}""",
                status = HttpStatusCode.InternalServerError,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val result = buildProvider(engine).transcribe(ByteArray(100))
        assertIs<TranscriptResult.Failure.ApiError>(result)
        assertEquals(500, result.code)
    }

    @Test
    fun `request targets correct URL with Authorization header`() = runTest {
        var capturedUrl = ""
        var capturedAuth = ""
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            capturedAuth = request.headers["Authorization"] ?: ""
            respond(
                content = "Test transcript",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/plain"),
            )
        }
        buildProvider(engine).transcribe(ByteArray(100))
        assertEquals("https://api.openai.com/v1/audio/transcriptions", capturedUrl)
        assertEquals("Bearer test-key", capturedAuth)
    }
}
