// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

internal object LlmProviderSupport {
    private val SENTENCE_END = setOf('.', '?', '!', ']', '\n')
    private const val MIN_TOKENS = 512
    private const val MAX_TOKENS = 4096

    fun estimateMaxTokens(transcript: String): Int {
        val wordCount = transcript.split(Regex("\\s+")).count { it.isNotBlank() }
        return (wordCount * 2).coerceIn(MIN_TOKENS, MAX_TOKENS)
    }

    fun detectTruncation(text: String): Boolean = text.isNotEmpty() && text.last() !in SENTENCE_END

    fun mapHttpError(statusCode: Int): LlmResult.Failure = when (statusCode) {
        401 -> LlmResult.Failure.ApiError(401, "Invalid API key")
        429 -> LlmResult.Failure.ApiError(429, "Rate limit exceeded")
        else -> LlmResult.Failure.ApiError(statusCode, "HTTP $statusCode")
    }
}
