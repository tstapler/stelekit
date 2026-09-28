// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.platform.NetworkMonitor
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Unit tests for [GitSyncService] covering [DomainError.GitError] → [SyncState] routing for
 * RateLimited, MergeConflict, and CredentialExpired — split out of the original 751-line
 * `GitSyncServiceTest.kt` (kotlin-file-size finding). See [GitSyncServiceTest] for early-return
 * scenarios and [GitSyncServiceConflictResolutionTest] for `resolveConflicts`/`refreshLocalStatus`.
 * Shared stubs live in `GitSyncServiceTestFixtures.kt`.
 */
class GitSyncServiceErrorRoutingTest {

    // ── TC-6: sync() when commit returns RateLimited emits SyncState.RateLimited ──

    /**
     * TC-6 (Story 1.3.3): When [GitRepository.commit] returns
     * [DomainError.GitError.RateLimited], [GitSyncService.sync] must emit
     * [SyncState.RateLimited] carrying the same `retryAfterSeconds`, not the generic
     * [SyncState.Error] the pre-Epic-1.3 fallback would have produced.
     *
     * Precondition: requires network connectivity so sync reaches the commit step (step 5).
     */
    @Test
    fun `sync when commit returns RateLimited emits SyncState RateLimited not Error`() = runTest {
        assumeTrue(
            "Skipped: requires network access for the initial NetworkMonitor.isOnline check",
            NetworkMonitor().isOnline,
        )

        val rateLimitedGitRepository = object : StubGitRepository() {
            override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> =
                GitStatus(hasLocalChanges = true, untrackedFiles = emptyList(), modifiedFiles = listOf("pages/foo.md")).right()

            override suspend fun stageSubdir(config: GitConfig): Either<DomainError.GitError, Unit> = Unit.right()

            override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> =
                DomainError.GitError.RateLimited(retryAfterSeconds = 30).left()
        }

        val service = buildGitSyncTestService(
            gitRepository = rateLimitedGitRepository,
            configRepository = StubConfigRepository(Either.Right(sampleConfig)),
        )

        val result = service.sync("test-graph")

        assertIs<Either.Left<*>>(result)
        assertIs<DomainError.GitError.RateLimited>(result.value)
        val state = service.syncState.value
        assertIs<SyncState.RateLimited>(state)
        assertEquals(30, state.retryAfterSeconds)
    }

    // ── TC-7: fetchOnly() when fetch returns RateLimited emits SyncState.RateLimited ──

    /**
     * TC-7 (Story 1.3.3): When [GitRepository.fetch] returns
     * [DomainError.GitError.RateLimited] during [GitSyncService.fetchOnly], the same
     * `RateLimited` branch must fire at this independent call site — proving all 5
     * `.onLeft` sites named in Task 1.3.3b got the branch, not just the `sync()` ones.
     *
     * Precondition: requires network connectivity so fetchOnly reaches the fetch call.
     */
    @Test
    fun `sync when fetch returns RateLimited during fetchOnly emits SyncState RateLimited`() = runTest {
        assumeTrue(
            "Skipped: requires network access for the initial NetworkMonitor.isOnline check",
            NetworkMonitor().isOnline,
        )

        val rateLimitedGitRepository = object : StubGitRepository() {
            override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> =
                DomainError.GitError.RateLimited(retryAfterSeconds = 15).left()
        }

        val service = buildGitSyncTestService(
            gitRepository = rateLimitedGitRepository,
            configRepository = StubConfigRepository(Either.Right(sampleConfig)),
        )

        val result = service.fetchOnly("test-graph")

        assertIs<Either.Left<*>>(result)
        assertIs<DomainError.GitError.RateLimited>(result.value)
        val state = service.syncState.value
        assertIs<SyncState.RateLimited>(state)
        assertEquals(15, state.retryAfterSeconds)
    }

    // ── TC-8: sync() when push returns MergeConflict emits ConflictPending, not generic Error ──

    /**
     * TC-8 (Story 3.2.3 / Task 3.2.3b): When [GitRepository.push] returns
     * [DomainError.GitError.MergeConflict] — GitHub's ref-PATCH `409`/`422` and GitLab's
     * commits-POST `400` are both mapped to this same error type before reaching
     * [GitSyncService] — [GitSyncService.sync] must emit [SyncState.ConflictPending] carrying a
     * [ConflictFile] per conflicting path, not the generic [SyncState.Error] the pre-Epic-3.2
     * push `.onLeft` would have produced (it only special-cased `RateLimited`).
     *
     * Precondition: requires network connectivity so sync reaches the push step (step 9).
     */
    @Test
    fun `sync when push returns MergeConflict emits ConflictPending not generic Error, for both GitHub and GitLab shapes`() = runTest {
        assumeTrue(
            "Skipped: requires network access for the initial NetworkMonitor.isOnline check",
            NetworkMonitor().isOnline,
        )

        // Represents both hosts' push-time conflict signal after DomainError mapping: GitHub's
        // 409/422 ref-PATCH race (single unknown path) and GitLab's 400 commits-POST race
        // (named path(s) extracted from the response body) both arrive here as the same
        // MergeConflict shape — this test exercises the shared GitSyncService routing logic,
        // not host-specific response parsing (that's WasmGitWriteServiceAlgorithmsTest's job).
        for (conflictPaths in listOf(listOf("pages/Foo.md"), listOf("<unknown — remote advanced during push>"))) {
            assertPushMergeConflictRoutesToConflictPending(conflictPaths)
        }
    }

