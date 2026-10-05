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

    // NOTE (Story 1.2.3, validation.md's slow-path row): a real end-to-end test of the slow path
    // — seed a real on-disk git_config row, point AndroidGitRepository.fetch() at an unreachable
    // remote, let RetryPolicies.gitTransportTransient's real 5-retry budget exhaust — is not
    // automatable in this Robolectric environment. Two stacked, pre-existing infra blockers were
    // found while attempting it: (1) DriverFactory's production default (RequeryDriverProvider)
    // links a native `sqlite3x` binary absent from java.library.path under Robolectric's
    // plain-JVM process (UnsatisfiedLinkError) — worked around by swapping
    // DriverFactory.driverProvider to a Robolectric-compatible FrameworkSQLiteOpenHelperFactory,
    // as the two tests above rely on for DB-adjacent setup — but (2) Robolectric's own bundled
    // native SQLite build lacks the `fts5` module this app's schema requires at CREATE TABLE
    // time (`SQLiteException: no such module: fts5`), and *any* driver creation against a fresh
    // DB hits this — which `doWork()`'s slow path always does internally, regardless of test
    // setup. The slow path's production fix (checking fetch()'s Either result — the same
    // discard-bug the fast path had — and Result.failure() on exhaustion, both above) is
    // implemented and compiles; only this dedicated regression test is blocked. Skipped
    // alongside validation.md's StubGitRepository-dependent Story 1.2.2 integration row (Story
    // 6.1.1, not yet built) — both should be revisited once GitSyncWorker's slow path gets an
    // injectable GitRepository/DB seam, or Robolectric's SQLite shadow gains fts5 support.

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
