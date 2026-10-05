// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import arrow.core.Either
import arrow.core.left
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Proves the ADR-002 single-retry-owner invariant at [GitSyncWorker.doWork]'s two catch sites
 * (Story 1.2.3): once [runGitTransportOpWithRetry] (Story 1.2.2) has already retried internally,
 * `doWork()` must return `Result.failure()`, never `Result.retry()` — a second, uncoordinated
 * WorkManager-level retry would stack a second backoff layer on top of the first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WorkManagerSyncSchedulerRetryOwnerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @After
    fun tearDown() {
        GitSyncServiceRegistry.unregister(FAST_PATH_GRAPH_ID)
        GitSyncServiceRegistry.unregister(WORK_MANAGER_GRAPH_ID)
    }

    /** Fakes a validated, internet-capable active network so [NetworkMonitor.isOnline] reports
     * `true` — mirrors [GitSyncBusyCounterSharedWiringTest]'s identical helper. */
    private fun fakeOnlineNetworkMonitor() {
        NetworkMonitor.init(context)
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowConnectivityManager = shadowOf(connectivityManager)
        val networkInfo = ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI,
            0,
            true,
            true,
        )
        shadowConnectivityManager.setActiveNetworkInfo(networkInfo)
        val activeNetwork = connectivityManager.activeNetwork ?: return
        val capabilities = NetworkCapabilities()
        shadowOf(capabilities).apply {
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        shadowConnectivityManager.setNetworkCapabilities(activeNetwork, capabilities)
    }

    /** Registers a [GitSyncService] whose [GitRepository.fetch] always returns [result] — the
     * fast path's route through [GitSyncServiceRegistry]. */
    private fun registerFastPathService(graphId: String, result: Either<DomainError.GitError, FetchResult>) {
        fakeOnlineNetworkMonitor()
        val stubFs = StubFileSystem()
        val service = GitSyncService(
            gitRepository = object : StubGitRepository() {
                override suspend fun fetch(config: GitConfig) = result
            },
            graphLoader = GraphLoader(
                fileSystem = stubFs,
                pageRepository = InMemoryPageRepository(),
                blockRepository = InMemoryBlockRepository(),
            ),
            graphWriter = GraphWriter(fileSystem = stubFs),
            editLock = EditLock(),
            configRepository = StubConfigRepository(sampleConfig.right()),
            networkMonitor = NetworkMonitor(),
            fileSystem = stubFs,
        )
        GitSyncServiceRegistry.register(graphId, service)
    }

    private fun buildWorker(graphId: String) =
        TestListenableWorkerBuilder<GitSyncWorker>(context)
            .setInputData(workDataOf(GitSyncWorker.KEY_GRAPH_ID to graphId))
            .build()

    @Test
    fun `GitSyncWorker doWork() fast path returns Result failure(), not Result retry(), when fetchOnly() returns Left(RetryExhausted)`() {
        registerFastPathService(
            FAST_PATH_GRAPH_ID,
            DomainError.GitError.RetryExhausted(5, DomainError.GitError.FetchFailed("boom")).left(),
        )

        // Block body (not `= runBlocking { ... }`): JUnit4's method validator requires a `void`
        // (Unit) return type, but `runBlocking`'s generic `T` would otherwise be inferred from
        // `assertIs`'s own return value (it returns the narrowed type, not Unit).
        val result = runBlocking { buildWorker(FAST_PATH_GRAPH_ID).doWork() }

        assertIs<ListenableWorker.Result.Failure>(result)
    }

    // Story 1.2.3 slow path: exercised via runSlowPathFetch, the DB-free seam GitSyncWorker.doWork()
    // delegates to (a real driver needs the `fts5` SQLite module Robolectric's build lacks).
    private fun repoReturning(result: Either<DomainError.GitError, FetchResult>) =
        object : StubGitRepository() {
            override suspend fun fetch(config: GitConfig) = result
        }

    @Test
    fun `slow path returns Result failure(), not retry(), when fetch() returns Left(RetryExhausted)`() {
        val result = runBlocking {
            runSlowPathFetch(
                loadConfig = { sampleConfig },
                gitRepository = repoReturning(
                    DomainError.GitError.RetryExhausted(5, DomainError.GitError.FetchFailed("boom")).left(),
                ),
            )
        }
        assertIs<ListenableWorker.Result.Failure>(result)
    }

    @Test
    fun `slow path returns success when fetch() succeeds, and success when no config row exists`() {
        val ok = runBlocking { runSlowPathFetch({ sampleConfig }, repoReturning(FetchResult(false, 0).right())) }
        assertIs<ListenableWorker.Result.Success>(ok)
        val noRow = runBlocking { runSlowPathFetch({ null }, repoReturning(FetchResult(false, 0).right())) }
        assertIs<ListenableWorker.Result.Success>(noRow)
    }

    /**
     * Drives [GitSyncWorker] through the real [WorkManager] enqueue/execution path (not a direct
     * `doWork()` call) and asserts the resulting [WorkInfo.State] is `FAILED`, never re-queued —
     * proving WorkManager itself never sees a `Result.retry()` to act on. Uses the fast path (an
     * immediate `Left(RetryExhausted)`, not a real network wait) since this test's job is proving
     * WorkManager's own reaction, not re-proving retry-budget exhaustion (already covered by
     * `GitTransportRetryTest`'s exhaustion test).
     */
    @Test
    fun `a GitSyncWorker built via TestListenableWorkerBuilder against a retry-exhausting StubGitRepository returns Result failure() and is not re-enqueued by WorkManager's own retry mechanism`() {
        registerFastPathService(
            WORK_MANAGER_GRAPH_ID,
            DomainError.GitError.RetryExhausted(5, DomainError.GitError.FetchFailed("boom")).left(),
        )

        val request = OneTimeWorkRequestBuilder<GitSyncWorker>()
            .setInputData(workDataOf(GitSyncWorker.KEY_GRAPH_ID to WORK_MANAGER_GRAPH_ID))
            .build()
        val workManager = WorkManager.getInstance(context)
        workManager.enqueue(request).result.get()

        // GitSyncWorker is a CoroutineWorker: it runs on a coroutine dispatcher, not the test
        // SynchronousExecutor, so the state right after enqueue can still be RUNNING. Wait for a
        // terminal state; a Result.retry() would surface as ENQUEUED and never terminate.
        val deadline = System.nanoTime() + 10_000_000_000L
        var state = workManager.getWorkInfoById(request.id).get().state
        while (!state.isFinished && state != WorkInfo.State.ENQUEUED && System.nanoTime() < deadline) {
            Thread.sleep(25)
            state = workManager.getWorkInfoById(request.id).get().state
        }
        assertEquals(WorkInfo.State.FAILED, state, "must not be re-enqueued (ENQUEUED/RUNNING) after Result.failure()")
    }

    companion object {
        private const val FAST_PATH_GRAPH_ID = "retry-owner-fast-path"
        private const val WORK_MANAGER_GRAPH_ID = "retry-owner-workmanager"
    }
}
