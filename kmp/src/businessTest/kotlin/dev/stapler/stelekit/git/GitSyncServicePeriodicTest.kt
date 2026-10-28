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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Characterization of [GitSyncService.startPeriodicSync] (Task 2.0a; UT-19, UT-36), written against
 * the pre-extraction loop and kept unchanged after [PeriodicSyncDriver] took the loop over.
 *
 * Virtual time cannot drive the whole service on its own: `fetchOnly` always hops onto the real
 * `PlatformDispatcher.IO` and back. [HopAwareDispatcher] makes that hop deterministic - it signals
 * when a continuation is dispatched from a non-test thread, so each tick is fully settled (parked
 * in its delay again) before virtual time moves on. No real sleeps, no polling.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class GitSyncServicePeriodicTest {

    private class HopAwareDispatcher(
        private val inner: TestDispatcher,
        private val testThread: Thread,
    ) : CoroutineDispatcher(), Delay by inner {
        val resumedFromOtherThread = Semaphore(0)
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            inner.dispatch(context, block)
            if (Thread.currentThread() !== testThread) resumedFromOtherThread.release()
        }
    }

    private class CountingGitRepository : StubGitRepository() {
        val fetches = AtomicInteger(0)
        override suspend fun status(config: GitConfig) =
            GitStatus(hasLocalChanges = false, untrackedFiles = emptyList(), modifiedFiles = emptyList()).right()
        override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> {
            fetches.incrementAndGet()
            return FetchResult(hasRemoteChanges = false, remoteCommitCount = 0).right()
        }
    }

    private val scheduler = TestCoroutineScheduler()
    private val repo = CountingGitRepository()
    private val dispatcher = HopAwareDispatcher(StandardTestDispatcher(scheduler), Thread.currentThread())
    private var service: GitSyncService? = null

    private fun buildService(): GitSyncService {
        val fs = StubFileSystem()
        return GitSyncService(
            gitRepository = repo,
            graphLoader = GraphLoader(fs, InMemoryPageRepository(), InMemoryBlockRepository()),
            graphWriter = GraphWriter(fileSystem = fs),
            editLock = EditLock(),
            configRepository = StubConfigRepository(Either.Right(sampleConfig)),
            networkMonitor = NetworkMonitor(),
            fileSystem = fs,
            isOnline = { true },
            periodicSyncContext = dispatcher,
        ).also { service = it }
    }

    @AfterTest
    fun tearDown() {
        service?.shutdown()
    }

    /** Advances one interval and waits until the tick has returned from its IO hop and re-parked. */
    private fun runOneInterval(intervalMinutes: Int) {
        scheduler.advanceTimeBy(intervalMinutes * 60_000L)
        scheduler.runCurrent()
        assertTrue(dispatcher.resumedFromOtherThread.tryAcquire(30, TimeUnit.SECONDS), "tick never returned from its IO hop")
        scheduler.runCurrent()
    }

    @Test
    fun `startPeriodicSync should fetch three times in 45 minutes when interval is 15`() {
        val svc = buildService()
        svc.startPeriodicSync("g", 15)
        scheduler.runCurrent()
        repeat(3) { runOneInterval(15) }
        assertEquals(3, repo.fetches.get())
    }

    @Test
    fun `startPeriodicSync should launch nothing when interval is non-positive`() {
        val svc = buildService()
        svc.startPeriodicSync("g", 0)
        svc.startPeriodicSync("g", -5)
        scheduler.runCurrent()
        scheduler.advanceTimeBy(24 * 60 * 60_000L)
        scheduler.runCurrent()
        assertEquals(0, repo.fetches.get())
    }

    @Test
    fun `second startPeriodicSync should cancel the first loop`() {
        val svc = buildService()
        svc.startPeriodicSync("g", 15)
        scheduler.runCurrent()
        svc.startPeriodicSync("g", 60)
        scheduler.runCurrent()
        // The 15-minute loop would have fired three times by now; only the 60-minute loop remains.
        scheduler.advanceTimeBy(45 * 60_000L)
        scheduler.runCurrent()
        assertEquals(0, repo.fetches.get())
        runOneInterval(15)
        assertEquals(1, repo.fetches.get())
    }
}
