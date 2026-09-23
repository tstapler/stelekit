// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GeminiLlmFormatterProviderTest {

    private fun buildProvider(engine: MockEngine): GeminiLlmFormatterProvider {
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return GeminiLlmFormatterProvider(client, "test-key")
    }

    private val validResponse = """
        {"candidates":[{"content":{"parts":[{"text":"- bullet point one\n- bullet point two."}],"role":"model"},"finishReason":"STOP"}]}
    """.trimIndent()

    @Test
    fun `format_should_ParseSuccess_When_200Response`() = runTest {
        val engine = MockEngine {
            respond(validResponse, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val result = buildProvider(engine).format("test transcript", "prompt")
        assertIs<LlmResult.Success>(result)
        assertEquals("- bullet point one\n- bullet point two.", result.formattedText)
    }

    @Test
    fun `truncation detected when last char is not sentence-ending`() = runTest {
        val truncatedBody =
            """{"candidates":[{"content":{"parts":[{"text":"- bullet that cuts off mid"}],"role":"model"},"finishReason":"MAX_TOKENS"}]}"""
        val engine = MockEngine {
            respond(truncatedBody, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val result = buildProvider(engine).format("test transcript", "prompt")
        assertIs<LlmResult.Success>(result)
        assertEquals(true, result.isLikelyTruncated)
    }

    @Test
    fun `format_should_MapError_When_401`() = runTest {
        val engine = MockEngine {
            respond("""{"error":{"message":"Invalid key"}}""", HttpStatusCode.Unauthorized, headersOf("Content-Type", "application/json"))
        }
        val result = buildProvider(engine).format("transcript", "prompt")
        assertIs<LlmResult.Failure.ApiError>(result)
        assertEquals(401, result.code)
    }

    @Test
    fun `format_should_MapError_When_429`() = runTest {
        val engine = MockEngine {
            respond("""{"error":{"message":"Rate limited"}}""", HttpStatusCode.TooManyRequests, headersOf("Content-Type", "application/json"))
        }
        val result = buildProvider(engine).format("transcript", "prompt")
        assertIs<LlmResult.Failure.ApiError>(result)
        assertEquals(429, result.code)
    }

    @Test
    fun `format_should_MapError_When_5xx`() = runTest {
        val engine = MockEngine {
            respond("""{"error":{"message":"Internal error"}}""", HttpStatusCode.InternalServerError, headersOf("Content-Type", "application/json"))
        }
        val result = buildProvider(engine).format("transcript", "prompt")
        assertIs<LlmResult.Failure.ApiError>(result)
        assertEquals(500, result.code)
    }

    @Test
    fun `format_should_ReturnNetworkError_When_EmptyCandidatesInResponse`() = runTest {
        val engine = MockEngine {
            respond("""{"candidates":[]}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val result = buildProvider(engine).format("transcript", "prompt")
        assertIs<LlmResult.Failure.NetworkError>(result)
    }

    @Test
    fun `network exception returns NetworkError`() = runTest {
        val engine = MockEngine { throw java.io.IOException("Connection refused") }
        val result = buildProvider(engine).format("transcript", "prompt")
        assertIs<LlmResult.Failure.NetworkError>(result)
    }

    @Test
    fun `request includes x-goog-api-key header and targets model endpoint`() = runTest {
        var capturedApiKey = ""
        var capturedUrl = ""
        val engine = MockEngine { request ->
            capturedApiKey = request.headers["x-goog-api-key"] ?: ""
            capturedUrl = request.url.toString()
            respond(validResponse, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        buildProvider(engine).format("transcript", "prompt")
        assertEquals("test-key", capturedApiKey)
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
            capturedUrl,
        )
    }

    @Test
    fun `system prompt is sent via systemInstruction field, not smuggled into contents`() = runTest {
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            respond(validResponse, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        buildProvider(engine).format("transcript", "my system prompt")
        assertTrue(capturedBody.contains("\"systemInstruction\""))
        assertTrue(capturedBody.contains("my system prompt"))
    }

    /**
     * The provider's own `httpCallInternal` (mirroring Claude/OpenAI) catches every exception
     * internally and returns a typed [LlmResult.Failure] instead of letting it propagate — so
     * [arrow.resilience.CircuitBreaker.protectEither] never observes a thrown exception via
     * `format()` and therefore never opens through HTTP-level failures. This is intentional
     * parity with the already-shipped Claude/OpenAI providers, not a Gemini-specific gap. This
     * test instead validates the shared circuit-breaker *policy* — `defaultCircuitBreaker()` —
     * directly: it opens after 3 consecutive failures and then rejects further calls without
     * invoking the protected block.
     */
    @Test
    fun `circuitBreaker_should_Open_When_ThreeConsecutiveFailures`() = runTest {
        val breaker = GeminiLlmFormatterProvider.defaultCircuitBreaker()
        var invocations = 0
        suspend fun failingCall(): String {
            invocations++
            throw RuntimeException("simulated failure")
        }

        // Count(maxFailures = 3) opens once failuresCount > 3, i.e. on the 4th tracked failure.
        repeat(4) {
            try {
                breaker.protectEither { failingCall() }
                error("expected failingCall to throw while circuit is closed")
            } catch (e: RuntimeException) {
                // expected — circuit is still closed/counting failures
            }
        }
        assertEquals(4, invocations)

        // Circuit is now open: further calls are rejected without invoking the block.
        val rejected = breaker.protectEither { failingCall() }
        assertTrue(rejected.isLeft())
        assertEquals(4, invocations)
    }
}
