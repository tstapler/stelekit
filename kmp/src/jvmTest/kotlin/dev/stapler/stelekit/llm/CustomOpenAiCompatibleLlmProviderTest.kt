// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

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
import kotlin.test.assertTrue

class CustomOpenAiCompatibleLlmProviderTest {

    private fun config(
        id: String = "ollama-1",
        baseUrl: String = "http://localhost:11434",
        allowInsecureHttp: Boolean = true,
    ) = CustomProviderConfig(
        id = id,
        displayName = "Ollama (local)",
        baseUrl = baseUrl,
        model = "llama3.1",
        allowInsecureHttp = allowInsecureHttp,
    )

    private fun buildProvider(
        engine: MockEngine,
        apiKey: String? = null,
        config: CustomProviderConfig = config(),
    ): CustomOpenAiCompatibleLlmProvider {
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return CustomOpenAiCompatibleLlmProvider(config, apiKey, client)
    }

    @Test
    fun `id_should_BePrefixedWithCustom_ForRegistryNamespacing`() {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK) }
        val provider = buildProvider(engine, config = config(id = "my-endpoint"))
        assertEquals("custom:my-endpoint", provider.id)
        assertEquals(LlmProviderKind.REMOTE, provider.kind)
    }

    @Test
    fun `checkAvailability_should_ReturnAvailable_When_200OnModelsEndpoint`() = runTest {
        var capturedUrl = ""
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond("""{"object":"list","data":[{"id":"llama3.1"}]}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val result = buildProvider(engine).checkAvailability()
        assertIs<LlmProviderAvailability.Available>(result)
        assertEquals("http://localhost:11434/v1/models", capturedUrl)
    }

    @Test
    fun `checkAvailability_should_ReturnUnavailableRetryable_When_ConnectionRefused`() = runTest {
        val engine = MockEngine { throw java.io.IOException("Connection refused") }
        val result = buildProvider(engine).checkAvailability()
        assertIs<LlmProviderAvailability.Unavailable>(result)
        assertTrue(result.retryable)
    }

    @Test
    fun `checkAvailability_should_ReturnUnavailableNonRetryable_When_401`() = runTest {
        val engine = MockEngine {
            respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized, headersOf("Content-Type", "application/json"))
        }
        val result = buildProvider(engine).checkAvailability()
        assertIs<LlmProviderAvailability.Unavailable>(result)
        assertEquals(false, result.retryable)
    }

    @Test
    fun `checkAvailability_should_ReturnUnavailableRetryable_When_UnexpectedNon2xx`() = runTest {
        val engine = MockEngine {
            respond("""{"error":"server error"}""", HttpStatusCode.InternalServerError, headersOf("Content-Type", "application/json"))
        }
        val result = buildProvider(engine).checkAvailability()
        assertIs<LlmProviderAvailability.Unavailable>(result)
        assertTrue(result.retryable)
    }

    @Test
    fun `fetchAvailableModels_should_ParseModelIdList_When_ValidResponse`() = runTest {
        val engine = MockEngine {
            respond(
                """{"object":"list","data":[{"id":"llama3.1"},{"id":"mistral"}]}""",
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        }
        val result = buildProvider(engine).fetchAvailableModels()
        assertTrue(result.isRight())
        result.fold(
            ifLeft = { throw AssertionError("expected Right, got Left: $it") },
            ifRight = { models -> assertEquals(listOf("llama3.1", "mistral"), models) },
        )
    }

    @Test
    fun `fetchAvailableModels_should_ReturnLeft_When_NonSuccessStatus`() = runTest {
        val engine = MockEngine {
            respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized, headersOf("Content-Type", "application/json"))
        }
        val result = buildProvider(engine).fetchAvailableModels()
        assertTrue(result.isLeft())
    }

    @Test
    fun `checkAvailability_should_SendAuthorizationHeader_When_ApiKeyPresent`() = runTest {
        var capturedAuth = ""
        val engine = MockEngine { request ->
            capturedAuth = request.headers["Authorization"] ?: ""
            respond("""{"data":[]}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        buildProvider(engine, apiKey = "sk-test").checkAvailability()
        assertEquals("Bearer sk-test", capturedAuth)
    }

    @Test
    fun `checkAvailability_should_OmitAuthorizationHeader_When_ApiKeyNull`() = runTest {
        var sawAuthHeader = true
        val engine = MockEngine { request ->
            sawAuthHeader = request.headers.contains("Authorization")
            respond("""{"data":[]}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        buildProvider(engine, apiKey = null).checkAvailability()
        assertEquals(false, sawAuthHeader)
    }
}
