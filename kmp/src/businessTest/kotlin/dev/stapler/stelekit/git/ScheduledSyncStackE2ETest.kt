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
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Story 2.4 / U16: `startPeriodicSync` ticks through the policy-gated runner, so a confirmed graph with
 * an idle editor pulls the remote while the app is open, and every other state stays fetch-only.
 * Virtual time drives the loop; each tick is settled on state, with no real sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledSyncStackE2ETest {

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store[key] ?: defaultValue
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    /** Remote two commits ahead on the first fetch, level afterwards. */
    private class RemoteAheadRepository : StubGitRepository() {
        val fetches = AtomicInteger(0)
        val merges = AtomicInteger(0)
        val pushes = AtomicInteger(0)
        val commits = AtomicInteger(0)
        @Volatile var dirty = false

        override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> =
            GitStatus(dirty, emptyList(), if (dirty) listOf("journals/local.md") else emptyList()).right()

        override suspend fun stageSubdir(config: GitConfig, excludePaths: Set<String>): Either<DomainError.GitError, Unit> = Unit.right()

        override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> {
            commits.incrementAndGet()
            dirty = false
            return "sha".right()
        }

        override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> {
            val behind = fetches.incrementAndGet() == 1
            return FetchResult(hasRemoteChanges = behind, remoteCommitCount = if (behind) 2 else 0).right()
        }

        override suspend fun merge(config: GitConfig): Either<DomainError.GitError, MergeResult> {
            merges.incrementAndGet()
            return MergeResult(
                hasConflicts = false, conflicts = emptyList(),
                changedFiles = listOf("/repo/journals/2026_10_08.md", "/repo/journals/2026_10_09.md"),
                mergedCommitCount = 2,
            ).right()
        }

        override suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit> {
            pushes.incrementAndGet()
            return Unit.right()
        }
    }

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val repo = RemoteAheadRepository()
    private val settings = MapSettings()
    private val editLock = EditLock()
    private val reloaded: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var service: GitSyncService? = null

    private fun service(config: GitConfig = sampleConfig): GitSyncService {
        val fs = object : StubFileSystem() {
            override fun readFile(path: String): String? {
                reloaded += path
                return null
            }
        }
        return GitSyncService(
            gitRepository = repo,
            graphLoader = GraphLoader(fs, InMemoryPageRepository(), InMemoryBlockRepository()),
            graphWriter = GraphWriter(fileSystem = fs),
            editLock = editLock,
            configRepository = StubConfigRepository(Either.Right(config)),
            networkMonitor = NetworkMonitor(),
            fileSystem = fs,
            graphId = "test-graph",
            settings = settings,
            isOnline = { true },
            periodicSyncContext = dispatcher,
        ).also { service = it }
    }

    @AfterTest
    fun tearDown() {
        service?.shutdown()
    }

    private fun confirmFirstSync() = FirstSyncConfirmation(settings).confirm("test-graph", "origin", "main")

    /** Advances one 15-minute interval, then pumps until the tick's fetch has run and the tick has finished. */
    private fun runOneInterval(svc: GitSyncService) {
        val expected = repo.fetches.get() + 1
        scheduler.advanceTimeBy(INTERVAL_MINUTES * MINUTE_MS)
        val deadline = System.nanoTime() + TICK_TIMEOUT_NANOS
        while (!(repo.fetches.get() >= expected && svc.scheduledSyncStatus.value.tickInFlight == false)) {
            assertTrue(System.nanoTime() < deadline, "tick $expected never finished")
            scheduler.runCurrent()
            Thread.yield()
        }
        scheduler.runCurrent()
    }

    @Test
    fun `a tick with the remote two commits ahead, an idle editor and a confirmed first sync merges both`() {
        confirmFirstSync()
        val svc = service()
        svc.startPeriodicSync("test-graph", INTERVAL_MINUTES)
        scheduler.runCurrent()

        runOneInterval(svc)

        val state = assertIs<SyncState.Success>(svc.syncState.value)
        assertEquals(2, state.remoteCommitsMerged)
        assertEquals(listOf("/repo/journals/2026_10_08.md", "/repo/journals/2026_10_09.md"), reloaded.distinct())
        assertEquals(1, repo.merges.get())
        assertEquals(1, repo.pushes.get())
        assertTrue(svc.scheduledSyncStatus.value.lastSuccessAtMs != null, "the tick counted as a success")
    }

    @Test
    fun `with autoCommit on a dirty tree is committed before the merge`() {
        confirmFirstSync()
        repo.dirty = true
        val svc = service()
        svc.startPeriodicSync("test-graph", INTERVAL_MINUTES)
        scheduler.runCurrent()

        runOneInterval(svc)

        assertEquals(1, repo.commits.get())
        assertEquals(1, repo.pushes.get())
    }

    @Test
    fun `an unconfirmed first sync keeps ticks fetch-only until a manual sync confirms it`() {
        val svc = service()
        svc.startPeriodicSync("test-graph", INTERVAL_MINUTES)
        scheduler.runCurrent()

        runOneInterval(svc)

        assertEquals(1, repo.fetches.get())
        assertEquals(0, repo.merges.get())
        assertEquals(0, repo.pushes.get())
        assertEquals(emptyList(), reloaded)
        assertEquals(SyncState.MergeAvailable(2), svc.syncState.value)
        assertEquals(0, svc.scheduledSyncStatus.value.consecutiveFailures)
    }

    @Test
    fun `an editor that is mid-edit makes the tick skip with no repository call`() {
        confirmFirstSync()
        editLock.beginEdit()
        val svc = service()
        svc.startPeriodicSync("test-graph", INTERVAL_MINUTES)
        scheduler.runCurrent()

        scheduler.advanceTimeBy(INTERVAL_MINUTES * MINUTE_MS)
        val deadline = System.nanoTime() + TICK_TIMEOUT_NANOS
        while (svc.scheduledSyncStatus.value.lastAttemptAtMs == null || svc.scheduledSyncStatus.value.tickInFlight) {
            assertTrue(System.nanoTime() < deadline, "tick never ran")
            scheduler.runCurrent()
            Thread.yield()
        }

        assertEquals(0, repo.fetches.get())
        assertEquals(SkipReason.Policy(ScheduledSkipReason.EditingInProgress), svc.scheduledSyncStatus.value.lastSkip)
    }

    private companion object {
        const val INTERVAL_MINUTES = 15
        const val MINUTE_MS = 60_000L
        const val TICK_TIMEOUT_NANOS = 30_000_000_000L
    }
}
