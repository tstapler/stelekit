package dev.stapler.stelekit.jobs

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.stapler.stelekit.platform.ml.DepthModelDownloadWorker
import dev.stapler.stelekit.platform.ml.DepthModelDownloader
import dev.stapler.stelekit.platform.ml.DepthModelStorage
import kotlinx.coroutines.async
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WorkManagerJobRegistryTest {
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

    private suspend fun WorkManagerJobRegistry.firstJobs() = withTimeout(5_000) { jobs.first { it.isNotEmpty() } }

    @Test
    fun `a queued depth model download is listed with its kind and title`() = runBlocking<Unit> {
        val waiter = async { DepthModelDownloader(context).downloadModel() }
        val registry = WorkManagerJobRegistry(context)

        val job = registry.firstJobs().single()

        assertEquals(JobKind.ModelDownload, job.kind)
        assertEquals("Depth model download", job.title)
        assertTrue(job.state is JobState.Queued, "constraints are unmet in the test harness: ${job.state}")
        waiter.cancel()
    }

    @Test
    fun `cancelling a listed job marks it cancelled`() = runBlocking<Unit> {
        val waiter = async { DepthModelDownloader(context).downloadModel() }
        val registry = WorkManagerJobRegistry(context)
        val id = registry.firstJobs().single().id

        registry.cancel(id)

        val cancelled = withTimeout(5_000) { registry.jobs.first { list -> list.any { it.state is JobState.Cancelled } } }
        assertEquals(id, cancelled.single().id)
        waiter.cancel()
    }

    @Test
    fun `clearFinished drops finished jobs from the list`() = runBlocking<Unit> {
        val waiter = async { DepthModelDownloader(context).downloadModel() }
        val registry = WorkManagerJobRegistry(context)
        registry.cancel(registry.firstJobs().single().id)
        withTimeout(5_000) { registry.jobs.first { list -> list.any { it.state is JobState.Cancelled } } }

        registry.clearFinished()

        withTimeout(5_000) { registry.jobs.first { it.isEmpty() } }
        waiter.cancel()
    }

    @Test
    fun `work without a job kind tag is ignored`() = runBlocking<Unit> {
        val request = OneTimeWorkRequestBuilder<DepthModelDownloadWorker>().addTag(JobTags.ANY).build()
        WorkManager.getInstance(context).enqueue(request)

        val registry = WorkManagerJobRegistry(context)

        assertTrue(withTimeout(5_000) { registry.jobs.first() }.isEmpty())
        assertNotNull(registry)
    }
}
