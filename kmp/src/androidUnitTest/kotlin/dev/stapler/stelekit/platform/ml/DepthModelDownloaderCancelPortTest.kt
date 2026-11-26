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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

        /** Consumed one per call, before [cancelGate] / after the call is recorded. */
        val cancelGates = java.util.concurrent.ConcurrentLinkedQueue<CompletableDeferred<Unit>>()
        val runOnceGates = java.util.concurrent.ConcurrentLinkedQueue<CompletableDeferred<Unit>>()

        override suspend fun runOnce(key: TaskKey, constraints: TaskConstraints, onConflict: OneShotConflict) {
            calls.send("runOnce ${key.kind.id}")
            onRunOnce()
            runOnceGates.poll()?.await()
        }

        override suspend fun cancel(key: TaskKey) {
            cancelGates.poll()?.await()
            cancelGate?.await()
            calls.send("cancel ${key.kind.id}")
        }

        override suspend fun apply(key: TaskKey, config: TaskConfig) = Unit
        override suspend fun cancelAll(scope: TaskScope) = Unit
        override suspend fun pause(scope: TaskScope) = Unit
        override suspend fun resume(scope: TaskScope) = Unit
        override suspend fun describe(key: TaskKey): TaskScheduleInfo? = null

        suspend fun next(): String = withTimeout(5_000) { calls.receive() }

        /** A bounded wait for a call that must not happen; a broken ordering delivers it almost at once. */
        suspend fun assertQuiet(why: String) = assertNull(withTimeoutOrNull(300) { calls.receive() }, why)
    }

    private val cancelled = "cancel ${ModelDownloadTask.KEY.kind.id}"
    private val enqueued = "runOnce ${ModelDownloadTask.KEY.kind.id}"

    private fun downloader(scheduler: BackgroundTaskScheduler, status: Flow<List<WorkInfo>>? = emptyFlow()) =
        DepthModelDownloader(context, scheduler = scheduler, workInfoFlow = { status })

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
        assertEquals(DepthModelDownloader.ModelState.Cancelled, downloader.modelState.value)
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
    fun downloadModel_waitsForAPendingCancel() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val scheduler = RecordingScheduler().apply { cancelGate = gate }
        val downloader = downloader(scheduler)
        downloader.cancelDownload()

        val retry = async(start = CoroutineStart.UNDISPATCHED) { downloader.downloadModel() }
        assertTrue(scheduler.calls.tryReceive().isFailure, "enqueued while the cancel was still pending")
        gate.complete(Unit)

        assertEquals(listOf(cancelled, enqueued), List(2) { scheduler.next() })
        retry.cancel()
    }

    @Test
    fun cancelWhileWaiting_neverEnqueues() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val scheduler = RecordingScheduler().apply { cancelGate = gate }
        val downloader = downloader(scheduler)
        downloader.cancelDownload()
        val plain = async(start = CoroutineStart.UNDISPATCHED) { downloader.downloadModel() }
        val anyway = async(start = CoroutineStart.UNDISPATCHED) { downloader.downloadNowAnyway() }

        downloader.cancelDownload()
        gate.complete(Unit)
        assertTrue(withTimeout(5_000) { plain.await() }.isLeft())
        withTimeout(5_000) { anyway.await() }

        assertEquals(listOf(cancelled, cancelled), List(2) { scheduler.next() })
        assertTrue(scheduler.calls.tryReceive().isFailure, "a cancelled call enqueued")
    }

    @Test
    fun cancelDuringDownloadNowAnyway_cancelsAgain() = runBlocking<Unit> {
        val scheduler = RecordingScheduler()
        val downloader = downloader(scheduler)
        scheduler.onRunOnce = { downloader.cancelDownload() }

        withTimeout(5_000) { downloader.downloadNowAnyway() }

        assertEquals(DepthModelDownloader.ModelState.Cancelled, downloader.modelState.value)
        assertEquals(listOf(enqueued, cancelled, cancelled), List(3) { scheduler.next() })
    }

    @Test
    fun aLaterCancel_doesNotOvertakeAnEarlierOne() = runBlocking<Unit> {
        val first = CompletableDeferred<Unit>()
        val scheduler = RecordingScheduler().apply { cancelGates += first }
        val downloader = downloader(scheduler)
        downloader.cancelDownload()
        downloader.cancelDownload()
        val retry = async(start = CoroutineStart.UNDISPATCHED) { downloader.downloadNowAnyway() }

        scheduler.assertQuiet("something ran past the held first cancel")
        first.complete(Unit)
        withTimeout(5_000) { retry.await() }

        assertEquals(listOf(cancelled, cancelled, enqueued), List(3) { scheduler.next() })
    }

    @Test
    fun retry_alsoWaitsForAGuardCancelRequestedWhileItWaits() = runBlocking<Unit> {
        val enqueueing = CompletableDeferred<Unit>()
        val userCancel = CompletableDeferred<Unit>()
        val guardCancel = CompletableDeferred<Unit>()
        val scheduler = RecordingScheduler().apply {
            runOnceGates += enqueueing
            cancelGates += listOf(userCancel, guardCancel)
        }
        val downloader = downloader(scheduler)
        val original = async(start = CoroutineStart.UNDISPATCHED) { downloader.downloadNowAnyway() }
        assertEquals(enqueued, scheduler.next())
        downloader.cancelDownload()
        val retry = async(start = CoroutineStart.UNDISPATCHED) { downloader.downloadNowAnyway() }

        enqueueing.complete(Unit)
        withTimeout(5_000) { original.await() } // has now requested its guard-cancel
        userCancel.complete(Unit)
        assertEquals(cancelled, scheduler.next())
        scheduler.assertQuiet("the retry enqueued ahead of the guard-cancel, which would kill its job")
        guardCancel.complete(Unit)
        withTimeout(5_000) { retry.await() }

        assertEquals(listOf(cancelled, enqueued), List(2) { scheduler.next() })
    }

    @Test
    fun retry_staysQueued_whenTheGuardCancelIsReleasedBeforeTheUserCancel() = runBlocking<Unit> {
        val enqueueing = CompletableDeferred<Unit>()
        val userCancel = CompletableDeferred<Unit>()
        val guardCancel = CompletableDeferred<Unit>()
        val scheduler = RecordingScheduler().apply {
            runOnceGates += enqueueing
            cancelGates += listOf(userCancel, guardCancel)
        }
        val downloader = downloader(scheduler)
        val original = async(start = CoroutineStart.UNDISPATCHED) { downloader.downloadNowAnyway() }
        assertEquals(enqueued, scheduler.next())
        downloader.cancelDownload()
        val retry = async(start = CoroutineStart.UNDISPATCHED) { downloader.downloadNowAnyway() }

        enqueueing.complete(Unit)
        withTimeout(5_000) { original.await() }
        guardCancel.complete(Unit)
        scheduler.assertQuiet("something ran past the held user cancel")
        userCancel.complete(Unit)
        withTimeout(5_000) { retry.await() }

        assertEquals(listOf(cancelled, cancelled, enqueued), List(3) { scheduler.next() })
    }

    @Test
    fun downloadNowAnyway_doesNothing_whenItCannotSchedule() = runBlocking<Unit> {
        val scheduler = RecordingScheduler()
        val downloader = downloader(scheduler, status = null)

        downloader.downloadNowAnyway()

        assertEquals(DepthModelDownloader.ModelState.Absent, downloader.modelState.value)
        assertTrue(scheduler.calls.tryReceive().isFailure)
    }

    @Test
    fun unavailableWorkManager_returnsLeft() = runBlocking<Unit> {
        val scheduler = RecordingScheduler()

        val result = withTimeout(5_000) { downloader(scheduler, status = null).downloadModel() }

        assertTrue(result.isLeft())
        assertTrue(scheduler.calls.tryReceive().isFailure)
    }

    @Test
    fun schedulerThatCannotSchedule_returnsLeft() = runBlocking<Unit> {
        val scheduler = RecordingScheduler(WorkManagerTaskScheduler.CAPABILITIES.copy(schedulesWork = false))

        val result = withTimeout(5_000) { downloader(scheduler).downloadModel() }

        assertTrue(result.isLeft())
        assertTrue(scheduler.calls.tryReceive().isFailure)
    }
}
