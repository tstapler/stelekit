// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

sealed interface LlmResult {
    data class Success(val formattedText: String, val isLikelyTruncated: Boolean = false) : LlmResult
    sealed interface Failure : LlmResult {
        data class ApiError(val code: Int, val message: String) : Failure
        data object NetworkError : Failure
    }
}

fun interface LlmFormatterProvider {
    suspend fun format(transcript: String, systemPrompt: String): LlmResult
}

class NoOpLlmFormatterProvider : LlmFormatterProvider {
    override suspend fun format(transcript: String, systemPrompt: String): LlmResult =
        LlmResult.Success(transcript)
}
