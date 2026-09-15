// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import dev.stapler.stelekit.llm.LlmProviderAvailability

/** Mirrors `com.google.mlkit.genai.common.FeatureStatus.UNAVAILABLE` (value = 0). */
private const val MLKIT_FEATURE_STATUS_UNAVAILABLE = 0

/** Mirrors `com.google.mlkit.genai.common.FeatureStatus.DOWNLOADABLE` (value = 1). */
private const val MLKIT_FEATURE_STATUS_DOWNLOADABLE = 1

/** Mirrors `com.google.mlkit.genai.common.FeatureStatus.DOWNLOADING` (value = 2). */
private const val MLKIT_FEATURE_STATUS_DOWNLOADING = 2

/** Mirrors `com.google.mlkit.genai.common.FeatureStatus.AVAILABLE` (value = 3). */
private const val MLKIT_FEATURE_STATUS_AVAILABLE = 3

/**
 * Pure, SDK-independent mapping from ML Kit's `FeatureStatus` int constants
 * (`com.google.mlkit.genai.common.FeatureStatus`, an `@IntDef` annotation, not a Kotlin enum) to
 * [LlmProviderAvailability].
 *
 * Deliberately takes a plain `Int?` rather than the SDK's `@FeatureStatus`-annotated `Int` so
 * this function has zero dependency on the (Android-only) `genai-common` artifact and can be
 * unit-tested in `businessTest`/`jvmTest` without mocking `GenerativeModel` or running under
 * Robolectric (plan.md Task 4.1d). [MlKitLlmFormatterProvider] calls this with the raw
 * `Int` returned by `GenerativeModel.checkStatus()`, or `null` if that call threw.
 *
 * This is also the fix for the `checkEligible()`/`format()` tri-state mismatch pitfalls research
 * found: the old `checkEligible()` reported `DOWNLOADABLE`/`DOWNLOADING` as simply "eligible",
 * while `format()` treated those same states as an immediate failure. Both call sites now derive
 * from this single mapping.
 *
 * @param statusCode `null` means `checkStatus()` itself threw. A value outside
 * `MLKIT_FEATURE_STATUS_UNAVAILABLE..MLKIT_FEATURE_STATUS_AVAILABLE` means the SDK returned an
 * unrecognized status. Both cases are treated as "genuinely unknown right now", not "confirmed
 * unsupported" — pitfalls.md §2.1 documents a post-reset initialization window where
 * `checkStatus()` can misreport eligibility with no way to distinguish it from real
 * unavailability other than retrying later, so both map to a *retryable* [LlmProviderAvailability.Unavailable]
 * rather than a permanent one. A clean [MLKIT_FEATURE_STATUS_UNAVAILABLE] response is treated as
 * genuinely unsupported hardware and is *not* retryable.
 */
fun mapMlKitFeatureStatus(statusCode: Int?): LlmProviderAvailability = when (statusCode) {
    MLKIT_FEATURE_STATUS_AVAILABLE -> LlmProviderAvailability.Available

    MLKIT_FEATURE_STATUS_DOWNLOADABLE, MLKIT_FEATURE_STATUS_DOWNLOADING ->
        LlmProviderAvailability.Preparing(
            "On-device model is downloading — this can take 15–30 minutes on first use"
        )

    MLKIT_FEATURE_STATUS_UNAVAILABLE -> LlmProviderAvailability.Unavailable(
        reason = "On-device AI is not supported on this device",
        retryable = false,
    )

    // null (checkStatus() threw) or any value outside the four known FeatureStatus constants.
    else -> LlmProviderAvailability.Unavailable(
        reason = "Not yet available — check back in a few minutes",
        retryable = true,
    )
}
