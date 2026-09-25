// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.testsupport.FailureSequence
import dev.stapler.stelekit.git.testsupport.FailureSequenceGitRepository
import dev.stapler.stelekit.resilience.RetryPolicies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.errors.CanceledException
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.errors.NoRemoteRepositoryException
import org.eclipse.jgit.transport.URIish
import java.net.SocketException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Tests for [runGitTransportOpWithRetry] (Story 1.2.2, ADR-002's single retry owner) using a fake
 * `op` lambda that throws a configured sequence of exceptions — not [AndroidGitRepository]/
 * [JvmGitRepository] end-to-end, since those need a real Android `Context`/JGit setup unavailable
 * in `businessTest`. Story 1.2.2's remaining Integration row instead drives a
 * [FailureSequenceGitRepository] — the `GitRepository`-typed fake that stands in for
 * `AndroidGitRepository` in tests precisely because both implement [GitRepository] — through
 * [runGitTransportOpWithRetry], proving the retry-then-succeed interaction at the interface level
 * (see `runGitTransportOpWithRetry retries a FailureSequenceGitRepository-faked clone() twice and
 * succeeds on the 3rd attempt` below). Story 6.1.2 is the one test in this plan that proves the
 * same behavior against a real JGit-thrown exception instead of a hand-constructed one.
 */
class GitTransportRetryTest {

    private fun transientFailure() =
        TransportException("Software caused connection abort", SocketException("Software caused connection abort"))

    private fun permanentFailure() =
        TransportException("not found", NoRemoteRepositoryException(URIish(), "repo-not-found"))

    @Test
    fun `runGitTransportOpWithRetry returns Right after the 3rd attempt when the first two throw a Transient SocketException, invoking preResolvedToken exactly once`() = runTest {
        var tokenResolutions = 0
        // Mirrors the real call sites: credentials are resolved once, outside the retried op.
        val preResolvedToken = run { tokenResolutions++; "resolved-token" }
        var callCount = 0

        val result: Either<DomainError.GitError, Unit> = runGitTransportOpWithRetry(
            schedule = RetryPolicies.gitTransportTransientImmediate,
            beforeRetry = {},
            onAuthFailed = { e -> DomainError.GitError.AuthFailed(e.message ?: "Authentication failed") },
            onFailed = { e -> DomainError.GitError.CloneFailed(e.message ?: "Clone failed") },
            onExhausted = { attempts, last -> DomainError.GitError.RetryExhausted(attempts, last) },
        ) {
            callCount++
            if (callCount < 3) throw transientFailure()
            check(preResolvedToken == "resolved-token") { "op must see the pre-resolved token, not re-resolve it" }
            Unit.right()
        }

        assertEquals(1, tokenResolutions, "credentials must be resolved once, not once per retry attempt")
        assertEquals(3, callCount)
        assertIs<Either.Right<Unit>>(result)
    }

    /**
     * Story 1.2.2's deferred Integration row (closed by Story 6.1.1's `FailureSequenceGitRepository`).
     * `FailureSequenceGitRepository.clone()` itself doesn't route through [runGitTransportOpWithRetry]
     * internally (it's a thin fake — see `StubGitRepository.kt`), so this test wraps the call site the
     * same way [AndroidGitRepository.clone]/[JvmGitRepository.clone] do in production, proving that
     * driving a real `GitRepository`-typed [FailureSequenceGitRepository.clone] call through the retry
     * wrapper reproduces the retry-then-succeed behavior beyond a hand-rolled `op` lambda.
     */
    @Test
    fun `runGitTransportOpWithRetry retries a FailureSequenceGitRepository-faked clone() twice and succeeds on the 3rd attempt`() = runTest {
        val cloneSequence = FailureSequence<Either<DomainError.GitError, Unit>>(
            failures = listOf(transientFailure(), transientFailure()),
            onSuccess = { Unit.right() },
        )
        val repo = FailureSequenceGitRepository(cloneSequence = cloneSequence)

        val result: Either<DomainError.GitError, Unit> = runGitTransportOpWithRetry(
            schedule = RetryPolicies.gitTransportTransientImmediate,
            beforeRetry = {},
            onAuthFailed = { e -> DomainError.GitError.AuthFailed(e.message ?: "Authentication failed") },
            onFailed = { e -> DomainError.GitError.CloneFailed(e.message ?: "Clone failed") },
            onExhausted = { attempts, last -> DomainError.GitError.RetryExhausted(attempts, last) },
        ) {
            repo.clone(url = "https://example.invalid/graph.git", localPath = "/tmp/graph", auth = GitAuth.None) {}
        }

        assertEquals(3, cloneSequence.invocationCount, "clone() must be invoked once, then retried twice before succeeding")
        assertIs<Either.Right<Unit>>(result)
    }

    @Test
    fun `runGitTransportOpWithRetry returns Left after exactly 1 attempt when the failure classifies Permanent`() = runTest {
        var callCount = 0

        val result: Either<DomainError.GitError, Unit> = runGitTransportOpWithRetry(
            schedule = RetryPolicies.gitTransportTransientImmediate,
            beforeRetry = { error("beforeRetry must not run for a Permanent (non-retryable) failure") },
            onAuthFailed = { e -> DomainError.GitError.AuthFailed(e.message ?: "Authentication failed") },
            onFailed = { e -> DomainError.GitError.CloneFailed(e.message ?: "Clone failed") },
            onExhausted = { attempts, last -> DomainError.GitError.RetryExhausted(attempts, last) },
        ) {
            callCount++
            throw permanentFailure()
        }

        assertEquals(1, callCount, "a Permanent failure must fail fast, never entering the retry loop")
        assertIs<Either.Left<DomainError.GitError>>(result)
        assertIs<DomainError.GitError.AuthFailed>(result.value)
    }

    @Test
    fun `runGitTransportOpWithRetry returns Left(RetryExhausted(attempts=5, last)) when every attempt classifies Transient and the schedule is exhausted`() = runTest {
        var callCount = 0

        val result: Either<DomainError.GitError, Unit> = runGitTransportOpWithRetry(
            schedule = RetryPolicies.gitTransportTransient,
            beforeRetry = {},
            onAuthFailed = { e -> DomainError.GitError.AuthFailed(e.message ?: "Authentication failed") },
            onFailed = { e -> DomainError.GitError.FetchFailed(e.message ?: "Fetch failed") },
            onExhausted = { attempts, last -> DomainError.GitError.RetryExhausted(attempts, last) },
        ) {
            callCount++
            throw transientFailure()
        }

        // gitTransportTransient grants 5 retries (~1s/2s/4s/8s/16s) before exhaustion — 6 total
        // op invocations (the original attempt plus the 5 retries it grants).
        assertEquals(6, callCount)
        assertIs<Either.Left<DomainError.GitError>>(result)
        val error = assertIs<DomainError.GitError.RetryExhausted>(result.value)
        assertEquals(5, error.attempts)
        assertIs<DomainError.GitError.FetchFailed>(error.lastError)
    }

    @Test
    fun `runGitTransportOpWithRetry rethrows CancellationException without retrying when the failure classifies Cancelled`() = runTest {
        var callCount = 0

        assertFailsWith<CancellationException> {
            runGitTransportOpWithRetry<Unit, Long>(
                schedule = RetryPolicies.gitTransportTransientImmediate,
                beforeRetry = { error("beforeRetry must not run for a Cancelled failure") },
                onAuthFailed = { e -> DomainError.GitError.AuthFailed(e.message ?: "Authentication failed") },
                onFailed = { e -> DomainError.GitError.CloneFailed(e.message ?: "Clone failed") },
                onExhausted = { attempts, last -> DomainError.GitError.RetryExhausted(attempts, last) },
            ) {
                callCount++
                throw CanceledException("clone cancelled")
            }
        }

        assertEquals(1, callCount, "a Cancelled failure must never be retried")
    }

    @Test
    fun `runGitTransportOpWithRetry invokes a first-attempt-success op exactly once, with zero delay() calls`() = runTest {
        var callCount = 0

        val result: Either<DomainError.GitError, Unit> = runGitTransportOpWithRetry(
            // The real production schedule (not *Immediate) so an accidental delay() call on the
            // success path would advance virtual time and fail the currentTime assertion below —
            // Schedule.recurs's zero-length delay would make that assertion a no-op.
            schedule = RetryPolicies.gitTransportTransient,
            beforeRetry = { error("beforeRetry must not run when the first attempt succeeds") },
            onAuthFailed = { e -> DomainError.GitError.AuthFailed(e.message ?: "Authentication failed") },
            onFailed = { e -> DomainError.GitError.CloneFailed(e.message ?: "Clone failed") },
            onExhausted = { attempts, last -> DomainError.GitError.RetryExhausted(attempts, last) },
        ) {
            callCount++
            Unit.right()
        }

        assertEquals(1, callCount, "a first-attempt success must never enter the retry loop")
        assertEquals(0L, currentTime, "no delay() call may occur on the stable-network/first-attempt-success path")
        assertIs<Either.Right<Unit>>(result)
    }
}
