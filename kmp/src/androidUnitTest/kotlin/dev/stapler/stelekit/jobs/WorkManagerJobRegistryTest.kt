package dev.stapler.stelekit.jobs

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.CloneProgress
import dev.stapler.stelekit.git.GitCloneWorkerData
import dev.stapler.stelekit.git.GitTransportRetryState
import dev.stapler.stelekit.jobs.schedule.AppliedConfigLedger
import dev.stapler.stelekit.jobs.schedule.ContractSettings
import dev.stapler.stelekit.jobs.schedule.LegacyConfigSeed
import dev.stapler.stelekit.jobs.schedule.Schedule
import dev.stapler.stelekit.jobs.schedule.TaskConfig
import dev.stapler.stelekit.jobs.schedule.TaskConstraints
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskKind
import dev.stapler.stelekit.jobs.schedule.TaskRegistry
import dev.stapler.stelekit.jobs.schedule.TaskRunResult
import dev.stapler.stelekit.jobs.schedule.TaskScope
import dev.stapler.stelekit.jobs.schedule.TaskShape
import dev.stapler.stelekit.jobs.schedule.TaskSpec
import dev.stapler.stelekit.jobs.schedule.TaskSettingsStore
import dev.stapler.stelekit.jobs.schedule.WorkManagerTaskScheduler
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    @Test
    fun `an enabled periodic graph adds no job row but a one-shot does`() = runBlocking<Unit> {
        val registry = TaskRegistry()
        val default = TaskConfig(true, Schedule.of(15, 0), TaskConstraints())
        registry.register(
            TaskKind("model-download"),
            TaskSpec(JobKind.ModelDownload, "Depth model download", TaskShape.OneShot, default, false, 0),
        ) { _, _ -> TaskRunResult.Success }
        val scheduler = WorkManagerTaskScheduler(
            context, registry, TaskSettingsStore(ContractSettings(), LegacyConfigSeed.None, registry), AppliedConfigLedger(ContractSettings()),
        )
        val jobs = WorkManagerJobRegistry(context)

        scheduler.apply(TaskKey(TaskKind("git-sync"), TaskScope("g1")), TaskConfig(true, Schedule.of(60, 0), TaskConstraints()))
        assertTrue(withTimeout(5_000) { jobs.jobs.first() }.isEmpty(), "periodic work carries no stelekit-job tag")

        scheduler.runOnce(TaskKey(TaskKind("model-download"), TaskScope("")), TaskConstraints())
        val row = withTimeout(5_000) { jobs.jobs.first { it.isNotEmpty() } }.single()
        assertEquals(JobKind.ModelDownload, row.kind)
        assertEquals("Depth model download", row.title)
    }

    @Test
    fun `clone progress is shown as a percentage once the total is known`() {
        val data = GitCloneWorkerData.encodeState(GitTransportRetryState.Attempting(CloneProgress("Receiving objects", 50, 200)))

        assertEquals(25, defaultProgressOf(JobKind.GitClone, data))
    }

    @Test
    fun `clone progress stays indeterminate before the total is known`() {
        val data = GitCloneWorkerData.encodeState(GitTransportRetryState.Attempting(CloneProgress("", 0, 0)))

        assertNull(defaultProgressOf(JobKind.GitClone, data))
    }

    @Test
    fun `a failed clone surfaces its error message`() {
        val output = GitCloneWorkerData.encodeFailure(DomainError.GitError.CloneFailed("repository not found"), null)

        assertEquals("repository not found", defaultErrorOf(JobKind.GitClone, output))
    }

    @Test
    fun `depth model download reports its percent and error`() {
        assertEquals(40, defaultProgressOf(JobKind.ModelDownload, workDataOf(DepthModelDownloadWorker.KEY_PROGRESS to 40)))
        assertNull(defaultProgressOf(JobKind.ModelDownload, Data.EMPTY))
        assertEquals("no space", defaultErrorOf(JobKind.ModelDownload, workDataOf(DepthModelDownloadWorker.KEY_ERROR to "no space")))
        assertNull(defaultProgressOf(JobKind.GitSync, Data.EMPTY))
        assertNull(defaultErrorOf(JobKind.GitSync, Data.EMPTY))
    }

    private fun info(state: WorkInfo.State, kind: JobKind, progress: Data = Data.EMPTY, output: Data = Data.EMPTY) =
        WorkInfo(UUID.randomUUID(), state, setOf(JobTags.ANY, JobTags.kind(kind)), output, progress)

    @Test
    fun `a running job carries the progress its kind reports`() {
        val status = info(WorkInfo.State.RUNNING, JobKind.ModelDownload, progress = workDataOf(DepthModelDownloadWorker.KEY_PROGRESS to 70))
            .toJobStatus(::defaultProgressOf, ::defaultErrorOf)

        assertEquals(JobState.Running(70), status?.state)
    }

    @Test
    fun `a failed job carries the error its kind reports`() {
        val status = info(WorkInfo.State.FAILED, JobKind.ModelDownload, output = workDataOf(DepthModelDownloadWorker.KEY_ERROR to "offline"))
            .toJobStatus(::defaultProgressOf, ::defaultErrorOf)

        assertEquals(JobState.Failed("offline"), status?.state)
    }
}
