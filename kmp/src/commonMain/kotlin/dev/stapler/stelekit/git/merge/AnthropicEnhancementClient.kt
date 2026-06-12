package dev.stapler.stelekit.git.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String,
    val messages: List<AnthropicMessage>,
)

@Serializable
data class AnthropicMessage(
    val role: String,
    val content: String,
)

@Serializable
data class AnthropicResponse(
    val content: List<AnthropicContentBlock>,
    @SerialName("stop_reason") val stopReason: String? = null,
)

@Serializable
data class AnthropicContentBlock(
    val type: String,
    val text: String? = null,
)

class AnthropicEnhancementClient(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val model: String = "claude-haiku-4-5",
) : LlmEnhancementClient {

    companion object {
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val MAX_TOKENS = 4096
        private val json = Json { ignoreUnknownKeys = true }
        private val SYSTEM_PROMPT = """
            You are a merge conflict resolver for Markdown note-taking files.
            The user will give you text that contains git merge conflict markers
            (<<<<<<< LOCAL, =======, >>>>>>> REMOTE).
            Resolve each conflict section by choosing the best combination of both sides.
            For journal notes: prefer to include content from both sides, not discard either.
            Return ONLY the resolved text with NO conflict markers remaining.
            Preserve all non-conflicting lines exactly as-is.
        """.trimIndent()
    }

    override suspend fun resolveConflictMarkers(textWithMarkers: String): Either<LlmError, String> {
        return try {
            val request = AnthropicRequest(
                model = model,
                maxTokens = MAX_TOKENS,
                system = SYSTEM_PROMPT,
                messages = listOf(AnthropicMessage("user", textWithMarkers)),
            )
            val response = httpClient.post(API_URL) {
                contentType(ContentType.Application.Json)
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
                setBody(json.encodeToString(AnthropicRequest.serializer(), request))
            }
            when (response.status) {
                HttpStatusCode.OK -> {
                    val body = json.decodeFromString(AnthropicResponse.serializer(), response.bodyAsText())
                    val text = body.content.firstOrNull { it.type == "text" }?.text
                        ?: return LlmError.ApiError(200, "Empty response").left()
                    if (body.stopReason == "max_tokens") {
                        LlmError.TokenLimitExceeded.left()
                    } else {
                        text.right()
                    }
                }
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
                    LlmError.ApiError(response.status.value, response.bodyAsText()).left()
                else ->
                    LlmError.ApiError(response.status.value, response.bodyAsText()).left()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LlmError.NetworkError(e.message ?: "Unknown network error").left()
        }
    }
}
