// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.tags

import dev.stapler.stelekit.llm.LlmProviderAvailability
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Clock

/**
 * Stateless, wall-clock-bounded poll loop over an [LlmProviderAvailability] probe. Mirrors
 * GitHubDeviceFlowClient.pollForToken's shape (kmp/src/commonMain/kotlin/dev/stapler/
 * stelekit/git/GitHubDeviceFlowClient.kt:96-140) deliberately: a plain suspend function
 * with no owned CoroutineScope, so it is directly unit-testable under
 * kotlinx.coroutines.test.runTest with virtual time instead of fighting
 * TagSuggestionViewModel's real Dispatchers.Default scope (NFR-3).
 *
 * Pitfall #2 (research/pitfalls.md): [checkAvailability] MUST be a lightweight status
 * probe only (LlmProvider.checkAvailability() / MlKitLlmFormatterProvider.checkAvailability())
 * — NEVER the suggestion/format() call. format()'s DOWNLOADABLE branch fires
 * generateContent() as a side effect to kick off the AICore download; calling it on every
 * poll tick would re-trigger that side effect every DEFAULT_POLL_INTERVAL_MS.
 *
 * Resilience contract: a single [checkAvailability] tick that throws (including [Throwable]
 * subtypes such as [OutOfMemoryError] or a native binder crash — not just [Exception]) is
 * treated as a transient failure, logged, and the loop keeps polling — mirroring
 * GitHubDeviceFlowClient.pollForToken's per-tick `catch (e: IOException)` / `catch (e:
 * Exception)` clauses (kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/
 * GitHubDeviceFlowClient.kt:159-169), which likewise back off and continue rather than abort
 * on a single failed attempt. This is deliberately widened to `Throwable` here (unlike
 * `pollForToken`'s `Exception`) because `MlKitLlmFormatterProvider.checkAvailability()` only
 * catches `Exception` internally — an `Error` subtype would otherwise propagate uncaught
 * through this loop into `TagSuggestionViewModel`'s `CoroutineExceptionHandler`, which
 * replaces the *entire* `_state` with `TagSuggestionState.Error(...)`, discarding
 * already-visible local chip suggestions for what may be a single transient tick.
 */
object TagAvailabilityPoller {
    const val DEFAULT_POLL_INTERVAL_MS = 4_000L
    /** ADR-001: interim desk-research estimate — see decisions/ADR-001-poll-deadline-estimate.md */
    const val DEFAULT_POLL_DEADLINE_MS = 120_000L
    const val CAPTION_ESCALATION_THRESHOLD_MS = 45_000L

    const val ESCALATED_WAIT_CAPTION = "Still downloading — this can take a few minutes the first time."
    const val STALLED_REASON = "Taking longer than expected"

    private val logger = Logger("TagAvailabilityPoller")

    /**
     * Polls [checkAvailability] every [intervalMs] until it reports [LlmProviderAvailability.Available]
     * or a non-retryable [LlmProviderAvailability.Unavailable] (FR-4 — permanent failure, stop
     * immediately), or until [deadlineMs] of wall-clock time elapses (FR-2). Calls
     * [onStatusUpdate] exactly once when elapsed time crosses [escalationThresholdMs] — never on
     * every tick — so the UI never reads as a ticking readout (research/ux.md accessibility
     * requirement: at most 3 total caption changes for the whole wait). A [checkAvailability]
     * tick that throws is treated as transient (logged, loop continues) rather than propagated
     * — see the resilience contract in this object's class-level KDoc.
     *
     * [startedAtOverride] (pre-mortem P1 #1/#2 fix): when null (the default), behaves exactly as
     * before — `startedAt` is "now," i.e. a truly first-ever poll for this block/session. When
     * the caller passes a non-null epoch-millis value (`TagSuggestionViewModel.runLlmSuggest`
     * passes its session-scoped `downloadFirstObservedAtMs`), `startedAt` is pinned to that
     * value instead, so a SECOND or LATER invocation (block-switch-and-return, or a manual
     * retry) computes its escalation/deadline math relative to the ORIGINAL first-observed
     * time, not a fresh "now" — this is what makes block-switching and repeated manual retries
     * not silently reset the elapsed-time clock. See plan.md's Pattern Decisions row "Should the
     * poll loop's elapsed-time math reset on every relaunch?".
     */
    suspend fun pollUntilAvailable(
        checkAvailability: suspend () -> LlmProviderAvailability,
        onStatusUpdate: (LlmSuggestionStatus) -> Unit,
        deadlineMs: Long = DEFAULT_POLL_DEADLINE_MS,
        intervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
        escalationThresholdMs: Long = CAPTION_ESCALATION_THRESHOLD_MS,
        startedAtOverride: Long? = null,
    ): LlmProviderAvailability {
        // Elapsed time is tracked by accumulating [intervalMs] per completed delay() tick,
        // NOT by re-reading Clock.System.now() inside the loop. Clock.System.now() is read
        // exactly once here (only when startedAtOverride is non-null) to fold in time that
        // already elapsed before this invocation. This matters for testability (NFR-3):
        // kotlinx.coroutines.test's runTest virtualizes delay() but has no way to virtualize
        // Clock.System — a loop that repeatedly re-queried Clock.System.now() as its exit
        // condition would busy-spin at full CPU under runTest (delay() resolves virtually
        // instantly, but the real-wall-clock condition only becomes false once REAL time
        // reaches the deadline), which both defeats "virtual time, no real sleep" and, for
        // deadlineMs on the order of DEFAULT_POLL_DEADLINE_MS, exceeds runTest's real-time
        // dispatch-timeout watchdog outright. Accumulating ticks keeps production behavior
        // equivalent (delay() genuinely takes intervalMs of real time outside of tests) while
        // making the loop resolve in true virtual time under test.
        val initialElapsedMs = startedAtOverride
            ?.let { Clock.System.now().toEpochMilliseconds() - it }
            ?: 0L
        // If startedAtOverride already implies we're past the escalation threshold (a resumed
        // poll after a long block-switch or retry), don't re-fire onStatusUpdate — the caller
        // (runLlmSuggest) already shows the escalated caption as its initial caption in that
        // case (see Task 4.1.2), so a second announcement here would be a redundant live-region
        // update, not a new one.
        var escalated = initialElapsedMs >= escalationThresholdMs
        var elapsedMs = initialElapsedMs

        while (elapsedMs < deadlineMs) {
            delay(intervalMs)
            elapsedMs += intervalMs

            val availability = try {
                checkAvailability()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Transient tick failure — log and keep polling. Do NOT propagate: one bad
                // tick (e.g. a momentary AICore binder hiccup) must not collapse the whole
                // Ready state via TagSuggestionViewModel's CoroutineExceptionHandler.
                logger.warn("checkAvailability() threw on a poll tick, continuing to poll", e)
                null
            }

            if (availability is LlmProviderAvailability.Available) return availability
            if (availability is LlmProviderAvailability.Unavailable && !availability.retryable) return availability

            if (!escalated && elapsedMs >= escalationThresholdMs) {
                escalated = true
                onStatusUpdate(LlmSuggestionStatus.Pending(ESCALATED_WAIT_CAPTION))
            }
        }
        return LlmProviderAvailability.Unavailable(STALLED_REASON, retryable = true)
    }
}
