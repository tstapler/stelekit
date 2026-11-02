// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** How a failed git step reaches [GitSyncService.syncState]: rate limit, expired credentials, or the plain error. */
class GitSyncServicePublishFailureTest {

    private enum class Step { Commit, Fetch, Merge, Push }

    private class FailingAt(val step: Step, val error: DomainError.GitError) : StubGitRepository() {
        override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> =
            GitStatus(true, emptyList(), listOf("journals/a.md")).right()

        override suspend fun stageSubdir(config: GitConfig, excludePaths: Set<String>) = Unit.right()

        override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> =
            if (step == Step.Commit) error.left() else "sha".right()

        override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> =
            if (step == Step.Fetch) error.left() else FetchResult(hasRemoteChanges = step == Step.Merge, remoteCommitCount = 1).right()

        override suspend fun merge(config: GitConfig): Either<DomainError.GitError, MergeResult> =
            if (step == Step.Merge) error.left() else MergeResult(false, emptyList(), emptyList()).right()

        override suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit> =
            if (step == Step.Push) error.left() else Unit.right()
    }

    private fun service(repo: GitRepository, config: GitConfig = sampleConfig): GitSyncService {
        val fs = StubFileSystem()
        return GitSyncService(
            gitRepository = repo,
            graphLoader = GraphLoader(fs, InMemoryPageRepository(), InMemoryBlockRepository()),
            graphWriter = GraphWriter(fileSystem = fs),
            editLock = EditLock(),
            configRepository = StubConfigRepository(config.right()),
            networkMonitor = NetworkMonitor(),
            fileSystem = fs,
            graphId = "test-graph",
            isOnline = { true },
        )
    }

    private fun stateAfter(step: Step, error: DomainError.GitError, config: GitConfig = sampleConfig): SyncState = runBlocking {
        val svc = service(FailingAt(step, error), config)
        svc.sync("test-graph")
        svc.syncState.value.also { svc.shutdown() }
    }

    @Test
    fun `a rate limit at any step shows RateLimited with the retry delay`() {
        Step.entries.forEach { step ->
            assertEquals(SyncState.RateLimited(3600), stateAfter(step, DomainError.GitError.RateLimited(3600)), "step $step")
        }
    }

    @Test
    fun `expired credentials at any step show CredentialExpired`() {
        Step.entries.forEach { step ->
            assertEquals(SyncState.CredentialExpired("test-graph"), stateAfter(step, DomainError.GitError.CredentialExpired("x")), "step $step")
        }
    }

    @Test
    fun `an auth failure on fetch means expired only for GitHub OAuth`() {
        val oauth = sampleConfig.copy(authType = GitAuthType.GITHUB_OAUTH)
        assertEquals(SyncState.CredentialExpired("test-graph"), stateAfter(Step.Fetch, DomainError.GitError.AuthFailed("x"), oauth))
        assertEquals(SyncState.Error(DomainError.GitError.AuthFailed("x")), stateAfter(Step.Fetch, DomainError.GitError.AuthFailed("x")))
    }

    @Test
    fun `any other failure is published as a plain error`() {
        Step.entries.forEach { step ->
            assertEquals(SyncState.Error(DomainError.GitError.PushFailed("nope")), stateAfter(step, DomainError.GitError.PushFailed("nope")), "step $step")
        }
    }

    @Test
    fun `a push-time conflict routes to ConflictPending`() {
        val state = stateAfter(Step.Push, DomainError.GitError.MergeConflict(1, listOf("a.md")))
        assertEquals(listOf("a.md"), (state as SyncState.ConflictPending).conflicts.map { it.filePath })
    }

    @Test
    fun `fetchOnly publishes rate limit and expiry the same way`() = runBlocking {
        val limited = service(FailingAt(Step.Fetch, DomainError.GitError.RateLimited(3600)))
        limited.fetchOnly("test-graph")
        assertEquals(SyncState.RateLimited(3600), limited.syncState.value)
        limited.shutdown()

        val oauth = service(FailingAt(Step.Fetch, DomainError.GitError.AuthFailed("x")), sampleConfig.copy(authType = GitAuthType.GITHUB_OAUTH))
        oauth.fetchOnly("test-graph")
        assertEquals(SyncState.CredentialExpired("test-graph"), oauth.syncState.value)
        oauth.shutdown()
    }
}
