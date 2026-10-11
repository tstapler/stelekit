// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * git-sync-resilience Story 5.1.1: [GitSyncService.fetchOnly] must bracket
 * [GitSyncBusyCounter.begin]/[GitSyncBusyCounter.end] exactly like [GitSyncService.sync] already
 * does, including the finally-equivalent guarantee that `end()` runs even when the call throws.
 *
 * Uses [runBlocking] with [CompletableDeferred] gates, not `runTest`/virtual time — `fetchOnly()`
 * always redispatches onto the real `PlatformDispatcher.IO` (`Dispatchers.IO` on JVM), so a
 * virtual-time test dispatcher can't observe its progress deterministically. Same precedent as
 * `GitSyncBusyCounterSharedWiringTest` (androidUnitTest).
 *
 * Lives in `businessTest`, not alongside [GitSyncBusyCounter]'s own unit tests in `commonTest`,
 * because reaching `GitRepository.fetch()` requires `NetworkMonitor.isOnline` to be true first,
 * and skipping via [assumeTrue] when it isn't (this repo's existing precedent — see
 * `GitSyncServiceTest.kt`'s TC-1) needs JUnit, which `commonTest` deliberately doesn't depend on
 * (it's shared with wasmJs/iOS targets).
 */
class GitSyncBusyCounterFetchOnlyTest {

    private fun buildService(
        counter: GitSyncBusyCounter,
        gitRepository: GitRepository,
    ): GitSyncService {
        val stubFs = StubFileSystem()
        return GitSyncService(
            gitRepository = gitRepository,
            graphLoader = GraphLoader(
                fileSystem = stubFs,
                pageRepository = InMemoryPageRepository(),
                blockRepository = InMemoryBlockRepository(),
            ),
            graphWriter = GraphWriter(fileSystem = stubFs),
            editLock = EditLock(),
            configRepository = StubConfigRepository(Either.Right(sampleConfig)),
            networkMonitor = NetworkMonitor(),
            fileSystem = stubFs,
            gitSyncBusyCounter = counter,
        )
    }

    @Test
    fun `isBusy is true for the duration of fetchOnly`() = runBlocking {
        assumeTrue(
            "Skipped: requires network access to reach the gated fetch() call",
            NetworkMonitor().isOnline,
        )

        val counter = GitSyncBusyCounter()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val gitRepository = object : StubGitRepository() {
            override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> {
                entered.complete(Unit)
                gate.await()
                return FetchResult(hasRemoteChanges = false, remoteCommitCount = 0).right()
            }
        }
        val service = buildService(counter, gitRepository)

        assertFalse(counter.isBusy.value, "precondition: counter starts idle")

        val job = launch { service.fetchOnly("test-graph") }
        withTimeout(5_000) { entered.await() }
        assertTrue(counter.isBusy.value, "isBusy must be true while fetchOnly is in flight")

        gate.complete(Unit)
        withTimeout(5_000) { job.join() }

        assertFalse(counter.isBusy.value, "counter must return to idle once fetchOnly completes")
    }

    @Test
    fun `isBusy returns to false even when fetchOnly throws`() = runBlocking {
        assumeTrue(
            "Skipped: requires network access to reach the throwing fetch() call",
            NetworkMonitor().isOnline,
        )

        val counter = GitSyncBusyCounter()
        val gitRepository = object : StubGitRepository() {
            override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> {
                throw RuntimeException("simulated transport crash")
            }
        }
        val service = buildService(counter, gitRepository)

        try {
            service.fetchOnly("test-graph")
            fail("expected fetchOnly to propagate the thrown exception")
        } catch (e: RuntimeException) {
            // expected — the finally-equivalent bracket around fetchOnly's body must still have
            // run gitSyncBusyCounter.end() before this exception propagated out.
        }

        assertFalse(counter.isBusy.value, "counter must return to idle even when fetchOnly throws")
    }
}
