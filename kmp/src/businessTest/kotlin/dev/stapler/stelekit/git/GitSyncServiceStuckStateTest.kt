// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A sync that dies mid-pipeline (exception from the git backend, or cancellation) must not leave
 * [SyncState] in Fetching/Merging/Pushing/Committing: the status sheet disables "Sync now" for those
 * states, so a stale one makes the graph unsyncable until the app restarts.
 */
class GitSyncServiceStuckStateTest {
    private class Behind : StubGitRepository() {
        var onMerge: suspend () -> Unit = {}
        override suspend fun hasDetachedHead(config: GitConfig) = false
        override suspend fun removeStaleLockFile(config: GitConfig) = Unit.right()
        override suspend fun status(config: GitConfig) = GitStatus(false, emptyList(), emptyList()).right()
        override suspend fun fetch(config: GitConfig) = FetchResult(hasRemoteChanges = true, remoteCommitCount = 1).right()
        override suspend fun merge(config: GitConfig): Either<DomainError.GitError, MergeResult> {
            onMerge()
            return MergeResult(false, emptyList(), emptyList(), 1).right()
        }
        override suspend fun push(config: GitConfig) = Unit.right()
    }

    private fun service(git: Behind) = buildGitSyncTestService(
        gitRepository = git,
        configRepository = StubConfigRepository(Either.Right(sampleConfig)),
        isOnline = { true },
    )

    @Test
    fun aThrowingMergeDoesNotLeaveTheStateMerging() = runTest {
        val git = Behind().apply { onMerge = { error("boom") } }
        val svc = service(git)

        runCatching { svc.sync("g") }

        val state = svc.syncState.value
        assertTrue(state !is SyncState.Merging, "state stuck at $state")
        assertIs<SyncState.Error>(state)
    }

    @Test
    fun aCancelledSyncDoesNotLeaveTheStateMerging() = runTest {
        val entered = CompletableDeferred<Unit>()
        val git = Behind().apply { onMerge = { entered.complete(Unit); CompletableDeferred<Unit>().await() } }
        val svc = service(git)

        val job = launch(start = CoroutineStart.UNDISPATCHED) { svc.sync("g") }
        entered.await()
        job.cancel()
        job.join()

        assertEquals(SyncState.Idle, svc.syncState.value)
    }
}
