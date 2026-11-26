// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.ml

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import dev.stapler.stelekit.jobs.schedule.BackgroundTaskScheduler
import dev.stapler.stelekit.jobs.schedule.OneShotConflict
import dev.stapler.stelekit.jobs.schedule.SchedulerCapabilities
import dev.stapler.stelekit.jobs.schedule.TaskConfig
import dev.stapler.stelekit.jobs.schedule.TaskConstraints
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskScheduleInfo
import dev.stapler.stelekit.jobs.schedule.TaskScope
import dev.stapler.stelekit.jobs.schedule.WorkManagerTaskScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The downloader's cancels go through [BackgroundTaskScheduler], and never overtake a later enqueue. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DepthModelDownloaderCancelPortTest {
    private val context: Context = ApplicationProvider.getApplicationContext<Application>()

    private class RecordingScheduler(
        override val capabilities: SchedulerCapabilities = WorkManagerTaskScheduler.CAPABILITIES,
    ) : BackgroundTaskScheduler {
        val calls = Channel<String>(Channel.UNLIMITED)
        var onRunOnce: () -> Unit = {}
        var cancelGate: CompletableDeferred<Unit>? = null

        override suspend fun runOnce(key: TaskKey, constraints: TaskConstraints, onConflict: OneShotConflict) {
            calls.send("runOnce ${key.kind.id}")
            onRunOnce()
        }

        override suspend fun cancel(key: TaskKey) {
            cancelGate?.await()
            calls.send("cancel ${key.kind.id}")
        }

        override suspend fun apply(key: TaskKey, config: TaskConfig) = Unit
        override suspend fun cancelAll(scope: TaskScope) = Unit
        override suspend fun pause(scope: TaskScope) = Unit
        override suspend fun resume(scope: TaskScope) = Unit
        override suspend fun describe(key: TaskKey): TaskScheduleInfo? = null

        suspend fun next(): String = withTimeout(5_000) { calls.receive() }
    }

    private val cancelled = "cancel ${ModelDownloadTask.KEY.kind.id}"
    private val enqueued = "runOnce ${ModelDownloadTask.KEY.kind.id}"

    private fun downloader(scheduler: BackgroundTaskScheduler) =
        DepthModelDownloader(context, scheduler = scheduler, workInfoFlow = emptyFlow<List<WorkInfo>>())

    @After
    fun tearDown() {
        DepthModelStorage.modelFile(context).parentFile?.deleteRecursively()
    }

    @Test
    fun cancelDownload_cancelsThroughThePort() = runBlocking<Unit> {
        val scheduler = RecordingScheduler()

        downloader(scheduler).cancelDownload()

        assertEquals(cancelled, scheduler.next())
    }

    @Test
    fun cancelDuringRunOnce_cancelsAgain() = runBlocking<Unit> {
        val scheduler = RecordingScheduler()
        val downloader = downloader(scheduler)
        scheduler.onRunOnce = { downloader.cancelDownload() }

        val result = withTimeout(5_000) { downloader.downloadModel() }

        assertTrue(result.isLeft())
        // The user's cancel and the guard-cancel for the job runOnce had just enqueued.
        assertEquals(listOf(enqueued, cancelled, cancelled), List(3) { scheduler.next() })
    }

    @Test
    fun runOnce_waitsForAPendingCancel() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val scheduler = RecordingScheduler().apply { cancelGate = gate }
        val downloader = downloader(scheduler)
        downloader.cancelDownload()

        // UNDISPATCHED: runs up to the first suspension, which without the wait would be past runOnce.
        val retry = async(start = CoroutineStart.UNDISPATCHED) { downloader.downloadNowAnyway() }
        assertTrue(scheduler.calls.tryReceive().isFailure, "enqueued while the cancel was still pending")
        gate.complete(Unit)
        withTimeout(5_000) { retry.await() }

        assertEquals(listOf(cancelled, enqueued), List(2) { scheduler.next() })
    }

    @Test
    fun schedulerThatCannotSchedule_returnsLeft() = runBlocking<Unit> {
        val scheduler = RecordingScheduler(WorkManagerTaskScheduler.CAPABILITIES.copy(schedulesWork = false))

        val result = withTimeout(5_000) { downloader(scheduler).downloadModel() }

        assertTrue(result.isLeft())
        assertTrue(scheduler.calls.tryReceive().isFailure)
    }
}
