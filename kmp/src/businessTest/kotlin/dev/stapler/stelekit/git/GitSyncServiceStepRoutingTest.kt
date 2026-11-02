// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Where each [GitSyncService.sync] step's failure lands in the returned error and [SyncState].
 * Unlike `GitSyncServiceErrorRoutingTest` these inject `isOnline = { true }`, so they never depend on
 * the machine's network and cannot be skipped by an offline runner.
 */
class GitSyncServiceStepRoutingTest {
    private val clean = GitStatus(hasLocalChanges = false, untrackedFiles = emptyList(), modifiedFiles = emptyList())
    private val dirty = GitStatus(hasLocalChanges = true, untrackedFiles = emptyList(), modifiedFiles = listOf("pages/a.md"))

    /** Records the order of git operations; every step succeeds unless a test overrides it. */
    private open class Scripted : StubGitRepository() {
        val calls = mutableListOf<String>()
        var status: GitStatus = GitStatus(false, emptyList(), emptyList())
        var fetchResult: Either<DomainError.GitError, FetchResult> = FetchResult(hasRemoteChanges = false, remoteCommitCount = 0).right()
        var mergeResult: Either<DomainError.GitError, MergeResult> = MergeResult(false, emptyList(), emptyList(), 0).right()
        var pushResult: Either<DomainError.GitError, Unit> = Unit.right()
        var commitResult: Either<DomainError.GitError, String> = "sha".right()
        var detached = false
        var lockResult: Either<DomainError.GitError, Unit> = Unit.right()

        override suspend fun hasDetachedHead(config: GitConfig) = detached
        override suspend fun removeStaleLockFile(config: GitConfig) = lockResult
        override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> = status.right()
        override suspend fun stageSubdir(config: GitConfig, excludePaths: Set<String>): Either<DomainError.GitError, Unit> =
            Unit.right().also { calls += "stage" }
        override suspend fun commit(config: GitConfig, message: String) = commitResult.also { calls += "commit" }
        override suspend fun fetch(config: GitConfig) = fetchResult.also { calls += "fetch" }
        override suspend fun merge(config: GitConfig) = mergeResult.also { calls += "merge" }
        override suspend fun push(config: GitConfig) = pushResult.also { calls += "push" }
        override suspend fun abortMerge(config: GitConfig): Either<DomainError.GitError, Unit> = Unit.right().also { calls += "abort" }
    }

    private fun service(git: Scripted, config: GitConfig = sampleConfig) = buildGitSyncTestService(
        gitRepository = git,
        configRepository = StubConfigRepository(Either.Right(config)),
        isOnline = { true },
    )

    @Test
    fun configLoadFailureIsReportedAsFetchFailedAndNothingRuns() = runTest {
        val git = Scripted()
        val svc = buildGitSyncTestService(
            gitRepository = git,
            configRepository = StubConfigRepository(Either.Left(DomainError.DatabaseError.ReadFailed("db closed"))),
            isOnline = { true },
        )

        val result = svc.sync("g")

        assertEquals(DomainError.GitError.FetchFailed("Failed to load git config: db closed"), result.leftOrNull())
        assertIs<SyncState.Error>(svc.syncState.value)
        assertEquals(emptyList(), git.calls)
    }

    @Test
    fun detachedHeadStopsBeforeAnyGitWork() = runTest {
        val git = Scripted().apply { detached = true }
        val svc = service(git)

        val result = svc.sync("g")

        assertEquals(DomainError.GitError.DetachedHead("/repo"), result.leftOrNull())
        assertEquals(SyncState.Error(DomainError.GitError.DetachedHead("/repo")), svc.syncState.value)
        assertEquals(emptyList(), git.calls)
    }

    @Test
    fun aStaleLockThatCannotBeRemovedStopsTheSync() = runTest {
        val lockError = DomainError.GitError.StaleLockFile("/repo/.git/index.lock")
        val git = Scripted().apply { lockResult = lockError.left() }
        val svc = service(git)

        assertEquals(lockError, svc.sync("g").leftOrNull())
        assertEquals(SyncState.Error(lockError), svc.syncState.value)
        assertEquals(emptyList(), git.calls)
    }

    @Test
    fun anUpToDateCleanRepoFetchesThenPushesAndReportsNoCommits() = runTest {
        val git = Scripted()
        val svc = service(git)

        val result = svc.sync("g")

        val success = assertIs<SyncState.Success>(result.getOrNull())
        assertEquals(0, success.localCommitsMade)
        assertEquals(0, success.remoteCommitsMerged)
        assertEquals(listOf("fetch", "push"), git.calls)
    }

    @Test
    fun localChangesAreStagedAndCommittedBeforeFetching() = runTest {
        val git = Scripted().apply { status = dirty }

        val result = service(git).sync("g")

        assertEquals(1, assertIs<SyncState.Success>(result.getOrNull()).localCommitsMade)
        assertEquals(listOf("stage", "commit", "fetch", "push"), git.calls)
    }

    @Test
    fun aFailedCommitStopsTheSyncWithTheCommitError() = runTest {
        val git = Scripted().apply { status = dirty; commitResult = DomainError.GitError.CommitFailed("hook rejected").left() }
        val svc = service(git)

        assertEquals(DomainError.GitError.CommitFailed("hook rejected"), svc.sync("g").leftOrNull())
        assertEquals(SyncState.Error(DomainError.GitError.CommitFailed("hook rejected")), svc.syncState.value)
        assertEquals(listOf("stage", "commit"), git.calls)
    }

