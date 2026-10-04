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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
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
     * Real, Robolectric-backed proof that a `GitCloneWorker` appended via `beginUniqueWork(...,
     * APPEND_OR_REPLACE, ...)` can never become eligible to run while the periodic `GitSyncWorker`
     * sharing its unique-work name is actually `RUNNING` — i.e. WorkManager's own uniqueness
     * guarantee, not a race that happens to resolve favorably under `SynchronousExecutor`.
     *
     * Gates the periodic worker's `doWork()` on a [CompletableDeferred] (mirrors
     * `GitSyncBusyCounterFetchOnlyTest`'s gating pattern, adapted from a suspend stub to a blocking
     * `Worker.doWork()`) and drives its `setPeriodDelayMet` trigger from a background [thread], since
     * `SynchronousExecutor` runs it to completion in-line on whichever thread calls it — blocking the
     * test thread there would deadlock before the assertion below ever ran.
     *
     * Deliberately does not assert anything about the appended request's state *after* the periodic
     * job finishes: decompiling `androidx.work:work-runtime:2.9.1`'s `WorkerWrapper.class` confirms a
     * periodic run's completion path (`resetPeriodicAndResolve()`, which only resets state to
     * `ENQUEUED`/increments the period count) never calls `DependencyDao.getDependentWorkIds()` the
     * way a one-time request's `setSucceededAndResolve()` does — so a request appended behind a
     * periodic prerequisite via this exact mechanism never unblocks, confirmed empirically (an
     * earlier revision of this test asserted `SUCCEEDED` after `setPeriodDelayMet` and observed
     * `BLOCKED` instead, forever). That is a real WorkManager limitation of the production
     * mutual-exclusion design (Story 5.1.2/`research/architecture.md` §4), reported separately rather
     * than baked into this test as a false assumption — this test's own name only promises "do not
     * run concurrently", which is exactly what it proves.
     */
    @Test
    fun `two work requests enqueued under the same workNameFor(graphId) — one periodic GitSyncWorker, one GitCloneWorker — do not run concurrently`() {
        val graphId = "unique-work-concurrency"
        val workName = WorkManagerSyncScheduler.workNameFor(graphId)
        val syncEntered = CompletableDeferred<Unit>()
        val syncGate = CompletableDeferred<Unit>()
        initTestWorkManager(
            gitSyncWorkerResult = {
                syncEntered.complete(Unit)
                runBlocking { syncGate.await() }
                ListenableWorker.Result.success()
            },
            gitCloneWorkerResult = { ListenableWorker.Result.success() },
        )
        val workManager = WorkManager.getInstance(context)
        val testDriver = WorkManagerTestInitHelper.getTestDriver(context)!!

        val periodicRequest = PeriodicWorkRequestBuilder<GitSyncWorker>(15, TimeUnit.MINUTES)
            .setInputData(workDataOf(GitSyncWorker.KEY_GRAPH_ID to graphId))
            .build()
        workManager.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.UPDATE, periodicRequest)

        // setPeriodDelayMet() runs the gated worker to the point it blocks on syncGate, all
        // synchronously on the calling thread — must be a background thread, not this test thread.
        val periodicRunner = thread(name = "periodic-runner") { testDriver.setPeriodDelayMet(periodicRequest.id) }
        runBlocking { withTimeout(5_000) { syncEntered.await() } }
        assertEquals(
            WorkInfo.State.RUNNING,
            workManager.getWorkInfoById(periodicRequest.id).get().state,
            "precondition: the periodic GitSyncWorker must actually be RUNNING, not merely scheduled",
        )

        // Append the one-time GitCloneWorker request under the same unique-work name, exactly as
        // AndroidGitCloneWorkerLauncher.launchClone() does, while the periodic job is still RUNNING.
        val cloneRequest = OneTimeWorkRequestBuilder<GitCloneWorker>().build()
        workManager.beginUniqueWork(workName, ExistingWorkPolicy.APPEND_OR_REPLACE, cloneRequest).enqueue().result.get()

        assertEquals(
            WorkInfo.State.BLOCKED,
            workManager.getWorkInfoById(cloneRequest.id).get().state,
            "GitCloneWorker must not be eligible to run while the periodic GitSyncWorker sharing its unique-work name is RUNNING",
        )

        syncGate.complete(Unit)
        periodicRunner.join(5_000)
    }
}
