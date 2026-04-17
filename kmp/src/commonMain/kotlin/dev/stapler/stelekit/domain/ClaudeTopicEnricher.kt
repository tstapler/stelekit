package dev.stapler.stelekit.domain

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ClaudeTopicEnricher(
    private val apiKey: String,
    private val httpClient: HttpClient,
) : TopicEnricher {

    companion object {
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val CLAUDE_MODEL = "claude-haiku-4-5-20251001"
        private const val MESSAGES_URL = "https://api.anthropic.com/v1/messages"
        private const val MAX_INPUT_CHARS = 60_000 // ~15k tokens at ~4 chars/token

        private val lenientJson = Json { ignoreUnknownKeys = true }

        fun withDefaults(apiKey: String): ClaudeTopicEnricher {
            val client = HttpClient {
                install(ContentNegotiation) { json(lenientJson) }
            }
            return ClaudeTopicEnricher(apiKey, client)
        }
    }

    override suspend fun enhance(
        rawText: String,
        localSuggestions: List<TopicSuggestion>,
    ): List<TopicSuggestion> {
        val truncatedText = rawText.take(MAX_INPUT_CHARS)
        val candidateJson = localSuggestions.joinToString { "\"${it.term}\"" }

        val prompt = buildString {
            append("You are a knowledge graph assistant. ")
            append("Given the document below and a list of candidate page names, ")
            append("return a JSON array of objects with 'term' (string) and 'confidence' (float 0-1). ")
            append("Re-rank the candidates by page-worthiness. You may add up to 5 net-new concepts. ")
            append("Return ONLY the JSON array, no markdown, no explanation.\n\n")
            append("Candidates: [$candidateJson]\n\n")
            append("<document>\n$truncatedText\n</document>")
        }

        val response = try {
            httpClient.post(MESSAGES_URL) {
                headers {
                    append("x-api-key", apiKey)
                    append("anthropic-version", ANTHROPIC_VERSION)
                }
                contentType(ContentType.Application.Json)
                setBody(
                    MessagesRequest(
                        model = CLAUDE_MODEL,
                        maxTokens = 256,
                        messages = listOf(Message(role = "user", content = prompt)),
                    ),
                )
            }
        } catch (e: Exception) {
            throw e
        }

        if (response.status.value == 429) {
            delay(2_000)
            val retry = httpClient.post(MESSAGES_URL) {
                headers {
                    append("x-api-key", apiKey)
                    append("anthropic-version", ANTHROPIC_VERSION)
                }
                contentType(ContentType.Application.Json)
                setBody(
                    MessagesRequest(
                        model = CLAUDE_MODEL,
                        maxTokens = 256,
                        messages = listOf(Message(role = "user", content = prompt)),
                    ),
                )
            }
            return parseResponse(retry.body(), localSuggestions)
        }

        val body = response.body<MessagesResponse>()
        return parseResponse(body, localSuggestions)
    }

    private fun parseResponse(
        body: MessagesResponse,
        fallback: List<TopicSuggestion>,
    ): List<TopicSuggestion> {
        val rawJson = body.content.firstOrNull()?.text ?: return fallback
        return runCatching {
            val parsed = lenientJson.decodeFromString<List<ClaudeCandidate>>(rawJson)
            parsed.map { candidate ->
                TopicSuggestion(
                    term = candidate.term,
                    confidence = candidate.confidence.coerceIn(0f, 1f),
                    source = TopicSuggestion.Source.AI_ENHANCED,
                )
            }
        }.getOrElse { fallback }
    }
}

@Serializable
private data class ClaudeCandidate(val term: String, val confidence: Float)

@Serializable
private data class MessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<Message>,
)

@Serializable
private data class Message(val role: String, val content: String)

@Serializable
private data class MessagesResponse(val content: List<ContentBlock>)

@Serializable
private data class ContentBlock(val type: String, val text: String)
