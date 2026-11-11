// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.ml

import android.app.Application
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.stapler.stelekit.jobs.JobKind
import dev.stapler.stelekit.jobs.JobTags
import dev.stapler.stelekit.jobs.schedule.ConstraintState
import dev.stapler.stelekit.jobs.schedule.NetworkPolicy
import dev.stapler.stelekit.jobs.schedule.OneShotConflict
import dev.stapler.stelekit.jobs.schedule.TaskConstraints
import dev.stapler.stelekit.jobs.schedule.TaskEntry
import dev.stapler.stelekit.jobs.schedule.TaskForegroundProvider
import dev.stapler.stelekit.jobs.schedule.TaskOverrides
import dev.stapler.stelekit.jobs.schedule.TaskRegistry
import dev.stapler.stelekit.jobs.schedule.TaskRunContext
import dev.stapler.stelekit.jobs.schedule.TaskRunResult
import dev.stapler.stelekit.jobs.schedule.TaskShape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ModelDownloadTaskTest {
    private val context: Context = ApplicationProvider.getApplicationContext<Application>()
    private val server = ModelDownloadFakeServer()
    private val target = DepthModelStorage.modelFile(context)
    private val ctx = TaskRunContext(null, 0L, false, ConstraintState(online = true, isMetered = false, charging = null))
    private lateinit var fixture: ModelDownloadFixture

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build(),
        )
        fixture = ModelDownloadFixture(context, server.url)
    }

    @After
    fun tearDown() {
        server.stop()
        target.parentFile?.deleteRecursively()
        fixture.uninstall()
        scope.cancel()
    }

    private fun activeRequest() = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork(DepthModelDownloader.UNIQUE_WORK_NAME).get().first { !it.state.isFinished }

    private val scope = CoroutineScope(Dispatchers.Default)

    private fun startDownload(downloader: DepthModelDownloader): Deferred<*> {
        val waiting = scope.async { downloader.downloadModel() }
        runBlocking { withTimeout(5_000) { downloader.modelState.first { it is DepthModelDownloader.ModelState.Downloading } } }
        return waiting
    }

    @Test
    fun run_should_putReadyFileInPlaceAndReportProgress_When_serverServesModel() = runBlocking<Unit> {
        val percents = mutableListOf<Int>()
        val result = ModelDownloadTask(context, server.url).run(
            ModelDownloadTask.KEY, ctx.copy(reportProgress = { percents += it }),
        )

        assertEquals(TaskRunResult.Success, result)
        assertTrue(DepthModelStorage.isReady(target))
        assertTrue(percents.isNotEmpty() && percents.all { it in -1..100 })
        assertTrue(!java.io.File(target.parentFile, "${target.name}.part").exists())
    }

    @Test
    fun run_should_beRetryableFailure_When_serverErrors() = runBlocking<Unit> {
        server.status = 503
        val result = ModelDownloadTask(context, server.url).run(ModelDownloadTask.KEY, ctx)
        assertTrue(result is TaskRunResult.Failed && result.retryable, "$result")
    }

    @Test
    fun run_should_failTerminally_When_downloadedFileIsTooSmall() = runBlocking<Unit> {
        server.body = ByteArray(1024)
        val result = ModelDownloadTask(context, server.url).run(ModelDownloadTask.KEY, ctx)
        assertTrue(result is TaskRunResult.Failed && !result.retryable, "$result")
    }

    @Test
    fun spec_should_beOneShotWithSharedAttemptBudget_AndLegacyWorkName() {
        val spec = ModelDownloadTask.spec
        assertEquals(TaskShape.OneShot, spec.shape)
        assertEquals(DepthModelDownloadWorker.MAX_ATTEMPTS, spec.oneShotRetryCap)
        assertEquals(JobKind.ModelDownload, spec.jobKind)
        assertEquals(DepthModelDownloader.UNIQUE_WORK_NAME, ModelDownloadTask.KEY.workName())
    }

    @Test
    fun register_should_beIdempotentAndSupplyDataSyncForeground_When_calledTwice() = runBlocking<Unit> {
        val registry = TaskRegistry()
        val foreground = TaskForegroundProvider()
        registerModelDownloadTask(registry, context, foreground)
        registerModelDownloadTask(registry, context, foreground)

        assertEquals(setOf(ModelDownloadTask.KIND), registry.kinds())
        val info = foreground.factoryFor(ModelDownloadTask.KIND)!!.create(context, ModelDownloadTask.KEY)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
    }

    @Test
    fun downloadModel_should_tagRequestWithKindAndTitle_AndRequireStorageNotLow() {
        val downloader = fixture.downloader
        val waiting = startDownload(downloader)

        val request = activeRequest()
        assertTrue(JobTags.kind(JobKind.ModelDownload) in request.tags)
        assertTrue(JobTags.title("Depth model download") in request.tags)
        assertTrue(JobTags.ANY in request.tags)
        assertTrue(request.constraints.requiresStorageNotLow())
        downloader.cancelDownload()
        runBlocking { waiting.await() }
    }

    @Test
    fun downloadModel_should_useStoredConstraints_NotEnabled() = runBlocking<Unit> {
        fixture.store.write(
            ModelDownloadTask.KEY,
            TaskEntry(enabled = false, TaskOverrides(schedule = null, constraints = TaskConstraints(NetworkPolicy.Any))),
        )
        val waiting = startDownload(fixture.downloader)
        assertEquals(NetworkType.CONNECTED, activeRequest().constraints.requiredNetworkType)
        fixture.downloader.cancelDownload()
        waiting.await()

        fixture.store.write(
            ModelDownloadTask.KEY,
            TaskEntry(enabled = true, TaskOverrides(schedule = null, constraints = TaskConstraints(NetworkPolicy.Unmetered))),
        )
        val again = startDownload(fixture.downloader)
        assertEquals(NetworkType.UNMETERED, activeRequest().constraints.requiredNetworkType)
        fixture.downloader.cancelDownload()
        again.await()
    }

    @Test
    fun runOnce_should_keepOrReplace_ByConflictPolicy() = runBlocking<Unit> {
        fixture.scheduler.runOnce(ModelDownloadTask.KEY, TaskConstraints(NetworkPolicy.Unmetered), OneShotConflict.Keep)
        val first = activeRequest()
        fixture.scheduler.runOnce(ModelDownloadTask.KEY, TaskConstraints(NetworkPolicy.Any), OneShotConflict.Keep)
        assertEquals(first.id, activeRequest().id)

        fixture.scheduler.runOnce(ModelDownloadTask.KEY, TaskConstraints(NetworkPolicy.Any), OneShotConflict.Replace)
        val replaced = activeRequest()
        assertTrue(replaced.id != first.id, "Replace must enqueue a new request")
        assertEquals(NetworkType.CONNECTED, replaced.constraints.requiredNetworkType)
    }

    @Test
    fun downloadModel_should_defaultToUnmetered_When_storeIsEmptyOrUnreadable() = runBlocking<Unit> {
        assertEquals(NetworkPolicy.Unmetered, ModelDownloadTask.defaultConfig.constraints.network)
        val empty = startDownload(fixture.downloader)
        assertEquals(NetworkType.UNMETERED, activeRequest().constraints.requiredNetworkType)
        assertTrue(activeRequest().constraints.requiresStorageNotLow())
        fixture.downloader.cancelDownload()
        empty.await()

        fixture.settings.failReads = true
        val unreadable = startDownload(fixture.downloader)
        assertEquals(NetworkType.UNMETERED, activeRequest().constraints.requiredNetworkType)
        fixture.downloader.cancelDownload()
        unreadable.await()
    }
}
