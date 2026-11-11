package dev.stapler.stelekit.platform.ml

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.stapler.stelekit.ui.annotate.DepthModelUiState
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield
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

/** The facade over the WorkManager job: enqueue-once, re-attach after a "restart", cancel, and the ready fast path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DepthModelDownloaderTest {
    private companion object {
        const val SETTLE_YIELDS = 50
        const val SETTLE_MS = 1_000L
    }

    private val context: Context = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build(),
        )
    }

    @After
    fun tearDown() {
        DepthModelStorage.modelFile(context).parentFile?.deleteRecursively()
    }

    private fun jobs() = WorkManager.getInstance(context).getWorkInfosForUniqueWork(DepthModelDownloader.UNIQUE_WORK_NAME).get()

    private suspend fun DepthModelDownloader.awaitDownloading() = withTimeout(5_000) {
        modelState.first { it is DepthModelDownloader.ModelState.Downloading }
    }

    @Test
    fun `a ready model short-circuits without enqueueing`() = runBlocking<Unit> {
        val file = DepthModelStorage.modelFile(context).also { it.parentFile!!.mkdirs() }
        file.writeBytes(ByteArray((DepthModelStorage.MIN_MODEL_BYTES + 1).toInt()))

        assertTrue(DepthModelDownloader(context).downloadModel().isRight())
        assertTrue(jobs().isEmpty())
    }

    @Test
    fun `a second caller re-attaches to the queued job instead of enqueueing another`() = runBlocking<Unit> {
        val downloader = DepthModelDownloader(context)
        val first = async { downloader.downloadModel() }
        downloader.awaitDownloading()
        val second = async { downloader.downloadModel() }
        repeat(SETTLE_YIELDS) { yield() }
        // runOnce hops to a real dispatcher (settings read), which yield() cannot wait for; cancelling before the
        // second enqueue lands lets it start a fresh, never-satisfied job and hangs the awaits below.
        kotlinx.coroutines.delay(SETTLE_MS)

        assertEquals(1, jobs().size, "KEEP must not create a second job")

        downloader.cancelDownload()
        assertEquals(first.await().isLeft(), second.await().isLeft())
    }

    @Test
    fun `a fresh instance after a restart sees the job that is still queued`() = runBlocking<Unit> {
        val before = DepthModelDownloader(context)
        val waiting = async { before.downloadModel() }
        before.awaitDownloading()

        val afterRestart = DepthModelDownloader(context)
        afterRestart.awaitDownloading()
        assertTrue(jobs().single().state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING))

        before.cancelDownload()
        waiting.await()
    }

    @Test
    fun `cancel resolves a waiting caller and surfaces Cancelled`() = runBlocking<Unit> {
        val downloader = DepthModelDownloader(context)
        val waiting = async { downloader.downloadModel() }
        downloader.awaitDownloading()

        downloader.cancelDownload()

        assertTrue(waiting.await().isLeft())
        assertEquals(DepthModelDownloader.ModelState.Cancelled, downloader.modelState.value)
        assertEquals(DepthModelUiState.Cancelled, downloader.modelState.value.toUiState())
        assertEquals(ModelDownloadPresentation.CANCELLED, ModelDownloadPresentation.present(ModelDownloadInput(ModelWorkState.Cancelled))?.text)
    }

    @Test
    fun `cancel with nothing in flight is a no-op`() {
        DepthModelDownloader(context).cancelDownload()
    }
}
