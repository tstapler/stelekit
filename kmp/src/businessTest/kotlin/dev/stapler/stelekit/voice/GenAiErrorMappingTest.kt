// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Table-driven coverage of [mapGenAiErrorCode] (MA10, adversarial code review of PR #197) — the
 * pure, SDK-independent mapping extracted from [MlKitLlmFormatterProvider.format]'s
 * `GenAiException.errorCode` handling, mirroring [MlKitAvailabilityMappingTest]'s shape for
 * [mapMlKitFeatureStatus]. Runs in `businessTest`/`jvmTest`; no ML Kit SDK, `GenerativeModel`,
 * `GenAiException`, or Robolectric dependency.
 */
class GenAiErrorMappingTest {

    private data class Case(
        val name: String,
        val errorCode: Int,
        val message: String?,
        val expected: LlmResult.Failure,
    )

    private val cases = listOf(
        Case(
            name = "BACKGROUND_USE_BLOCKED (30) -> OnDeviceUnavailable, retryable",
            errorCode = 30,
            message = "background use blocked",
            expected = LlmResult.Failure.OnDeviceUnavailable(
                "On-device AI requires the app to be in the foreground",
                retryable = true,
            ),
        ),
        Case(
            name = "BUSY (9) -> OnDeviceUnavailable, retryable",
            errorCode = 9,
            message = "busy",
            expected = LlmResult.Failure.OnDeviceUnavailable(
                "On-device AI is busy — try again shortly",
                retryable = true,
            ),
        ),
        Case(
            name = "unrecognized/default error code -> generic ApiError carrying the message",
            errorCode = 4, // REQUEST_PROCESSING_ERROR — not specially handled
            message = "request processing failed",
            expected = LlmResult.Failure.ApiError(-1, "On-device LLM error: request processing failed"),
        ),
        Case(
            name = "unrecognized error code with null message -> generic ApiError",
            errorCode = -1,
            message = null,
            expected = LlmResult.Failure.ApiError(-1, "On-device LLM error: null"),
        ),
    )

    @Test
    fun `mapGenAiErrorCode_should_ReturnExpectedFailure_ForEachErrorCode`() {
        for (case in cases) {
            assertEquals(case.expected, mapGenAiErrorCode(case.errorCode, case.message), "case: ${case.name}")
        }
    }

    @Test
    fun `mapGenAiErrorCode_should_MarkKnownCodes_AsRetryableOnDeviceUnavailable`() {
        val backgroundBlocked = mapGenAiErrorCode(30, "msg")
        assertIs<LlmResult.Failure.OnDeviceUnavailable>(backgroundBlocked)
        assertTrue(backgroundBlocked.retryable)

        val busy = mapGenAiErrorCode(9, "msg")
        assertIs<LlmResult.Failure.OnDeviceUnavailable>(busy)
        assertTrue(busy.retryable)

        val unrecognized = mapGenAiErrorCode(4, "msg")
        assertIs<LlmResult.Failure.ApiError>(unrecognized)
    }
}
