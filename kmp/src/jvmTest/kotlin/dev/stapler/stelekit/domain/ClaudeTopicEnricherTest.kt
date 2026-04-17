package dev.stapler.stelekit.domain

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
        ClaudeTopicEnricher(apiKey = "test-key", httpClient = client)

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

    @Test
    fun timeout_throwsCancellationException() = runTest {
        // Use a MockEngine that hangs indefinitely (but timeout fires quickly)
        val engine = MockEngine { _ ->
            // Simulate network hang by blocking — runTest's test scope will cancel via timeout
            kotlinx.coroutines.awaitCancellation()
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(lenientJson) }
        }
        val enricher = ClaudeTopicEnricher(apiKey = "test-key", httpClient = client)

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            withTimeout(1L) {
                enricher.enhance("some text", localSuggestions)
            }
        }
    }
}
