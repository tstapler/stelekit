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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Characterization of [GitSyncService.startPeriodicSync] (Task 2.0a; UT-19, UT-36), written against
 * the pre-extraction loop and kept unchanged after [PeriodicSyncDriver] took the loop over.
 *
 * Virtual time cannot drive the whole service on its own: `fetchOnly` always hops onto the real
 * `PlatformDispatcher.IO` and back, and the hop may complete inline without a dispatch back. Each tick
 * is therefore settled on state (fetch counted, `tickInFlight` false) while the scheduler is pumped,
 * so virtual time only moves on once the loop has parked in its delay again. No real sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GitSyncServicePeriodicTest {

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
    private val dispatcher = StandardTestDispatcher(scheduler)
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

    /** Advances one interval, then pumps the scheduler until that tick's fetch has run and its tick has finished. */
    private fun runOneInterval(intervalMinutes: Int) {
        val expected = repo.fetches.get() + 1
        scheduler.advanceTimeBy(intervalMinutes * 60_000L)
        val deadline = System.nanoTime() + TICK_TIMEOUT_NANOS
        while (!(repo.fetches.get() >= expected && service?.scheduledSyncStatus?.value?.tickInFlight == false)) {
            assertTrue(System.nanoTime() < deadline, "tick $expected never finished")
            scheduler.runCurrent()
            Thread.yield()
        }
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
    fun `startPeriodicSync should contain only delegation to the driver when source is audited`() {
        val source = dev.stapler.stelekit.wiring.WiringInputs.productionSources
            .getValue("commonMain/dev/stapler/stelekit/git/GitSyncService.kt")
        val start = source.indexOf("fun startPeriodicSync(")
        val end = source.indexOf("fun stopPeriodicSync")
        val body = source.substring(start, end)
        listOf("while (", "delay(", "launch", "++", "counter").forEach {
            assertTrue(it !in body, "startPeriodicSync must only delegate to PeriodicSyncDriver, found `$it`")
        }
        assertTrue("periodicDriver.start(" in body)
        val driverDecl = source.substring(source.indexOf("private val periodicDriver"), source.indexOf("val scheduledSyncStatus"))
        assertTrue("refreshLocalStatus(periodicGraphId)" in driverDecl && "fetchOnly(periodicGraphId).toTickOutcome()" in driverDecl)
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

    private companion object {
        const val TICK_TIMEOUT_NANOS = 30_000_000_000L
    }
}