    /** One iteration of TC-8 — extracted so the `for` loop above stays a thin driver. */
    private suspend fun assertPushMergeConflictRoutesToConflictPending(conflictPaths: List<String>) {
        val conflictingGitRepository = object : StubGitRepository() {
            override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> =
                GitStatus(hasLocalChanges = false, untrackedFiles = emptyList(), modifiedFiles = emptyList()).right()

            override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> =
                FetchResult(hasRemoteChanges = false, remoteCommitCount = 0).right()

            override suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit> =
                DomainError.GitError.MergeConflict(
                    conflictCount = conflictPaths.size,
                    conflictPaths = conflictPaths,
                ).left()
        }

        val service = buildGitSyncTestService(
            gitRepository = conflictingGitRepository,
            configRepository = StubConfigRepository(Either.Right(sampleConfig)),
        )

        val result = service.sync("test-graph")

        assertIs<Either.Left<*>>(result)
        assertIs<DomainError.GitError.MergeConflict>(result.value)
        val state = service.syncState.value
        assertIs<SyncState.ConflictPending>(state)
        assertEquals(
            conflictPaths.map { ConflictFile(it, it, emptyList()) },
            state.conflicts,
            "push-time MergeConflict must route to ConflictPending with a ConflictFile per path",
        )
    }

    // ── TC-9 (Story 3.4.3): a missing/expired token (CredentialExpired) routes to
    // SyncState.CredentialExpired, not a generic Error, at both the sync() commit step and the
    // fetchOnly() fetch step ──────────────────────────────────────────────────────────────────

    /**
     * TC-9 (Story 3.4.3 / Task 3.4.3b): when [GitRepository.commit] returns
     * [DomainError.GitError.CredentialExpired] — the shape `WasmGitWriteService.mapHttpFailure`
     * produces for a `401`, or a `403` without `Retry-After`, i.e. a missing/rejected token —
     * [GitSyncService.sync] must emit [SyncState.CredentialExpired], not the generic
     * [SyncState.Error] the pre-Story-3.4.3 fallback would have produced. Web's session-scoped PAT
     * auth (`GitAuthType.HTTPS_TOKEN`) never matches the existing `AuthFailed`+`GITHUB_OAUTH`
     * special case (`GitSyncService.kt`'s fetch-step branch), which is exactly the gap this story
     * closes by mapping straight to `CredentialExpired` instead of routing through `AuthFailed`.
     */
    @Test
    fun `sync when commit returns CredentialExpired emits SyncState CredentialExpired not Error`() = runTest {
        assumeTrue(
            "Skipped: requires network access for the initial NetworkMonitor.isOnline check",
            NetworkMonitor().isOnline,
        )

        val credentialExpiredGitRepository = object : StubGitRepository() {
            override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> =
                GitStatus(hasLocalChanges = true, untrackedFiles = emptyList(), modifiedFiles = listOf("pages/foo.md")).right()

            override suspend fun stageSubdir(config: GitConfig): Either<DomainError.GitError, Unit> = Unit.right()

            override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> =
                DomainError.GitError.CredentialExpired("Your git host rejected the configured token").left()
        }

        val service = buildGitSyncTestService(
            gitRepository = credentialExpiredGitRepository,
            configRepository = StubConfigRepository(Either.Right(sampleConfig)),
        )

        val result = service.sync("test-graph")

        assertIs<Either.Left<*>>(result)
        assertIs<DomainError.GitError.CredentialExpired>(result.value)
        assertEquals(SyncState.CredentialExpired("test-graph"), service.syncState.value)
    }

    /**
     * TC-9 (Story 3.4.3): the same [DomainError.GitError.CredentialExpired] routing at the
     * independent `fetchOnly()` fetch-step call site — proving all 5 `.onLeft` sites named in
     * Task 3.4.3b got the branch, not just `sync()`'s commit step.
     */
    @Test
    fun `fetchOnly when fetch returns CredentialExpired emits SyncState CredentialExpired not Error`() = runTest {
        assumeTrue(
            "Skipped: requires network access for the initial NetworkMonitor.isOnline check",
            NetworkMonitor().isOnline,
        )

        val credentialExpiredGitRepository = object : StubGitRepository() {
            override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> =
                DomainError.GitError.CredentialExpired("Your git host rejected the configured token").left()
        }

        val service = buildGitSyncTestService(
            gitRepository = credentialExpiredGitRepository,
            configRepository = StubConfigRepository(Either.Right(sampleConfig)),
        )

        val result = service.fetchOnly("test-graph")

        assertIs<Either.Left<*>>(result)
        assertIs<DomainError.GitError.CredentialExpired>(result.value)
        assertEquals(SyncState.CredentialExpired("test-graph"), service.syncState.value)
    }
}
