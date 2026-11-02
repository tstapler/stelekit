// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.error.DomainError
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Task 2.4d: a manual `sync()` honors `GitConfig.autoCommit`; with it off, a dirty tree is fetched but never committed or merged. */
class GitSyncServiceAutoCommitTest {

    private class Spy(var dirty: Boolean, var remoteChanges: Boolean) : StubGitRepository() {
        val calls = mutableListOf<String>()

        override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> =
            GitStatus(dirty, emptyList(), if (dirty) listOf("journals/a.md", "pages/b.md") else emptyList()).right()

        override suspend fun stageSubdir(config: GitConfig, excludePaths: Set<String>): Either<DomainError.GitError, Unit> {
            calls += "stage"
            return Unit.right()
        }

        override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> {
            calls += "commit"
            return "sha".right()
        }

        override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> {
            calls += "fetch"
            return FetchResult(remoteChanges, if (remoteChanges) 3 else 0).right()
        }

        override suspend fun merge(config: GitConfig): Either<DomainError.GitError, MergeResult> {
            calls += "merge"
            return MergeResult(false, emptyList(), listOf("journals/a.md"), mergedCommitCount = 3).right()
        }

        override suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit> {
            calls += "push"
            return Unit.right()
        }
    }

    private fun service(spy: Spy, autoCommit: Boolean): GitSyncService {
        val fs = StubFileSystem()
        return GitSyncService(
            gitRepository = spy,
            graphLoader = GraphLoader(fs, InMemoryPageRepository(), InMemoryBlockRepository()),
            graphWriter = GraphWriter(fileSystem = fs),
            editLock = EditLock(),
            configRepository = StubConfigRepository(sampleConfig.copy(autoCommit = autoCommit).right()),
            networkMonitor = NetworkMonitor(),
            fileSystem = fs,
            graphId = "test-graph",
            isOnline = { true },
        )
    }

    private fun GitSyncService.syncOnce(): Either<DomainError.GitError, SyncState.Success> = runBlocking { sync("test-graph") }

    @Test
    fun `sync_should_FetchButNeverCommitMergeOrPush_When_AutoCommitOffAndTreeDirty`() {
        val spy = Spy(dirty = true, remoteChanges = true)
        val svc = service(spy, autoCommit = false)

        val result = svc.syncOnce()

        val error = assertIs<DomainError.GitError.AutoCommitDisabled>((result as Either.Left).value)
        assertEquals(2, error.fileCount)
        assertEquals(listOf("fetch"), spy.calls, "only the fetch may run")
        assertEquals(SyncState.MergeAvailable(3), svc.syncState.value)
        assertEquals("Uncommitted changes — commit or enable auto-commit", error.message)
        svc.shutdown()
    }

    @Test
    fun `sync_should_ReturnToIdle_When_AutoCommitOffTreeDirtyAndNothingToFetch`() {
        val spy = Spy(dirty = true, remoteChanges = false)
        val svc = service(spy, autoCommit = false)

        assertIs<DomainError.GitError.AutoCommitDisabled>((svc.syncOnce() as Either.Left).value)

        assertEquals(listOf("fetch"), spy.calls)
        assertEquals(SyncState.Idle, svc.syncState.value)
        svc.shutdown()
    }

    @Test
    fun `sync_should_CommitMergeAndPush_When_AutoCommitOnAndTreeDirty`() {
        val spy = Spy(dirty = true, remoteChanges = true)
        val svc = service(spy, autoCommit = true)

        val result = svc.syncOnce()

        assertTrue(result.isRight(), result.toString())
        assertEquals(listOf("stage", "commit", "fetch", "merge", "push"), spy.calls)
        svc.shutdown()
    }

    @Test
    fun `sync_should_MergeAndPush_When_AutoCommitOffButTreeClean`() {
        val spy = Spy(dirty = false, remoteChanges = true)
        val svc = service(spy, autoCommit = false)

        val result = svc.syncOnce()

        assertTrue(result.isRight(), result.toString())
        assertEquals(listOf("fetch", "merge", "push"), spy.calls)
        svc.shutdown()
    }
}
