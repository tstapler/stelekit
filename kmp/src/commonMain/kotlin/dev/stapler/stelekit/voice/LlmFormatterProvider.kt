// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

sealed interface LlmResult {
    data class Success(val formattedText: String, val isLikelyTruncated: Boolean = false) : LlmResult
    sealed interface Failure : LlmResult {
        data class ApiError(val code: Int, val message: String) : Failure
        data object NetworkError : Failure

        /**
         * An on-device provider (ML Kit/Gemini Nano, iOS Foundation Models) is not usable right
         * now — still downloading, foreground-only constraint violated, per-app quota hit, etc.
         * Kept distinct from [ApiError] so [reason] can be surfaced to the user verbatim instead
         * of a generic error string, and so callers can decide whether to retry based on
         * [retryable].
         */
        data class OnDeviceUnavailable(val reason: String, val retryable: Boolean) : Failure
    }
}

fun interface LlmFormatterProvider {
    suspend fun format(transcript: String, systemPrompt: String): LlmResult
}

class NoOpLlmFormatterProvider : LlmFormatterProvider {
    override suspend fun format(transcript: String, systemPrompt: String): LlmResult =
        LlmResult.Success(transcript)
}
