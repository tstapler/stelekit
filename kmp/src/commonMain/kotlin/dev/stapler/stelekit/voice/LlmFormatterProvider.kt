// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

sealed interface LlmResult {
    data class Success(val formattedText: String, val isLikelyTruncated: Boolean = false) : LlmResult
    sealed interface Failure : LlmResult {
        data class ApiError(val code: Int, val message: String) : Failure
        data object NetworkError : Failure

        /**
         * The provider's content-safety guardrails rejected the input/prompt itself — distinct
         * from [ApiError]/[NetworkError] because this is not "try again later" or "fix your
         * credentials," it's "this specific input was refused." Introduced by Epic 5 (iOS
         * on-device / Apple Foundation Models): Apple's on-device guardrails can reject a
         * programmatically-constructed system prompt, not just user content (see
         * `project_plans/llm-service/decisions/ADR-013-ios-on-device-llm-swift-shim.md`). Other
         * providers may adopt this case later instead of folding guardrail rejections into
         * [ApiError].
         */
        data class ContentRejected(val reason: String) : Failure
    }
}

fun interface LlmFormatterProvider {
    suspend fun format(transcript: String, systemPrompt: String): LlmResult
}

class NoOpLlmFormatterProvider : LlmFormatterProvider {
    override suspend fun format(transcript: String, systemPrompt: String): LlmResult =
        LlmResult.Success(transcript)
}
