// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.resilience.RetryPolicies
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.errors.NoRemoteRepositoryException
import org.eclipse.jgit.transport.URIish
import java.net.SocketException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests [runGitTransportOpWithRetry]'s `onStateChange` production (git-sync-resilience Story
 * 4.1.2, Task 4.1.2b) — the [GitTransportRetryState] transitions Step 5 and the Android foreground
 * notification both render from.
 */
class GitTransportRetryStateTest {

    private fun transientFailure() =
        TransportException("Software caused connection abort", SocketException("Software caused connection abort"))

    private fun permanentFailure() =
        TransportException("not found", NoRemoteRepositoryException(URIish(), "repo-not-found"))

    @Test
    fun `onStateChange emits Attempting then Retrying on a Transient failure, matching the real attempt sequence`() = runTest {
        val states = mutableListOf<GitTransportRetryState>()
        var callCount = 0

        val result: Either<DomainError.GitError, Unit> = runGitTransportOpWithRetry(
            schedule = RetryPolicies.gitTransportTransientImmediate,
            onStateChange = { states += it },
            beforeRetry = {},
            onAuthFailed = { e -> DomainError.GitError.AuthFailed(e.message ?: "Authentication failed") },
            onFailed = { e -> DomainError.GitError.CloneFailed(e.message ?: "Clone failed") },
            onExhausted = { attempts, last -> DomainError.GitError.RetryExhausted(attempts, last) },
        ) {
            callCount++
            if (callCount < 2) throw transientFailure()
            Unit.right()
        }

        assertIs<Either.Right<Unit>>(result)
        assertEquals(2, states.size, "expected Attempting (start) then Retrying (after the 1st failure), no Idle/terminal state on success")
        assertIs<GitTransportRetryState.Attempting>(states[0])
        val retrying = assertIs<GitTransportRetryState.Retrying>(states[1])
        assertEquals(1, retrying.attempt)
        assertEquals(GIT_TRANSPORT_RETRY_MAX_ATTEMPTS, retrying.max)
    }

    @Test
    fun `onStateChange emits NonRetryableFailure directly from Attempting with no intervening Retrying state when the first failure classifies Permanent`() = runTest {
        val states = mutableListOf<GitTransportRetryState>()

        val result: Either<DomainError.GitError, Unit> = runGitTransportOpWithRetry(
            schedule = RetryPolicies.gitTransportTransientImmediate,
            onStateChange = { states += it },
            beforeRetry = { error("beforeRetry must not run for a Permanent failure") },
            onAuthFailed = { e -> DomainError.GitError.AuthFailed(e.message ?: "Authentication failed") },
            onFailed = { e -> DomainError.GitError.CloneFailed(e.message ?: "Clone failed") },
            onExhausted = { attempts, last -> DomainError.GitError.RetryExhausted(attempts, last) },
        ) {
            throw permanentFailure()
        }

        assertIs<Either.Left<DomainError.GitError>>(result)
        assertEquals(2, states.size)
        assertIs<GitTransportRetryState.Attempting>(states[0])
        val nonRetryable = assertIs<GitTransportRetryState.NonRetryableFailure>(states[1])
        assertEquals(NonRetryableReason.NOT_FOUND, nonRetryable.reason)
        assertTrue(states.none { it is GitTransportRetryState.Retrying }, "a Permanent failure must never enter Retrying")
    }

    @Test
    fun `onStateChange tags an auth-shaped Permanent failure distinctly from a not-found one`() = runTest {
        val states = mutableListOf<GitTransportRetryState>()

        runGitTransportOpWithRetry<Unit, Long>(
            schedule = RetryPolicies.gitTransportTransientImmediate,
            onStateChange = { states += it },
            beforeRetry = {},
            onAuthFailed = { e -> DomainError.GitError.AuthFailed(e.message ?: "Authentication failed") },
            onFailed = { e -> DomainError.GitError.CloneFailed(e.message ?: "Clone failed") },
            onExhausted = { attempts, last -> DomainError.GitError.RetryExhausted(attempts, last) },
        ) {
            throw TransportException("not authorized", RuntimeException("401"))
        }

        val nonRetryable = assertIs<GitTransportRetryState.NonRetryableFailure>(states.last())
        assertEquals(NonRetryableReason.AUTH, nonRetryable.reason)
    }

    @Test
    fun `onStateChange emits Exhausted once every retry attempt is spent`() = runTest {
        val states = mutableListOf<GitTransportRetryState>()

        val result: Either<DomainError.GitError, Unit> = runGitTransportOpWithRetry(
            schedule = RetryPolicies.gitTransportTransient,
            onStateChange = { states += it },
            beforeRetry = {},
            onAuthFailed = { e -> DomainError.GitError.AuthFailed(e.message ?: "Authentication failed") },
            onFailed = { e -> DomainError.GitError.FetchFailed(e.message ?: "Fetch failed") },
            onExhausted = { attempts, last -> DomainError.GitError.RetryExhausted(attempts, last) },
        ) {
            throw transientFailure()
        }

        assertIs<Either.Left<DomainError.GitError>>(result)
        assertIs<GitTransportRetryState.Exhausted>(states.last())
        assertEquals(5, states.count { it is GitTransportRetryState.Retrying }, "one Retrying transition per retry attempt granted")
    }
}
