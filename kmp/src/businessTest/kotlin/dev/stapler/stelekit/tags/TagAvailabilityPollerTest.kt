// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.tags

import dev.stapler.stelekit.llm.LlmProviderAvailability
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class TagAvailabilityPollerTest {

    @Test
    fun `pollUntilAvailable returns immediately once Available is observed`() = runTest {
        var calls = 0
        val result = TagAvailabilityPoller.pollUntilAvailable(
            checkAvailability = { calls++; if (calls >= 3) LlmProviderAvailability.Available
                                   else LlmProviderAvailability.Preparing("downloading") },
            onStatusUpdate = {},
        )
        assertIs<LlmProviderAvailability.Available>(result)
        assertEquals(3, calls)
    }

    @Test
    fun `pollUntilAvailable returns retryable Unavailable when deadline is reached`() = runTest {
        val result = TagAvailabilityPoller.pollUntilAvailable(
            checkAvailability = { LlmProviderAvailability.Preparing("still downloading") },
            onStatusUpdate = {},
            deadlineMs = 12_000L,
            intervalMs = 4_000L,
        )
        assertIs<LlmProviderAvailability.Unavailable>(result)
        assertTrue(result.retryable)
        assertEquals("Taking longer than expected", result.reason)
    }

    @Test
    fun `pollUntilAvailable stops immediately on non-retryable Unavailable`() = runTest {
        var calls = 0
        val result = TagAvailabilityPoller.pollUntilAvailable(
            checkAvailability = { calls++; LlmProviderAvailability.Unavailable("Not supported", retryable = false) },
            onStatusUpdate = { fail("must not push a status update for a permanent failure") },
        )
        assertIs<LlmProviderAvailability.Unavailable>(result)
        assertFalse(result.retryable)
        assertEquals(1, calls)
    }

    @Test
    fun `pollUntilAvailable escalates the caption exactly once after 45s`() = runTest {
        val updates = mutableListOf<LlmSuggestionStatus>()
        TagAvailabilityPoller.pollUntilAvailable(
            checkAvailability = { LlmProviderAvailability.Preparing("still downloading") },
            onStatusUpdate = { updates += it },
            deadlineMs = 120_000L,
            intervalMs = 4_000L,
            escalationThresholdMs = 45_000L,
        )
        val pendingUpdates = updates.filterIsInstance<LlmSuggestionStatus.Pending>()
        assertEquals(1, pendingUpdates.size, "caption must change exactly once before the terminal state")
        assertEquals(
            "Still downloading — this can take a few minutes the first time.",
            pendingUpdates.single().caption,
        )
    }

    @Test
    fun `pollUntilAvailable treats a thrown checkAvailability as a transient tick and keeps polling`() = runTest {
        var calls = 0
        val result = TagAvailabilityPoller.pollUntilAvailable(
            checkAvailability = {
                calls++
                when (calls) {
                    2 -> throw IllegalStateException("simulated AICore binder crash")
                    3 -> LlmProviderAvailability.Available
                    else -> LlmProviderAvailability.Preparing("downloading")
                }
            },
            onStatusUpdate = {},
        )
        assertIs<LlmProviderAvailability.Available>(result)
        assertEquals(3, calls)
    }

    @Test
    fun `pollUntilAvailable measures elapsed time from startedAtOverride, not from invocation time`() = runTest {
        val now = 1_000_000L
        val startedAtOverride = now - 90_000L // pretend the model has already been "downloading" for 90s

        val updates = mutableListOf<LlmSuggestionStatus>()
        val result = TagAvailabilityPoller.pollUntilAvailable(
            checkAvailability = { LlmProviderAvailability.Preparing("still downloading") },
            onStatusUpdate = { updates += it },
            deadlineMs = 120_000L,
            intervalMs = 4_000L,
            escalationThresholdMs = 45_000L,
            startedAtOverride = startedAtOverride,
        )
        assertIs<LlmProviderAvailability.Unavailable>(result)
        assertTrue(result.retryable)
        // 90s already elapsed + 120s deadline means only 30s of *this* invocation's ticks run
        // (30_000 / 4_000 = 7.5 -> 8 ticks), not a fresh 120s/30 ticks.
        assertTrue(updates.none { it is LlmSuggestionStatus.Pending },
            "no escalation update should fire mid-loop — 90s already exceeds the 45s threshold " +
            "before the loop even starts, so 'escalated' starts true and the caller is expected " +
            "to have already shown the escalated caption itself")
    }
}