    @Test
    fun anExpiredCredentialAtCommitIsSurfacedAsCredentialExpired() = runTest {
        val git = Scripted().apply { status = dirty; commitResult = DomainError.GitError.CredentialExpired("expired").left() }
        val svc = service(git)

        svc.sync("g")

        assertEquals(SyncState.CredentialExpired("g"), svc.syncState.value)
    }

    @Test
    fun anAuthFailureOnFetchMeansExpiredOnlyForOAuthConnections() = runTest {
        val oauth = service(Scripted().apply { fetchResult = DomainError.GitError.AuthFailed("401").left() }, sampleConfig.copy(authType = GitAuthType.GITHUB_OAUTH))
        val token = service(Scripted().apply { fetchResult = DomainError.GitError.AuthFailed("401").left() }, sampleConfig.copy(authType = GitAuthType.HTTPS_TOKEN))

        oauth.sync("g")
        token.sync("g")

        assertEquals(SyncState.CredentialExpired("g"), oauth.syncState.value)
        assertEquals(SyncState.Error(DomainError.GitError.AuthFailed("401")), token.syncState.value)
    }

    @Test
    fun fetchFailureStopsBeforeMergeOrPush() = runTest {
        val git = Scripted().apply { fetchResult = DomainError.GitError.FetchFailed("timeout").left() }

        val result = service(git).sync("g")

        assertEquals(DomainError.GitError.FetchFailed("timeout"), result.leftOrNull())
        assertEquals(listOf("fetch"), git.calls)
    }

    @Test
    fun remoteChangesAreMergedAndTheMergeCommitCountIsReported() = runTest {
        val git = Scripted().apply {
            fetchResult = FetchResult(hasRemoteChanges = true, remoteCommitCount = 9).right()
            mergeResult = MergeResult(false, emptyList(), emptyList(), mergedCommitCount = 3).right()
        }

        val result = service(git).sync("g")

        assertEquals(3, assertIs<SyncState.Success>(result.getOrNull()).remoteCommitsMerged)
        assertEquals(listOf("fetch", "merge", "push"), git.calls)
    }

    @Test
    fun withoutAMergeCountTheFetchCountIsReported() = runTest {
        val git = Scripted().apply {
            fetchResult = FetchResult(hasRemoteChanges = true, remoteCommitCount = 9).right()
            mergeResult = MergeResult(false, emptyList(), emptyList(), mergedCommitCount = null).right()
        }

        assertEquals(9, assertIs<SyncState.Success>(service(git).sync("g").getOrNull()).remoteCommitsMerged)
    }

    @Test
    fun mergeFailuresRouteLikeFetchFailuresAndNeverPush() = runTest {
        val expired = Scripted().apply {
            fetchResult = FetchResult(true, 1).right()
            mergeResult = DomainError.GitError.CredentialExpired("expired").left()
        }
        val broken = Scripted().apply {
            fetchResult = FetchResult(true, 1).right()
            mergeResult = DomainError.GitError.FetchFailed("corrupt pack").left()
        }
        val expiredSvc = service(expired)
        val brokenSvc = service(broken)

        expiredSvc.sync("g")
        brokenSvc.sync("g")

        assertEquals(SyncState.CredentialExpired("g"), expiredSvc.syncState.value)
        assertEquals(SyncState.Error(DomainError.GitError.FetchFailed("corrupt pack")), brokenSvc.syncState.value)
        assertEquals(listOf("fetch", "merge"), expired.calls)
        assertEquals(listOf("fetch", "merge"), broken.calls)
    }

    @Test
    fun anUnresolvedMergeConflictIsPendingAndNothingIsPushed() = runTest {
        val conflict = ConflictFile(filePath = "/repo/pages/a.md", wikiRelativePath = "pages/a.md", hunks = emptyList())
        val git = Scripted().apply {
            fetchResult = FetchResult(true, 1).right()
            mergeResult = MergeResult(hasConflicts = true, conflicts = listOf(conflict), changedFiles = emptyList()).right()
        }
        val svc = service(git)

        val result = svc.sync("g")

        assertEquals(DomainError.GitError.MergeConflict(1, listOf("/repo/pages/a.md")), result.leftOrNull())
        assertEquals(SyncState.ConflictPending(listOf(conflict)), svc.syncState.value)
        assertEquals(listOf("fetch", "merge"), git.calls)
    }

    @Test
    fun aPushTimeConflictBecomesConflictPendingForThoseFiles() = runTest {
        val git = Scripted().apply { pushResult = DomainError.GitError.MergeConflict(1, listOf("pages/a.md")).left() }
        val svc = service(git)

        svc.sync("g")

        assertEquals(SyncState.ConflictPending(listOf(ConflictFile("pages/a.md", "pages/a.md", emptyList()))), svc.syncState.value)
    }

    @Test
    fun otherPushFailuresAreGenericErrorsAndExpiredCredentialsAreDistinguished() = runTest {
        val failing = service(Scripted().apply { pushResult = DomainError.GitError.PushFailed("rejected").left() })
        val expired = service(Scripted().apply { pushResult = DomainError.GitError.CredentialExpired("expired").left() })

        failing.sync("g")
        expired.sync("g")

        assertEquals(SyncState.Error(DomainError.GitError.PushFailed("rejected")), failing.syncState.value)
        assertEquals(SyncState.CredentialExpired("g"), expired.syncState.value)
    }
}
