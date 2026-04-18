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
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ClaudeLlmFormatterProviderTest {

    private fun buildProvider(engine: MockEngine): ClaudeLlmFormatterProvider {
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return ClaudeLlmFormatterProvider(client, "test-key")
    }

    private val validResponse = """
        {"content":[{"type":"text","text":"- bullet point one\n- bullet point two."}],"stop_reason":"end_turn"}
    """.trimIndent()

    @Test
    fun `200 with formatted text returns Success`() = runTest {
        val engine = MockEngine {
            respond(validResponse, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val result = buildProvider(engine).format("test transcript", "prompt")
        assertIs<LlmResult.Success>(result)
        assertEquals("- bullet point one\n- bullet point two.", result.formattedText)
    }

    @Test
    fun `truncation detected when last char is not sentence-ending`() = runTest {
        val truncatedBody = """{"content":[{"type":"text","text":"- bullet that cuts off mid"}],"stop_reason":"max_tokens"}"""
        val engine = MockEngine {
            respond(truncatedBody, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val result = buildProvider(engine).format("test transcript", "prompt")
        assertIs<LlmResult.Success>(result)
        assertEquals(true, result.isLikelyTruncated)
    }

    @Test
    fun `no truncation when last char is period`() = runTest {
        val engine = MockEngine {
            respond(validResponse, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val result = buildProvider(engine).format("test transcript", "prompt")
        assertIs<LlmResult.Success>(result)
        assertEquals(false, result.isLikelyTruncated)
    }

    @Test
    fun `401 returns ApiError with code 401`() = runTest {
        val engine = MockEngine {
            respond("""{"error":{"message":"Invalid key"}}""", HttpStatusCode.Unauthorized, headersOf("Content-Type", "application/json"))
        }
        val result = buildProvider(engine).format("transcript", "prompt")
        assertIs<LlmResult.Failure.ApiError>(result)
        assertEquals(401, result.code)
    }

    @Test
    fun `429 returns ApiError with code 429`() = runTest {
        val engine = MockEngine {
            respond("""{"error":{"message":"Rate limited"}}""", HttpStatusCode.TooManyRequests, headersOf("Content-Type", "application/json"))
        }
        val result = buildProvider(engine).format("transcript", "prompt")
        assertIs<LlmResult.Failure.ApiError>(result)
        assertEquals(429, result.code)
    }

    @Test
    fun `500 returns ApiError with code 500`() = runTest {
        val engine = MockEngine {
            respond("""{"error":{"message":"Internal error"}}""", HttpStatusCode.InternalServerError, headersOf("Content-Type", "application/json"))
        }
        val result = buildProvider(engine).format("transcript", "prompt")
        assertIs<LlmResult.Failure.ApiError>(result)
        assertEquals(500, result.code)
    }

    @Test
    fun `network exception returns NetworkError`() = runTest {
        val engine = MockEngine { throw java.io.IOException("Connection refused") }
        val result = buildProvider(engine).format("transcript", "prompt")
        assertIs<LlmResult.Failure.NetworkError>(result)
    }

    @Test
    fun `request includes x-api-key and anthropic-version headers`() = runTest {
        var capturedApiKey = ""
        var capturedVersion = ""
        val engine = MockEngine { request ->
            capturedApiKey = request.headers["x-api-key"] ?: ""
            capturedVersion = request.headers["anthropic-version"] ?: ""
            respond(validResponse, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        buildProvider(engine).format("transcript", "prompt")
        assertEquals("test-key", capturedApiKey)
        assertEquals("2023-06-01", capturedVersion)
    }
}
