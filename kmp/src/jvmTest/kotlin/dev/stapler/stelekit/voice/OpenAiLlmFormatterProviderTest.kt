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

class OpenAiLlmFormatterProviderTest {

    private fun buildProvider(engine: MockEngine, baseUrl: String = "https://api.openai.com"): OpenAiLlmFormatterProvider {
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return OpenAiLlmFormatterProvider(client, "test-key", baseUrl)
    }

    private val validResponse = """
        {"choices":[{"message":{"role":"assistant","content":"- bullet point one\n- bullet point two."}}],"model":"gpt-4o-mini"}
    """.trimIndent()

    @Test
    fun `200 with formatted text returns Success`() = runTest {
        val engine = MockEngine {
            respond(validResponse, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val result = buildProvider(engine).format("test transcript", "system prompt")
        assertIs<LlmResult.Success>(result)
        assertEquals("- bullet point one\n- bullet point two.", result.formattedText)
    }

    @Test
    fun `truncation detected when last char is not sentence-ending`() = runTest {
        val truncatedBody = """{"choices":[{"message":{"role":"assistant","content":"- bullet that cuts off mid"}}]}"""
        val engine = MockEngine {
            respond(truncatedBody, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val result = buildProvider(engine).format("test transcript", "prompt")
        assertIs<LlmResult.Success>(result)
        assertEquals(true, result.isLikelyTruncated)
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
    fun `network exception returns NetworkError`() = runTest {
        val engine = MockEngine { throw java.io.IOException("Connection refused") }
        val result = buildProvider(engine).format("transcript", "prompt")
        assertIs<LlmResult.Failure.NetworkError>(result)
    }

    @Test
    fun `request targets correct URL`() = runTest {
        var capturedUrl = ""
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(validResponse, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        buildProvider(engine, "https://api.openai.com").format("transcript", "prompt")
        assertEquals("https://api.openai.com/v1/chat/completions", capturedUrl)
    }

    @Test
    fun `custom baseUrl is respected for OpenAI-compatible endpoints`() = runTest {
        var capturedUrl = ""
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(validResponse, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        buildProvider(engine, "https://openrouter.ai").format("transcript", "prompt")
        assertEquals("https://openrouter.ai/v1/chat/completions", capturedUrl)
    }

    @Test
    fun `request includes Authorization header`() = runTest {
        var capturedAuth = ""
        val engine = MockEngine { request ->
            capturedAuth = request.headers["Authorization"] ?: ""
            respond(validResponse, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        buildProvider(engine).format("transcript", "prompt")
        assertEquals("Bearer test-key", capturedAuth)
    }
}
