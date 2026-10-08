// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Closes the git-sync-resilience Story 5.1.2 test-coverage gap (validation.md's REQ-5 rows):
 * [AndroidGitCloneWorkerLauncher] and [WorkManagerSyncScheduler] share one unique-work name
 * (`WorkManagerSyncScheduler.workNameFor`, now `internal`) so WorkManager's own uniqueness
 * guarantee — not a second, same-process-only mutex — serializes a foreground clone/fetch/push
 * against the periodic background fetch (`research/architecture.md` §4). These tests exercise
 * that guarantee through a real Robolectric-backed `WorkManager` test driver, matching
 * `WorkManagerSyncSchedulerRetryOwnerTest.kt`/`AndroidGitCloneWorkerLauncherTest.kt`'s established
 * conventions: `WorkManagerTestInitHelper` + `SynchronousExecutor`, and a `WorkerFactory` that
 * substitutes trivial stand-in `Worker`s for `GitSyncWorker`/`GitCloneWorker` so these tests probe
 * WorkManager's own scheduling behavior, not either worker's internal `doWork()` logic (already
 * covered by `WorkManagerSyncSchedulerRetryOwnerTest`/`GitCloneWorkerTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WorkManagerSyncSchedulerUniqueWorkTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Builds a test [WorkManager] whose `WorkerFactory` substitutes trivial stand-in `Worker`s for
     * `GitSyncWorker`/`GitCloneWorker`, isolating unique-work scheduling behavior from either
     * worker's real `doWork()` (JGit calls, credential resolution, notifications, ...). */
    private fun initTestWorkManager(
        gitSyncWorkerResult: () -> ListenableWorker.Result = { ListenableWorker.Result.success() },
        gitCloneWorkerResult: () -> ListenableWorker.Result = { ListenableWorker.Result.success() },
    ) {
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker? {
                    val result = when (workerClassName) {
                        GitSyncWorker::class.java.name -> gitSyncWorkerResult
                        GitCloneWorker::class.java.name -> gitCloneWorkerResult
                        else -> return null
                    }
                    return object : Worker(appContext, workerParameters) {
                        override fun doWork(): Result = result()
                    }
                }
            })
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun `AndroidGitCloneWorkerLauncher computes the same workNameFor(graphId) as WorkManagerSyncScheduler's periodic job`() {
        initTestWorkManager()
        val graphId = "unique-work-happy-path"
        WorkManagerSyncScheduler(context, graphId).schedule(30)

        // AndroidGitCloneWorkerLauncher.launchClone() (AndroidGitCloneWorkerLauncher.kt:61)
        // computes its enqueue name via this exact same call — if the two classes truly share one
        // name, querying WorkManager by it must resolve to the periodic job just scheduled above.
        val nameLauncherWouldCompute = WorkManagerSyncScheduler.workNameFor(graphId)
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(nameLauncherWouldCompute).get()

        assertEquals(1, infos.size, "workNameFor(graphId) must resolve to the periodic job WorkManagerSyncScheduler scheduled")
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)

        // Negative control: a different graphId's computed name must not collide with this one.
        val otherGraphInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WorkManagerSyncScheduler.workNameFor("unique-work-other-graph"))
            .get()
        assertTrue(otherGraphInfos.isEmpty(), "an unrelated graphId must compute a distinct, empty unique-work bucket")
    }

    @Test
    fun `GitCloneWorker enqueued via beginUniqueWork(APPEND_OR_REPLACE) for a graph with no existing periodic job still runs immediately`() {
        initTestWorkManager { ListenableWorker.Result.success() }
        val graphId = "unique-work-new-graph"
        val workName = WorkManagerSyncScheduler.workNameFor(graphId)
        val workManager = WorkManager.getInstance(context)

        // Precondition: this is the initial-clone case (architecture.md §4 point 3) — no periodic
        // job, or any other work, scheduled yet under this graph's unique-work name.
        assertTrue(workManager.getWorkInfosForUniqueWork(workName).get().isEmpty())

        val cloneRequest = OneTimeWorkRequestBuilder<GitCloneWorker>().build()
        workManager.beginUniqueWork(workName, ExistingWorkPolicy.APPEND_OR_REPLACE, cloneRequest).enqueue().result.get()

        val info = workManager.getWorkInfoById(cloneRequest.id).get()
        assertEquals(
            WorkInfo.State.SUCCEEDED,
            info.state,
            "a new graph's first clone must not be falsely blocked by a nonexistent periodic job",
        )
    }

    /**
     * Regression for the shared-unique-name hazard: a clone chained behind a periodic job via
     * `APPEND_OR_REPLACE` sits `BLOCKED` forever (a periodic run's completion never unblocks
     * dependents, per decompiled work-runtime 2.9.1). [AndroidGitCloneWorkerLauncher] therefore
     * pauses the periodic job, runs the clone fresh under the name, and restores the periodic job.
     *
     * Non-blocking by design: the periodic job's initial delay keeps it `ENQUEUED` (never run) so
     * the test driver's serial executor is never held by a gated worker.
     */
    @Test
    fun `launchClone over an existing periodic job reaches SUCCEEDED instead of being left BLOCKED, and restores the periodic job`() = runBlocking {
        val graphId = "unique-work-concurrency"
        val workName = WorkManagerSyncScheduler.workNameFor(graphId)
        initTestWorkManager()
        val workManager = WorkManager.getInstance(context)

        val periodicRequest = PeriodicWorkRequestBuilder<GitSyncWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(1, TimeUnit.HOURS)
            .setInputData(workDataOf(GitSyncWorker.KEY_GRAPH_ID to graphId))
            .build()
        workManager.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.UPDATE, periodicRequest).result.get()
        assertEquals(WorkInfo.State.ENQUEUED, workManager.getWorkInfoById(periodicRequest.id).get().state)

        val result = AndroidGitCloneWorkerLauncher(context, blockedTimeoutMs = 5_000L).launchClone(
            graphId = graphId,
            url = "https://example.invalid/graph.git",
            localPath = "/tmp/graph",
            auth = GitAuth.None,
            onProgress = {},
            onStateChange = {},
            graphDisplayName = null,
        )

        assertTrue(result.isRight(), "clone over a periodic job must complete, not hang BLOCKED: $result")
        val infos = workManager.getWorkInfosForUniqueWork(workName).get()
        assertTrue(
            infos.any { !it.state.isFinished },
            "the periodic sync job must be restored after the clone terminates, got ${infos.map { it.state }}",
        )
    }

    @Test
    fun `GitCloneWorker appended under the same workNameFor(graphId) as a periodic GitSyncWorker via APPEND_OR_REPLACE is still chained BLOCKED - the hazard the launcher avoids`() {
        val graphId = "unique-work-append-hazard"
        val workName = WorkManagerSyncScheduler.workNameFor(graphId)
        initTestWorkManager()
        val workManager = WorkManager.getInstance(context)

        val periodicRequest = PeriodicWorkRequestBuilder<GitSyncWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(1, TimeUnit.HOURS)
            .setInputData(workDataOf(GitSyncWorker.KEY_GRAPH_ID to graphId))
            .build()
        workManager.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.UPDATE, periodicRequest).result.get()

        val cloneRequest = OneTimeWorkRequestBuilder<GitCloneWorker>().build()
        workManager.beginUniqueWork(workName, ExistingWorkPolicy.APPEND_OR_REPLACE, cloneRequest).enqueue().result.get()

        assertEquals(WorkInfo.State.BLOCKED, workManager.getWorkInfoById(cloneRequest.id).get().state)
    }
}
