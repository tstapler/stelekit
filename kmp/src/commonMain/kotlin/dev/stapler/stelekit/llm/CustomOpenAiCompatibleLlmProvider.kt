// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.voice.LlmFormatterProvider
import dev.stapler.stelekit.voice.LlmProviderSupport
import dev.stapler.stelekit.voice.OpenAiLlmFormatterProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * Generic [LlmProvider] for any OpenAI-compatible custom endpoint (Ollama, LM Studio,
 * OpenRouter, current-generation/v1-GA Azure OpenAI) — covers vendors without one bespoke
 * `LlmProvider` per vendor, per [CustomProviderConfig].
 *
 * [config.baseUrl] is normalized via [LlmProviderSupport.normalizeBaseUrl] before use, so a
 * trailing slash or `/v1` suffix (some Settings-UI presets, e.g. Ollama's default
 * `http://localhost:11434/v1`, include one) doesn't produce a broken `.../v1/v1/...` URL —
 * [OpenAiLlmFormatterProvider] (which [formatter] wraps) appends `/v1/chat/completions` itself,
 * and this class appends `/v1/models` for [checkAvailability]/[fetchAvailableModels] using the
 * same convention.
 *
 * Explicitly **out of scope**: legacy (pre-v1-GA) Azure OpenAI, which uses a
 * deployment-path URL shape plus an `api-version` query parameter and an `api-key` header
 * instead of `Authorization: Bearer`. That is a structurally different auth/URL scheme, not
 * a variation of the generic OpenAI-compatible shape handled here — do not special-case it
 * in this class.
 */
class CustomOpenAiCompatibleLlmProvider(
    private val config: CustomProviderConfig,
    private val apiKey: String?,
    private val httpClient: HttpClient,
) : LlmProvider {

    override val id: String = "custom:${config.id}"
    override val displayName: String = config.displayName
    override val kind: LlmProviderKind = LlmProviderKind.REMOTE
    override val formatter: LlmFormatterProvider = OpenAiLlmFormatterProvider(
        httpClient = httpClient,
        apiKey = apiKey ?: "",
        baseUrl = config.baseUrl,
        model = config.model,
        allowInsecureHttp = config.allowInsecureHttp,
    )

    private val modelsUrl = "${LlmProviderSupport.normalizeBaseUrl(config.baseUrl)}/v1/models"

    override suspend fun checkAvailability(): LlmProviderAvailability {
        return try {
            val response = httpClient.get(modelsUrl) { applyAuth() }
            when {
                response.status.value in 200..299 -> LlmProviderAvailability.Available
                response.status.value == 401 || response.status.value == 403 ->
                    LlmProviderAvailability.Unavailable("Authentication failed", retryable = false)
                else ->
                    LlmProviderAvailability.Unavailable(
                        "Unexpected response (${response.status.value})",
                        retryable = true,
                    )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LlmProviderAvailability.Unavailable("Could not reach ${config.baseUrl}", retryable = true)
        }
    }

    /**
     * Best-effort model-list probe, doubling as connectivity/compatibility validation for the
     * Settings UI's "fetch models" action (Epic 6 Story 6.3).
     */
    suspend fun fetchAvailableModels(): Either<DomainError.NetworkError, List<String>> {
        return try {
            val response = httpClient.get(modelsUrl) { applyAuth() }
            if (response.status.value in 200..299) {
                response.body<OpenAiModelsListResponse>().data.map { it.id }.right()
            } else {
                DomainError.NetworkError.HttpError(
                    response.status.value,
                    "HTTP ${response.status.value}",
                ).left()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            DomainError.NetworkError.RequestFailed(e.message ?: "Network error").left()
        } catch (e: Exception) {
            DomainError.NetworkError.RequestFailed(e.message ?: "Unexpected error").left()
        }
    }

    private fun HttpRequestBuilder.applyAuth() {
        if (!apiKey.isNullOrBlank()) {
            headers { append(HttpHeaders.Authorization, "Bearer $apiKey") }
        }
    }
}

@Serializable
private data class OpenAiModelsListResponse(val data: List<OpenAiModelEntry> = emptyList())

@Serializable
private data class OpenAiModelEntry(val id: String)
