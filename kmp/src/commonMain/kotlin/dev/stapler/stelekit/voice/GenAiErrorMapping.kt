// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

/** Mirrors `com.google.mlkit.genai.common.GenAiException.ErrorCode.BUSY` (value = 9). */
private const val GENAI_ERROR_CODE_BUSY = 9

/** Mirrors `com.google.mlkit.genai.common.GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED` (value = 30). */
private const val GENAI_ERROR_CODE_BACKGROUND_USE_BLOCKED = 30

/**
 * Pure, SDK-independent mapping from ML Kit's `GenAiException.ErrorCode` int constants
 * (`com.google.mlkit.genai.common.GenAiException.ErrorCode`, an `@IntDef` annotation, not a
 * Kotlin enum) to [LlmResult.Failure].
 *
 * Mirrors [mapMlKitFeatureStatus]'s shape (MA10 — adversarial code review of PR #197):
 * deliberately takes a plain `Int` rather than the SDK's `GenAiException`/`@ErrorCode`-annotated
 * type so this function has zero dependency on the (Android-only) `genai-common` artifact and
 * can be unit-tested in `businessTest`/`jvmTest` without mocking `GenerativeModel` or running
 * under Robolectric. [MlKitLlmFormatterProvider.format] calls this with the raw
 * `GenAiException.errorCode` int and `GenAiException.message` when a `GenAiException` is caught.
 *
 * `BACKGROUND_USE_BLOCKED` (foreground-only inference) and `BUSY` (per-app quota) are both
 * retryable, expected conditions — see pitfalls.md §2.1 — not bugs, so they get a distinct,
 * actionable [LlmResult.Failure.OnDeviceUnavailable] instead of the generic
 * `LlmResult.Failure.ApiError` fallback used for every other/unrecognized error code.
 */
fun mapGenAiErrorCode(errorCode: Int, message: String?): LlmResult.Failure = when (errorCode) {
    GENAI_ERROR_CODE_BACKGROUND_USE_BLOCKED -> LlmResult.Failure.OnDeviceUnavailable(
        "On-device AI requires the app to be in the foreground",
        retryable = true,
    )
    GENAI_ERROR_CODE_BUSY -> LlmResult.Failure.OnDeviceUnavailable(
        "On-device AI is busy — try again shortly",
        retryable = true,
    )
    else -> LlmResult.Failure.ApiError(-1, "On-device LLM error: $message")
}
