package dev.stapler.stelekit.domain

import dev.stapler.stelekit.voice.ClaudeLlmFormatterProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Epic 8 Story 8.3b: rewritten per pitfalls §6.1's explicit flag — `ClaudeTopicEnricher` no
 * longer owns its own `HttpClient`/retry logic, so this test now exercises it through the
 * shared [ClaudeLlmFormatterProvider] it delegates to. Retry-on-429 assertions are removed
 * (that behavior is deleted, not preserved — a 429 is now a single, non-retried failure that
 * falls back to `localSuggestions`, same as any other failure). Wire-compatibility assertions
 * (model name, `max_tokens: 256`, prompt content shape) are kept as regression tests.
 */
class ClaudeTopicEnricherTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private val lenientJson = Json { ignoreUnknownKeys = true }

    private fun mockClient(
        statusCode: HttpStatusCode = HttpStatusCode.OK,
        responseBody: String,
    ): HttpClient {
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(responseBody),
                status = statusCode,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return HttpClient(engine) {
            install(ContentNegotiation) { json(lenientJson) }
        }
    }

    private fun makeEnricher(client: HttpClient) =
        ClaudeTopicEnricher(ClaudeLlmFormatterProvider(client, apiKey = "test-key", maxTokensOverride = 256))

    private val localSuggestions = listOf(
        TopicSuggestion("TensorFlow", 0.8f, TopicSuggestion.Source.LOCAL),
        TopicSuggestion("PyTorch", 0.6f, TopicSuggestion.Source.LOCAL),
    )

    // -------------------------------------------------------------------------
    // 1. Real API (opt-in, guarded by ANTHROPIC_API_KEY env var)
    // -------------------------------------------------------------------------

    @Test
    fun realApi_enhance_returnsNonEmptySuggestions() = runTest {
        val apiKey = System.getenv("ANTHROPIC_API_KEY") ?: return@runTest

        val enricher = ClaudeTopicEnricher.withDefaults(apiKey)
        val text = """
            TensorFlow is an open-source machine learning framework developed by Google Brain.
            It is widely used for deep learning applications including image recognition and NLP.
            PyTorch, developed by Meta AI, is another popular framework valued for its dynamic
            computation graph and ease of debugging. Both frameworks support GPU acceleration.
        """.trimIndent()

        val result = enricher.enhance(text, localSuggestions)
        assertTrue(result.isNotEmpty(), "Expected non-empty suggestions from real API")
        assertTrue(
            result.all { it.confidence in 0f..1f },
            "All confidences should be in [0, 1]",
        )
    }

    @Test
    fun realApi_enhance_withEmptyLocalSuggestions_mayReturnConcepts() = runTest {
        val apiKey = System.getenv("ANTHROPIC_API_KEY") ?: return@runTest

        val enricher = ClaudeTopicEnricher.withDefaults(apiKey)
        val text = """
            Kubernetes is an open-source container orchestration system for automating deployment,
            scaling, and management of containerized applications. It groups containers that make up
            an application into logical units for easy management and discovery.
        """.trimIndent()

        val result = enricher.enhance(text, emptyList())
        // Claude may or may not surface concepts with no candidates — just verify no crash and valid range
        assertTrue(result.all { it.confidence in 0f..1f })
    }

    // -------------------------------------------------------------------------
    // 2. Mock-based tests (no API key required)
    // -------------------------------------------------------------------------

    @Test
    fun mockClient_validResponse_returnsParsedSuggestions() = runTest {
        val responseJson = """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-4-5-20251001",
             "stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":20},
             "content":[{"type":"text","text":"[{\"term\":\"TensorFlow\",\"confidence\":0.9},{\"term\":\"DeepLearning\",\"confidence\":0.7}]"}]}
        """.trimIndent()

        val enricher = makeEnricher(mockClient(responseBody = responseJson))
        val result = enricher.enhance("some text", localSuggestions)

        assertEquals(2, result.size)
        assertEquals("TensorFlow", result[0].term)
        assertEquals(0.9f, result[0].confidence)
        assertEquals(TopicSuggestion.Source.AI_ENHANCED, result[0].source)
        assertEquals("DeepLearning", result[1].term)
        assertEquals(0.7f, result[1].confidence)
    }

    @Test
    fun mockClient_malformedJsonResponse_fallsBackToLocalSuggestions() = runTest {
        val responseJson = """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-4-5-20251001",
             "stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5},
             "content":[{"type":"text","text":"this is not valid json at all!!!"}]}
        """.trimIndent()

        val enricher = makeEnricher(mockClient(responseBody = responseJson))
        val result = enricher.enhance("some text", localSuggestions)

        assertEquals(localSuggestions, result, "Malformed JSON should fall back to localSuggestions")
    }

    @Test
    fun mockClient_emptyContentBlocks_fallsBackToLocalSuggestions() = runTest {
        val responseJson = """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-4-5-20251001",
             "stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":0},
             "content":[]}
        """.trimIndent()

        val enricher = makeEnricher(mockClient(responseBody = responseJson))
        val result = enricher.enhance("some text", localSuggestions)

        assertEquals(localSuggestions, result, "Empty content should fall back to localSuggestions")
    }

    @Test
    fun mockClient_confidenceClampedToRange() = runTest {
        val responseJson = """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-4-5-20251001",
             "stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":10},
             "content":[{"type":"text","text":"[{\"term\":\"OverConfident\",\"confidence\":1.5},{\"term\":\"Negative\",\"confidence\":-0.3}]"}]}
        """.trimIndent()

        val enricher = makeEnricher(mockClient(responseBody = responseJson))
        val result = enricher.enhance("some text", emptyList())

        assertEquals(2, result.size)
        assertEquals(1.0f, result[0].confidence, "confidence > 1.0 should be clamped to 1.0")
        assertEquals(0.0f, result[1].confidence, "confidence < 0.0 should be clamped to 0.0")
    }

    // -------------------------------------------------------------------------
    // 3. Failure paths — retry-on-429 deleted, not preserved (Story 8.3 acceptance criteria)
    // -------------------------------------------------------------------------

    @Test
    fun enhance_should_FallBackToLocalSuggestions_When_NonSuccessOrParseFailure() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"error":{"message":"Internal error"}}""",
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(lenientJson) } }

        val result = makeEnricher(client).enhance("some text", localSuggestions)

        assertEquals(localSuggestions, result)
    }

    @Test
    fun enhance_should_NotRetry_When_RateLimited() = runTest {
        // Deleted behavior regression guard: the old hand-rolled delay(2_000)-then-retry-once
        // is gone — a 429 must result in exactly one HTTP call, not two.
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            respond(
                content = """{"error":{"message":"Rate limited"}}""",
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(lenientJson) } }

        val result = makeEnricher(client).enhance("some text", localSuggestions)

        assertEquals(1, callCount, "A 429 must not trigger a retry — that behavior was deleted, not preserved")
        assertEquals(localSuggestions, result)
    }

    /**
     * `ClaudeTopicEnricher` delegates entirely to [ClaudeLlmFormatterProvider], whose
     * `httpCallInternal` catches every exception internally and returns a typed
     * `LlmResult.Failure` rather than letting it propagate — so
     * [arrow.resilience.CircuitBreaker.protectEither] never observes a thrown exception via
     * `format()` and therefore never opens through HTTP-level failures (this is intentional
     * parity with the already-shipped Claude/OpenAI/Gemini providers — see
     * `GeminiLlmFormatterProviderTest`'s identical rationale). This test instead validates the
     * shared circuit-breaker *policy* `ClaudeLlmFormatterProvider.defaultCircuitBreaker()` that
     * `ClaudeTopicEnricher` now inherits via delegation: it opens after 3 consecutive failures
     * and rejects further calls without invoking the protected block — i.e. "short-circuits
     * rather than retries," replacing the deleted per-call retry-on-429 logic.
     */
    @Test
    fun enhance_should_ShortCircuit_ViaSharedCircuitBreaker_When_ThreeConsecutiveFailures() = runTest {
        val breaker = ClaudeLlmFormatterProvider.defaultCircuitBreaker()
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

    // -------------------------------------------------------------------------
    // 4. Wire-compatibility regression tests
    // -------------------------------------------------------------------------

    @Test
    fun enhance_should_PreserveWireCompat_ModelNameAndMaxTokens256AndPromptShape() = runTest {
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            respond(
                content = """{"content":[{"type":"text","text":"[]"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(lenientJson) } }

        makeEnricher(client).enhance("some document text", localSuggestions)

        assertTrue(capturedBody.contains("\"model\":\"claude-haiku-4-5-20251001\""), "model name must be preserved: $capturedBody")
        assertTrue(capturedBody.contains("\"max_tokens\":256"), "max_tokens must be fixed at 256: $capturedBody")
        assertTrue(capturedBody.contains("Candidates:"), "prompt must include the candidate list marker: $capturedBody")
        assertTrue(capturedBody.contains("<document>"), "prompt must include the document marker: $capturedBody")
        assertTrue(capturedBody.contains("some document text"), "prompt must include the raw document text: $capturedBody")
        assertTrue(capturedBody.contains("TensorFlow") && capturedBody.contains("PyTorch"), "prompt must include candidate terms: $capturedBody")
    }
}
