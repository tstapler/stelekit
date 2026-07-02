// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import arrow.resilience.CircuitBreaker
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json

internal object LlmProviderSupport {
    private val SENTENCE_END = setOf('.', '?', '!', ']', '\n')
    private const val MIN_TOKENS = 512
    private const val MAX_TOKENS = 4096

    /** Bracketed and unbracketed forms both included so callers with either IPv6-literal
     * host-extraction convention (with/without brackets) get correct results. */
    private val loopbackHosts = setOf("localhost", "127.0.0.1", "::1", "[::1]")

    /**
     * Exact-match loopback host check (MA6) — shared by
     * [dev.stapler.stelekit.llm.CustomProviderUrlValidation] (Settings-UI base-URL validation)
     * and [OpenAiLlmFormatterProvider] (HTTP-vs-HTTPS scheme downgrade guard) as the single
     * source of truth. Compares [host] **exactly** against a known set of loopback literals —
     * never a prefix/substring match. A prefix check like `host.startsWith("127.")` would
     * incorrectly treat a crafted hostname such as `127.0.0.1.attacker.example` as loopback;
     * DNS would actually resolve that name to an attacker-controlled server.
     */
    fun isLoopbackHost(host: String): Boolean = host in loopbackHosts

    /** Shared JSON instance used by all voice HTTP providers. */
    val voiceLenientJson = Json { ignoreUnknownKeys = true }

    fun wordCount(text: String): Int = text.split(Regex("\\s+")).count { it.isNotBlank() }

    fun estimateMaxTokens(transcript: String): Int =
        (wordCount(transcript) * 2).coerceIn(MIN_TOKENS, MAX_TOKENS)

    fun detectTruncation(text: String): Boolean = text.isNotEmpty() && text.last() !in SENTENCE_END

    fun mapHttpError(statusCode: Int): LlmResult.Failure = when (statusCode) {
        401 -> LlmResult.Failure.ApiError(401, "Invalid API key")
        429 -> LlmResult.Failure.ApiError(429, "Rate limit exceeded")
        else -> LlmResult.Failure.ApiError(statusCode, "HTTP $statusCode")
    }

    /**
     * Shared circuit-breaker default for all remote LLM formatter providers (MA4): opens after
     * 3 consecutive failures, resets after 30 seconds, with exponential backoff up to 5
     * minutes. Formerly duplicated verbatim across `ClaudeLlmFormatterProvider`,
     * `OpenAiLlmFormatterProvider`, and `GeminiLlmFormatterProvider`'s companion objects — now a
     * single source of truth; each provider's `defaultCircuitBreaker()` delegates here so the
     * existing per-provider call sites (including test call sites) keep working unchanged.
     */
    fun defaultCircuitBreaker(): CircuitBreaker = CircuitBreaker(
        openingStrategy = CircuitBreaker.OpeningStrategy.Count(maxFailures = 3),
        resetTimeout = 30.seconds,
        exponentialBackoffFactor = 2.0,
        maxResetTimeout = 5.minutes,
    )
}
