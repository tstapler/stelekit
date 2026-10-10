// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import org.eclipse.jgit.api.errors.CanceledException
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.errors.NoRemoteRepositoryException
import org.eclipse.jgit.transport.URIish
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Unit + integration tests for [classifyGitFailure] (Story 1.1.1) — the exception-classification
 * fix for the pre-existing bug where a plain [SocketException] (a transient network drop) was
 * misrouted to [DomainError.GitError.AuthFailed] by [runGitTransportOp]'s old type-only
 * `TransportException` catch. See `research/stack.md` §4 for the full taxonomy table this test
 * enumerates.
 */
class GitOperationSupportClassifierTest {

    // ── Transient: network-level causes anywhere in the chain ──────────────────────────────

    @Test
    fun `classifyGitFailure returns Transient when TransportException wraps a SocketException`() {
        val e = TransportException("Software caused connection abort", SocketException("Software caused connection abort"))

        assertEquals(GitFailureClass.Transient, classifyGitFailure(e))
    }

    @Test
    fun `classifyGitFailure returns Transient when TransportException wraps a SocketTimeoutException`() {
        val e = TransportException("timed out", SocketTimeoutException("Read timed out"))

        assertEquals(GitFailureClass.Transient, classifyGitFailure(e))
    }

    @Test
    fun `classifyGitFailure returns Transient when TransportException wraps an UnknownHostException`() {
        val e = TransportException("unresolved", UnknownHostException("git.example.invalid"))

        assertEquals(GitFailureClass.Transient, classifyGitFailure(e))
    }

    @Test
    fun `classifyGitFailure returns Transient when TransportException wraps an EOFException nested two levels deep`() {
        val e = TransportException("stream ended", RuntimeException("wrapper", EOFException("unexpected end of stream")))

        assertEquals(GitFailureClass.Transient, classifyGitFailure(e))
    }

    // ── Permanent: auth/not-found causes, and the fail-closed default ──────────────────────

    @Test
    fun `classifyGitFailure returns Permanent when TransportException wraps NoRemoteRepositoryException`() {
        val e = TransportException("repo-not-found", NoRemoteRepositoryException(URIish(), "repo-not-found"))

        assertEquals(GitFailureClass.Permanent, classifyGitFailure(e))
    }

    @Test
    fun `classifyGitFailure returns Permanent for an HTTP 401-shaped message with no recognized cause`() {
        val e = TransportException("not authorized (401)")

        assertEquals(GitFailureClass.Permanent, classifyGitFailure(e))
    }

    @Test
    fun `classifyGitFailure returns Permanent for an unrecognized exception type, failing closed`() {
        val e = IllegalStateException("some future JGit exception shape this taxonomy has never seen")

        assertEquals(GitFailureClass.Permanent, classifyGitFailure(e))
    }

    // ── Cancelled: never retried ─────────────────────────────────────────────────────────────

    @Test
    fun `classifyGitFailure returns Cancelled when JGit throws CanceledException, and Cancelled is never retried`() {
        val e = CanceledException("clone cancelled")

        assertEquals(GitFailureClass.Cancelled, classifyGitFailure(e))
    }

    // ── Integration: real routing through runGitTransportOp ────────────────────────────────

    /**
     * Reproduces today's actual bug end-to-end through the real catch/routing path in
     * [runGitTransportOp], not just [classifyGitFailure] in isolation.
     */
    @Test
    fun `runGitTransportOp routes a Transient-classified TransportException to onFailed, not onAuthFailed`() {
        val socketFailure = TransportException(
            "Software caused connection abort",
            SocketException("Software caused connection abort"),
        )

        val result: Either<DomainError.GitError, Unit> = runGitTransportOp(
            onAuthFailed = { e -> DomainError.GitError.AuthFailed(e.message ?: "Authentication failed") },
            onFailed = { e -> DomainError.GitError.CloneFailed(e.message ?: "Clone failed") },
        ) {
            throw socketFailure
        }

        assertIs<Either.Left<DomainError.GitError>>(result)
        assertIs<DomainError.GitError.CloneFailed>(result.value)
    }

    @Test
    fun `runGitTransportOp routes a Permanent-classified TransportException to onAuthFailed`() {
        val authFailure = TransportException("not authorized", NoRemoteRepositoryException(URIish(), "repo-not-found"))

        val result: Either<DomainError.GitError, Unit> = runGitTransportOp(
            onAuthFailed = { e -> DomainError.GitError.AuthFailed(e.message ?: "Authentication failed") },
            onFailed = { e -> DomainError.GitError.CloneFailed(e.message ?: "Clone failed") },
        ) {
            throw authFailure
        }

        assertIs<Either.Left<DomainError.GitError>>(result)
        assertIs<DomainError.GitError.AuthFailed>(result.value)
    }
}
