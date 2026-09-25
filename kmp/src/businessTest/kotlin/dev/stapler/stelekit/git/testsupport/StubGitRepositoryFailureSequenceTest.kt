// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.testsupport

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.GitAuth
import dev.stapler.stelekit.git.GitFailureClass
import dev.stapler.stelekit.git.runGitTransportOpWithRetry
import dev.stapler.stelekit.resilience.RetryPolicies
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.errors.NoRemoteRepositoryException
import org.eclipse.jgit.transport.URIish
import java.net.SocketException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Story 6.1.1 (git-sync-resilience) — pulled forward as a **foundational** dependency per
 * `validation.md`'s "Fault-Injection Infrastructure" section: Stories 1.2.2, 3.1.2, 4.1.2, and
 * 6.1.3 each need `StubGitRepository`'s configurable failure-sequence mode ([FailureSequence] /
 * [FailureSequenceGitRepository], added in `StubGitRepository.kt` by this same story) to exist
 * before their own tests can be written. This file proves the fake itself works, driven through
 * the already-existing [runGitTransportOpWithRetry] (Story 1.2.2, `GitOperationSupport.kt`) —
 * not a full `GitSyncService`/`GitCloneWorker` integration, which is out of this story's scope.
 *
 * **`GitTransportRetryState` substitution note**: `plan.md`'s acceptance criterion for this story
 * is phrased in terms of the UI-facing `GitTransportRetryState` sequence
 * (`Attempting → Retrying(1) → Retrying(2) → Idle/Success`), but that sealed type is a Phase 4
 * deliverable that does not exist yet on this branch. The closest available observable equivalent
 * is [runGitTransportOpWithRetry]'s own `onAttempt(attempt: Int, failure: GitFailureClass?)`
 * callback: each call with a non-null [GitFailureClass] is the signal that would drive a
 * `Retrying(N)` transition, and the final call with a `null` failure marks `Success` (or, for the
 * permanent-failure test, the run stops after exactly one `onAttempt` call, which is what would
 * drive `NonRetryableFailure` instead of `Retrying`). Phase 4's worker can replace the `onAttempt`
 * assertions below with real `GitTransportRetryState` values once that type exists — the recorded
 * `(attempt, GitFailureClass?)` sequence carries the same intent.
 */
class StubGitRepositoryFailureSequenceTest {

    private fun transientCloneFailure() =
        TransportException("Software caused connection abort", SocketException("Software caused connection abort"))

    private fun permanentCloneFailure() =
        TransportException("not found", NoRemoteRepositoryException(URIish(), "repo-not-found"))

    /** Records each [runGitTransportOpWithRetry] `onAttempt` call — see the class doc's substitution note. */
    private fun recordingAttempts(): Pair<MutableList<Pair<Int, GitFailureClass?>>, (Int, GitFailureClass?) -> Unit> {
        val transitions = mutableListOf<Pair<Int, GitFailureClass?>>()
        return transitions to { attempt, failure -> transitions.add(attempt to failure) }
    }

    @Test
    fun `StubGitRepository configured to fail twice with a Transient cause then succeed drives Attempting to Retrying(1) to Retrying(2) to Success`() = runTest {
        val cloneSequence = FailureSequence<Either<DomainError.GitError, Unit>>(
            failures = listOf(transientCloneFailure(), transientCloneFailure()),
            onSuccess = { Unit.right() },
        )
        val repo = FailureSequenceGitRepository(cloneSequence = cloneSequence)
        val (transitions, onAttempt) = recordingAttempts()

        val result: Either<DomainError.GitError, Unit> = runGitTransportOpWithRetry(
            schedule = RetryPolicies.gitTransportTransientImmediate,
            onAttempt = onAttempt,
            beforeRetry = {},
            onAuthFailed = { e -> DomainError.GitError.AuthFailed(e.message ?: "Authentication failed") },
            onFailed = { e -> DomainError.GitError.CloneFailed(e.message ?: "Clone failed") },
            onExhausted = { attempts, last -> DomainError.GitError.RetryExhausted(attempts, last) },
        ) {
            repo.clone("https://example.invalid/repo.git", "/tmp/repo", GitAuth.None, onProgress = {}, onStateChange = {})
        }

        assertIs<Either.Right<Unit>>(result)
        assertEquals(3, cloneSequence.invocationCount, "clone() must be called once per attempt: 2 failures + 1 success")
        // Attempting (fails Transient) -> Retrying(1) (fails Transient) -> Retrying(2) (succeeds).
        assertEquals(
            listOf<Pair<Int, GitFailureClass?>>(1 to GitFailureClass.Transient, 2 to GitFailureClass.Transient, 3 to null),
            transitions,
        )
    }

    @Test
    fun `StubGitRepository configured to always throw NoRemoteRepositoryException drives exactly one Attempting to NonRetryableFailure transition, never Retrying`() = runTest {
        val cloneSequence = FailureSequence<Either<DomainError.GitError, Unit>>(
            failures = listOf(permanentCloneFailure()),
            onSuccess = { error("must not be reached: a Permanent failure must never retry to success") },
        )
        val repo = FailureSequenceGitRepository(cloneSequence = cloneSequence)
        val (transitions, onAttempt) = recordingAttempts()

        val result: Either<DomainError.GitError, Unit> = runGitTransportOpWithRetry(
            schedule = RetryPolicies.gitTransportTransientImmediate,
            onAttempt = onAttempt,
            beforeRetry = { error("beforeRetry must not run: a Permanent (non-retryable) failure never enters the retry loop") },
            onAuthFailed = { e -> DomainError.GitError.AuthFailed(e.message ?: "Authentication failed") },
            onFailed = { e -> DomainError.GitError.CloneFailed(e.message ?: "Clone failed") },
            onExhausted = { attempts, last -> DomainError.GitError.RetryExhausted(attempts, last) },
        ) {
            repo.clone("https://example.invalid/repo.git", "/tmp/repo", GitAuth.None, onProgress = {}, onStateChange = {})
        }

        assertIs<Either.Left<DomainError.GitError>>(result)
        assertIs<DomainError.GitError.AuthFailed>(result.value)
        assertEquals(1, cloneSequence.invocationCount, "a Permanent failure must fail fast after exactly one attempt")
        // Exactly one Attempting -> NonRetryableFailure transition; Retrying never fires.
        assertEquals(listOf<Pair<Int, GitFailureClass?>>(1 to GitFailureClass.Permanent), transitions)
    }
}
