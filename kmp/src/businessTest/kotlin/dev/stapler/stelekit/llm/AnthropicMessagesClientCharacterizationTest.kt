package dev.stapler.stelekit.llm

import dev.stapler.stelekit.voice.ClaudeLlmFormatterProvider
import dev.stapler.stelekit.voice.LlmResult
import dev.stapler.stelekit.wiring.WiringInputs
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Pins the wire format both Claude text features send, so the shared-client extraction cannot change it. */
class AnthropicMessagesClientCharacterizationTest {
    private val ok = """{"content":[{"type":"text","text":"hello"}]}"""

    private class Captured(val url: String, val apiKey: String?, val version: String?, val body: String)

    private fun capture(request: HttpRequestData) = Captured(
        url = request.url.toString(),
        apiKey = request.headers["x-api-key"],
        version = request.headers["anthropic-version"],
        body = (request.body as TextContent).text,
    )

    private suspend fun send(maxTokensOverride: Int?, transcript: String): Pair<Captured, LlmResult> {
        var seen: Captured? = null
        val engine = MockEngine { request ->
            seen = capture(request)
            respond(ok, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val result = ClaudeLlmFormatterProvider(http, "test-key", maxTokensOverride = maxTokensOverride)
            .format(transcript, "SYS \"prompt\"")
        return seen!! to result
    }

    @Test
    fun anthropicClient_should_SendByteIdenticalTextRequests_When_FormatterAndEnricherUseSharedClient() = runTest {
        val prefix = """{"model":"claude-haiku-4-5-20251001","max_tokens":"""
        val suffix = ""","system":"SYS \"prompt\"","messages":[{"role":"user","content":"Follow the instructions in the system prompt."}]}"""

        val (voice, voiceResult) = send(maxTokensOverride = null, transcript = "one two three")
        assertEquals("https://api.anthropic.com/v1/messages", voice.url)
        assertEquals("test-key", voice.apiKey)
        assertEquals("2023-06-01", voice.version)
        assertEquals(prefix + "512" + suffix, voice.body)
        assertIs<LlmResult.Success>(voiceResult)

        val (enricher, _) = send(maxTokensOverride = 256, transcript = "one two three")
        assertEquals(prefix + "256" + suffix, enricher.body)
    }

    @Test
    fun anthropicClient_should_HaveExactlyOneProductionFileNamingTheApiHost_When_SourcesAudited() {
        val owners = WiringInputs.productionSources.filterValues { "api.anthropic.com" in it }.keys
        assertEquals(1, owners.size, "files naming api.anthropic.com: $owners")
        assertTrue(owners.single().endsWith("llm/AnthropicMessagesClient.kt"), owners.single())
    }
}
