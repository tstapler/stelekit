// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import dev.stapler.stelekit.llm.LlmProviderAvailability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Table-driven coverage of [mapMlKitFeatureStatus] — the pure, SDK-independent mapping that
 * fixes the `checkEligible()`/`format()` tri-state mismatch (plan.md Task 4.1b/4.1d). Runs in
 * `businessTest`/`jvmTest`; no ML Kit SDK, `GenerativeModel`, or Robolectric dependency.
 */
class MlKitAvailabilityMappingTest {

    private data class Case(
        val name: String,
        val statusCode: Int?,
        val expected: LlmProviderAvailability,
    )

    private val cases = listOf(
        Case(
            name = "AVAILABLE (3) -> Available",
            statusCode = 3,
            expected = LlmProviderAvailability.Available,
        ),
        Case(
            name = "DOWNLOADABLE (1) -> Preparing",
            statusCode = 1,
            expected = LlmProviderAvailability.Preparing(
                "On-device model is downloading — this can take 15–30 minutes on first use"
            ),
        ),
        Case(
            name = "DOWNLOADING (2) -> Preparing",
            statusCode = 2,
            expected = LlmProviderAvailability.Preparing(
                "On-device model is downloading — this can take 15–30 minutes on first use"
            ),
        ),
        Case(
            name = "UNAVAILABLE (0), clean response -> Unavailable, not retryable",
            statusCode = 0,
            expected = LlmProviderAvailability.Unavailable(
                "On-device AI is not supported on this device",
                retryable = false,
            ),
        ),
        Case(
            name = "null (checkStatus threw) -> Unavailable, retryable",
            statusCode = null,
            expected = LlmProviderAvailability.Unavailable(
                "Not yet available — check back in a few minutes",
                retryable = true,
            ),
        ),
        Case(
            name = "unrecognized status code -> Unavailable, retryable",
            statusCode = 99,
            expected = LlmProviderAvailability.Unavailable(
                "Not yet available — check back in a few minutes",
                retryable = true,
            ),
        ),
    )

    @Test
    fun `mapFeatureStatus_should_ReturnExpectedAvailability_ForEachFeatureStatusValue`() {
        for (case in cases) {
            assertEquals(case.expected, mapMlKitFeatureStatus(case.statusCode), "case: ${case.name}")
        }
    }

    @Test
    fun `mapFeatureStatus_should_MarkOnlyCleanUnavailable_AsNonRetryable`() {
        val cleanUnavailable = mapMlKitFeatureStatus(0)
        assertTrue(cleanUnavailable is LlmProviderAvailability.Unavailable)
        assertFalse((cleanUnavailable as LlmProviderAvailability.Unavailable).retryable)

        val thrown = mapMlKitFeatureStatus(null)
        assertTrue(thrown is LlmProviderAvailability.Unavailable)
        assertTrue((thrown as LlmProviderAvailability.Unavailable).retryable)

        val unrecognized = mapMlKitFeatureStatus(-1)
        assertTrue(unrecognized is LlmProviderAvailability.Unavailable)
        assertTrue((unrecognized as LlmProviderAvailability.Unavailable).retryable)
    }
}
