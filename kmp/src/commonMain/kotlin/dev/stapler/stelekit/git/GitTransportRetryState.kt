// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

/**
 * Single sealed state describing "what's happening with this clone/fetch/push right now"
 * (git-sync-resilience Story 4.1.2) — the one source of truth Step 5 (`GitSetupStep5TestAndSave.kt`)
 * and the Android foreground-service notification (`GitCloneWorker.kt`) both render from, so the
 * two surfaces can never disagree and neither ever shows a raw exception string. Produced by
 * `GitOperationSupport.runGitTransportOpWithRetry`'s internal retry loop via its `onStateChange`
 * callback — see that function's kdoc for exactly when each transition fires.
 *
 * Mirrors the existing `SyncState`/`DeviceFlowPollState` sealed-state idiom already used elsewhere
 * in this codebase.
 */
sealed interface GitTransportRetryState {
    /** No clone/fetch/push in flight. */
    data object Idle : GitTransportRetryState

    /**
     * An attempt (the first, or a retry that has started transferring again) is in flight.
     * [foregroundPromoted] is `false` only when `GitCloneWorker`'s `setForeground()` was denied by
     * the OS for the current run (Task 3.1.2e) — Step 5 must not imply a background-survival
     * guarantee that isn't actually in effect for that run.
     */
    data class Attempting(val progress: CloneProgress, val foregroundPromoted: Boolean = true) : GitTransportRetryState

    /**
     * A transient failure was classified and a retry has been scheduled/is about to run.
     * [attempt] is 1-indexed (the Nth retry granted so far, matching
     * `DomainError.GitError.RetryExhausted.attempts`'s own counting convention — not the total
     * op-invocation count). [max] mirrors `RetryPolicies.gitTransportTransient`'s documented retry
     * budget (see `GIT_TRANSPORT_RETRY_MAX_ATTEMPTS`) — the generic `Schedule<Throwable, D>` type
     * has no way to report its own attempt count, so this is the one schedule actually used for
     * clone/fetch/push, not a value derived from the schedule itself. [progress] is this retry
     * attempt's own transfer progress once it starts moving again (per ADR-001, a retried
     * shallow-clone attempt restarts its transfer from zero — this is progress *within* the
     * current retry attempt, never a resumed byte count from a prior attempt) — `null` while this
     * attempt hasn't transferred anything yet (e.g. still in the backoff wait).
     */
    data class Retrying(val attempt: Int, val max: Int, val progress: CloneProgress?) : GitTransportRetryState

    /**
     * Widening a shallow clone to full history (`unshallow`/deepen, Story 2.1.4) is in progress.
     * Backend-only in this plan — no UI entry point calls it yet (see plan.md's Domain Glossary),
     * so no production call site emits this today; included here for completeness of the sealed
     * type per the Domain Glossary's exact shape.
     */
    data class ResumingDeepen(val percent: Int?) : GitTransportRetryState

    /** Every automatic retry attempt was exhausted (`DomainError.GitError.RetryExhausted`) — a
     * retryable failure the user can manually retry from the same checkpoint. [reason] is a
     * diagnostic string (e.g. for logs) — Step 5's UI never renders it directly, always showing
     * fixed authored copy instead (Story 4.1.3's AC). [maxAttempts] is the real retry budget that
     * was actually exhausted for this run — `runGitTransportOpWithRetry` always passes its own
     * live `maxAttempts` value here (mirroring `GIT_TRANSPORT_RETRY_MAX_ATTEMPTS`), so Step 5's
     * "Couldn't finish after N attempts" copy is templated from this field and can never drift
     * from the real constant (Story 4.1.3 spec-compliance fix). Defaults to
     * `GIT_TRANSPORT_RETRY_MAX_ATTEMPTS`'s current value (5) only for call sites that construct
     * this state without exercising that copy. */
    data class Exhausted(val reason: String, val maxAttempts: Int = 5) : GitTransportRetryState

    /**
     * A `GitFailureClass.Permanent` failure — never retried. [reason] is one of
     * [NonRetryableReason]'s tag constants (not a raw exception message) so the UI can pick between
     * distinct authored copy for an auth failure vs. a repo-not-found failure without ever
     * rendering `e.message` (Story 4.1.3's AC / UX Acceptance Test 9).
     */
    data class NonRetryableFailure(val reason: String) : GitTransportRetryState
}

/**
 * Semantic tags for [GitTransportRetryState.NonRetryableFailure.reason] — produced by
 * `GitOperationSupport.permanentFailureReasonTag`, consumed by `GitSetupStep5TestAndSave.kt` to
 * pick the right authored copy (`design/ux.md`'s Step 3 AC7: distinct auth vs. not-found copy).
 */
object NonRetryableReason {
    const val AUTH = "auth"
    const val NOT_FOUND = "not-found"
    /** A permanent failure that isn't a transport/auth error (e.g. disk full, internal JGit error). */
    const val OTHER = "other"
}
