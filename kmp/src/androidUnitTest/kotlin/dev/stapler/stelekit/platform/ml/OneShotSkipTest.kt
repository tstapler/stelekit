// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.ml

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker.Result
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import dev.stapler.stelekit.jobs.schedule.NetworkPolicy
import dev.stapler.stelekit.jobs.schedule.NetworkSnapshot
import dev.stapler.stelekit.jobs.schedule.TaskConstraints
import dev.stapler.stelekit.jobs.schedule.TaskEntry
import dev.stapler.stelekit.jobs.schedule.TaskOverrides
import dev.stapler.stelekit.jobs.schedule.TaskWorkKeys
import dev.stapler.stelekit.jobs.schedule.TaskWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A skipped model download never leaves `downloadModel()` hanging in `Downloading`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class OneShotSkipTest {
    private val context: Context = ApplicationProvider.getApplicationContext<Application>()
    private val server = ModelDownloadFakeServer()
    private val scope = CoroutineScope(Dispatchers.Default)
    private var fixture: ModelDownloadFixture? = null

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @After
    fun tearDown() {
        server.stop()
        scope.cancel()
        DepthModelStorage.modelFile(context).parentFile?.deleteRecursively()
        fixture?.uninstall()
    }

    private fun workerRun(attempt: Int): Result = runBlocking {
        TestListenableWorkerBuilder<TaskWorker>(context)
            .setInputData(
                workDataOf(
                    TaskWorkKeys.KEY_TASK_KIND to ModelDownloadTask.KIND.id,
                    TaskWorkKeys.KEY_TASK_SCOPE to "",
                    TaskWorkKeys.KEY_NETWORK_POLICY to "unmetered",
                ),
            )
            .setRunAttemptCount(attempt)
            .build()
            .doWork()
    }

    @Test
    fun worker_should_retryThenFailTerminalWithError_When_meteredSkipsShareTheFiveAttemptBudget() {
        fixture = ModelDownloadFixture(context, server.url, network = NetworkSnapshot(online = true, isMetered = true)).also { it.install() }

        assertEquals(Result.retry(), workerRun(0))
        assertEquals(Result.retry(), workerRun(3))
        val terminal = workerRun(4)
        assertTrue(terminal is Result.Failure)
        assertEquals("skipped: metered", terminal.outputData.getString(TaskWorkKeys.KEY_ERROR))
        assertFalse(DepthModelStorage.modelFile(context).exists(), "the body must not run on a skip")
    }

    private fun assertDownloadResolvesFailed(network: NetworkSnapshot, expectedReason: String) = runBlocking<Unit> {
        // Cap 1 means the first skip is already terminal, so the real WorkManager chain ends without backoff timing.
        val f = ModelDownloadFixture(context, server.url, network = network, specTransform = { it.copy(oneShotRetryCap = 1) })
            .also { it.install() }
        fixture = f
        f.store.write(
            ModelDownloadTask.KEY,
            TaskEntry(enabled = false, TaskOverrides(schedule = null, constraints = TaskConstraints(NetworkPolicy.Unmetered))),
        )
        val result = scope.async { f.downloader.downloadModel() }
        val work = WorkManager.getInstance(context)
        val id: UUID = awaitActiveWorkId(work, DepthModelDownloader.UNIQUE_WORK_NAME)
        f.downloader.awaitDownloadingState()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(id)

        val outcome = withTimeout(30_000) { result.await() }
        assertTrue(outcome.isLeft())
        assertEquals(DepthModelDownloader.ModelState.Failed(expectedReason), f.downloader.modelState.value)
    }

    @Test
    fun downloadModel_should_resolveFailed_When_networkIsMetered() =
        assertDownloadResolvesFailed(NetworkSnapshot(online = true, isMetered = true), "skipped: metered")

    @Test
    fun downloadModel_should_resolveFailed_When_meteredStateIsUnknown() =
        assertDownloadResolvesFailed(NetworkSnapshot(online = true, isMetered = null), "skipped: unknown-network")
}
