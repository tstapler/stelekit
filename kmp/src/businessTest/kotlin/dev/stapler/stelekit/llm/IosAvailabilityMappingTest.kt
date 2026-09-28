// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Table-driven coverage of [mapShimCodeToAvailability] over all 5 `FoundationModelsShim`
 * availability codes (Task 5.5d). Pure Kotlin, zero iOS/cinterop dependency — this is the part
 * of Epic 5's iOS on-device provider that *can* run in this repository's CI (JVM `businessTest`).
 * The cinterop call that actually produces these codes at runtime cannot be exercised here; see
 * `project_plans/llm-service/implementation/validation.md`'s "iOS Tests — CANNOT run in this
 * repo's CI" section (Task 5.5e is manual-verification-only).
 */
class IosAvailabilityMappingTest {

    @Test
    fun `mapShimCode_should_ReturnExpectedAvailability_ForEachOfFiveCodes`() {
        assertEquals(
            LlmProviderAvailability.Available,
            mapShimCodeToAvailability(code = 0, detail = null),
        )

        assertEquals(
            LlmProviderAvailability.Unavailable(
                reason = "This device doesn't support on-device AI",
                retryable = false,
            ),
            mapShimCodeToAvailability(code = 1, detail = "deviceNotEligible"),
        )

        assertEquals(
            LlmProviderAvailability.Unavailable(
                reason = "Turn on Apple Intelligence in Settings to use on-device AI",
                retryable = false,
            ),
            mapShimCodeToAvailability(code = 2, detail = "appleIntelligenceNotEnabled"),
        )

        assertEquals(
            LlmProviderAvailability.Preparing(
                detail = "The on-device model is still downloading",
            ),
            mapShimCodeToAvailability(code = 3, detail = "modelNotReady"),
        )

        assertEquals(
            LlmProviderAvailability.Unavailable(reason = "some other failure", retryable = true),
            mapShimCodeToAvailability(code = 4, detail = "some other failure"),
        )
    }

    @Test
    fun `mapShimCode_should_TreatUnknownCodes_AsRetryableOther`() {
        // Defensive: any code outside 0-4 (e.g. a future shim revision) falls into the "other"
        // branch rather than crashing — retryable=true since we don't know it's permanent.
        assertEquals(
            LlmProviderAvailability.Unavailable(reason = "Unknown", retryable = true),
            mapShimCodeToAvailability(code = 99, detail = null),
        )
    }
}
