// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.resilience

import arrow.resilience.Schedule
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Drives [RetryPolicies.gitTransportTransient]/[RetryPolicies.gitTransportTransientImmediate] by
 * hand via [Schedule.step] — the same low-level API `runGitTransportOpWithRetry`
 * (`GitOperationSupport.kt`) uses internally — rather than a fake retried operation, since these
 * tests are about the schedule's own shape (git-sync-resilience Story 1.2.1), not the retry
 * wrapper built on top of it (covered by `GitTransportRetryTest`).
 */
class RetryPoliciesTest {

    @Test
    fun `gitTransportTransient schedule produces ~5 jittered attempts with delays from 1s up to 16s`() = runTest {
        val expectedOutputs = listOf(1.seconds, 2.seconds, 4.seconds, 8.seconds, 16.seconds)
        var step = RetryPolicies.gitTransportTransient.step
        val e = RuntimeException("boom")

        for (expectedOutput in expectedOutputs) {
            val decision = step(e)
            val continueDecision = decision as? Schedule.Decision.Continue
                ?: error("expected Continue for output=$expectedOutput but got $decision")
            assertEquals(expectedOutput, continueDecision.output, "logical schedule output (predicate input)")
            assertTrue(continueDecision.delay > Duration.ZERO, "jitter must produce a positive actual delay")
            step = continueDecision.step
        }
    }

    @Test
    fun `gitTransportTransient schedule terminates once a delay exceeds 16 seconds, rather than retrying forever`() = runTest {
        var step = RetryPolicies.gitTransportTransient.step
        val e = RuntimeException("boom")

        // Five Continue decisions (1s, 2s, 4s, 8s, 16s) precede the terminating Done — see the
        // "produces ~5 jittered attempts" test above for the per-step assertions.
        repeat(5) {
            val decision = step(e) as? Schedule.Decision.Continue ?: error("expected Continue before exhaustion")
            step = decision.step
        }

        val finalDecision = step(e)
        assertTrue(finalDecision is Schedule.Decision.Done, "schedule must terminate once the delay exceeds 16s, not retry forever")
    }

    @Test
    fun `gitTransportTransientImmediate applies zero delay for fast, deterministic test execution`() = runTest {
        val decision = RetryPolicies.gitTransportTransientImmediate.step(RuntimeException("boom"))
        val continueDecision = decision as? Schedule.Decision.Continue ?: error("expected Continue on first attempt")
        assertEquals(Duration.ZERO, continueDecision.delay, "test variant must not introduce any real delay")
    }
}
