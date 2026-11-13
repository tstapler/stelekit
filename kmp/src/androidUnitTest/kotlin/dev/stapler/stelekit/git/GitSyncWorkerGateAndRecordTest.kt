// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
import dev.stapler.stelekit.jobs.schedule.NetworkSnapshot
import dev.stapler.stelekit.jobs.schedule.NetworkSnapshotProvider
import dev.stapler.stelekit.jobs.schedule.RunSource
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskRunLog
import dev.stapler.stelekit.jobs.schedule.TaskRunRecord
import dev.stapler.stelekit.jobs.schedule.TaskRunResult
import dev.stapler.stelekit.jobs.schedule.TaskScope
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Story 3.1.2 (Phase 4 repair, P1-2): the thin [GitSyncWorker] evaluates the shared `NetworkGate` and appends
 * exactly one run-log record per run through the shared `recordRun`, with no registry and no store.
 * The clone-yield test holds a real RUNNING work item under the tracked clone work id (a blocked stand-in
 * worker on a thread-pool executor) so the production `isGitCloneWorkerRunningFor` path is exercised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GitSyncWorkerGateAndRecordTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val graphId = "gate-record-graph"
    private val key = TaskKey(GitSyncTask.KIND, TaskScope(graphId))

    private class CapturingRunLog : TaskRunLog {
        val records = mutableListOf<TaskRunRecord>()
        override suspend fun append(record: TaskRunRecord) { records += record }
        override suspend fun recent(key: TaskKey) = records.filter { it.key == key }
        override suspend fun lastSuccessAtMs(key: TaskKey): Long? = null
    }

    /** Stands in for a RUNNING GitCloneWorker: blocks until [release] is counted down. */
    class BlockedCloneStandIn(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            release.await()
            return Result.success()
        }

        companion object {
            @Volatile var release = CountDownLatch(1)
        }
    }

    private var fetches = 0
    private val log = CapturingRunLog()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        GitSyncWorker.runLogOverride = { log }
    }

    private val events = mutableListOf<String>()

    /** Records the order of the worker's hook calls; [postRunFails] makes postRun throw after the fetch succeeded. */
    private fun recordingHooks(postRunFails: Boolean = false, yield: Boolean = false) {
        GitSyncWorker.hooksOverride = object : GitSyncWorkerHooks {
            override suspend fun runWatchdog(context: Context, graphId: String) { events += "watchdog" }
            override suspend fun shouldYieldToCloneWorker(context: Context, graphId: String): Boolean { events += "yield-check"; return yield }
            override suspend fun postRun(context: Context, key: TaskKey, input: androidx.work.Data, result: TaskRunResult) {
                events += "postRun:${result::class.simpleName}"
                if (postRunFails) throw IllegalStateException("notifier exploded")
            }
        }
    }

    @After
    fun tearDown() {
        GitSyncWorker.hooksOverride = null
        GitSyncWorker.snapshotProviderOverride = null
        GitSyncWorker.runLogOverride = null
        GitSyncServiceRegistry.unregister(graphId)
    }

    private fun provider(online: Boolean = true, metered: Boolean?) {
        GitSyncWorker.snapshotProviderOverride = { NetworkSnapshotProvider { NetworkSnapshot(online, metered) } }
    }

    private fun throwingProvider() {
        GitSyncWorker.snapshotProviderOverride = { NetworkSnapshotProvider { throw SecurityException("no ACCESS_NETWORK_STATE") } }
    }

    private fun registerService(result: Either<DomainError.GitError, FetchResult> = FetchResult(false, 0).right()) {
        NetworkMonitor.init(context)
        val fs = StubFileSystem()
        val service = GitSyncService(
            gitRepository = object : StubGitRepository() {
                override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> {
                    fetches++
                    return result
                }
            },
            graphLoader = GraphLoader(fs, InMemoryPageRepository(), InMemoryBlockRepository()),
            graphWriter = GraphWriter(fileSystem = fs),
            editLock = EditLock(),
            configRepository = StubConfigRepository(sampleConfig.right()),
            networkMonitor = NetworkMonitor(),
            fileSystem = fs,
            graphId = graphId,
            settings = MapSettings(),
            isOnline = { true },
            isAppOwnedPath = { true },
        )
        GitSyncServiceRegistry.register(graphId, service)
    }

    private class MapSettings : dev.stapler.stelekit.platform.Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store[key] ?: defaultValue
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private fun run(policy: String? = null, interval: Long? = null): ListenableWorker.Result {
        val data = buildList {
            add(GitSyncWorker.KEY_GRAPH_ID to graphId)
            if (policy != null) add(GitSyncWorker.KEY_NETWORK_POLICY to policy)
            if (interval != null) add(GitSyncWorker.KEY_INTERVAL_MINUTES to interval)
        }
        val worker = TestListenableWorkerBuilder<GitSyncWorker>(context).setInputData(workDataOf(*data.toTypedArray())).build()
        return runBlocking { worker.doWork() }
    }

    private fun onlyRecord(): TaskRunRecord {
        assertEquals(1, log.records.size, "exactly one run-log record per run: ${log.records}")
        return log.records.single().also {
            assertEquals(key, it.key)
            assertEquals(RunSource.Background, it.source)
        }
    }

    @Test
    fun doWork_should_skipMeteredAndLogOneRecord_When_UnmeteredPolicyOnMeteredNetwork() {
        registerService(); provider(metered = true)

        val result = run(policy = "unmetered")

        assertIs<ListenableWorker.Result.Success>(result)
        assertEquals(0, fetches, "no git call on a metered network")
        assertEquals(TaskRunResult.Skipped("metered"), onlyRecord().result)
    }

    @Test
    fun doWork_should_skipUnknownNetwork_When_UnmeteredPolicyAndMeteringUnknown() {
        registerService(); provider(metered = null)
        assertIs<ListenableWorker.Result.Success>(run(policy = "unmetered"))
        assertEquals(TaskRunResult.Skipped("unknown-network"), onlyRecord().result)
        assertEquals(0, fetches)
    }

    @Test
    fun doWork_should_skipUnknownNetwork_When_UnmeteredPolicyAndProviderThrows() {
        registerService(); throwingProvider()
        assertIs<ListenableWorker.Result.Success>(run(policy = "unmetered"))
        assertEquals(TaskRunResult.Skipped("unknown-network"), onlyRecord().result)
        assertEquals(0, fetches)
    }

    @Test
    fun doWork_should_skipOffline_When_ProviderExplicitlyReportsOffline() {
        registerService(); provider(online = false, metered = null)
        assertIs<ListenableWorker.Result.Success>(run(policy = "any"))
        assertEquals(TaskRunResult.Skipped("offline"), onlyRecord().result)
        assertEquals(0, fetches)
    }

    @Test
    fun doWork_should_proceed_When_PolicyAnyAndNetworkMetered() {
        registerService(); provider(metered = true)
        assertIs<ListenableWorker.Result.Success>(run(policy = "any"))
        assertEquals(1, fetches)
        assertTrue(onlyRecord().result !is TaskRunResult.Skipped)
    }

    @Test
    fun doWork_should_proceed_When_PolicyKeyAbsentAsInAPreUpgradeJob() {
        registerService(); provider(metered = true)
        assertIs<ListenableWorker.Result.Success>(run(policy = null))
        assertEquals(1, fetches)
        onlyRecord()
    }

    @Test
    fun doWork_should_proceed_When_PolicyAnyAndProviderThrows() {
        registerService(); throwingProvider()
        assertIs<ListenableWorker.Result.Success>(run(policy = null))
        assertEquals(1, fetches, "WorkManager's CONNECTED constraint already held, so an unreadable provider proceeds for Any")
        onlyRecord()
    }

    @Test
    fun doWork_should_logFailedAndReturnFailure_When_FetchFails() {
        registerService(DomainError.GitError.RetryExhausted(5, DomainError.GitError.FetchFailed("boom")).left())
        provider(metered = false)

        val result = run()

        assertIs<ListenableWorker.Result.Failure>(result)
        assertIs<TaskRunResult.Failed>(onlyRecord().result)
    }

    @Test
    fun doWork_should_recordConstraintStateReadAtRunStart_When_Run() {
        registerService(); provider(online = true, metered = false)
        run()
        val state = onlyRecord().constraintState
        assertEquals(true, state.online)
        assertEquals(false, state.isMetered)
    }

    @Test
    fun doWork_should_keepResultUnchanged_When_RunLogWriteThrows() {
        registerService(); provider(metered = false)
        GitSyncWorker.runLogOverride = {
            object : TaskRunLog {
                override suspend fun append(record: TaskRunRecord) = throw IllegalStateException("keystore failure")
                override suspend fun recent(key: TaskKey) = emptyList<TaskRunRecord>()
                override suspend fun lastSuccessAtMs(key: TaskKey): Long? = null
            }
        }

        assertIs<ListenableWorker.Result.Success>(run())
        assertEquals(1, fetches)
    }

    @Test
    fun doWork_should_keepResultUnchanged_When_RunLogCannotBeBuilt() {
        registerService(); provider(metered = false)
        GitSyncWorker.runLogOverride = { throw IllegalStateException("settings unavailable") }
        assertIs<ListenableWorker.Result.Success>(run())
    }

    @Test
    fun doWork_should_appendExactlyOneRecord_When_SuccessSkipOrFailure() {
        provider(metered = false)
        registerService()
        run()
        assertTrue(onlyRecord().result !is TaskRunResult.Skipped && onlyRecord().result !is TaskRunResult.Failed)

        log.records.clear()
        provider(metered = true)
        run(policy = "unmetered")
        assertEquals(TaskRunResult.Skipped("metered"), onlyRecord().result)

        log.records.clear()
        provider(metered = false)
        GitSyncServiceRegistry.unregister(graphId)
        registerService(DomainError.GitError.RetryExhausted(5, DomainError.GitError.FetchFailed("boom")).left())
        run()
        assertIs<TaskRunResult.Failed>(onlyRecord().result)
    }

    @Test
    fun doWork_should_runPreRunGateYieldBodyPostRunInOrder_When_CloneRunning() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(Executors.newCachedThreadPool()).build(),
        )
        BlockedCloneStandIn.release = CountDownLatch(1)
        val clone = OneTimeWorkRequestBuilder<BlockedCloneStandIn>().build()
        try {
            WorkManager.getInstance(context).enqueue(clone)
            runBlocking {
                withTimeout(CLONE_RUNNING_TIMEOUT_MS) {
                    WorkManager.getInstance(context).getWorkInfoByIdFlow(clone.id).first { it?.state == WorkInfo.State.RUNNING }
                }
            }
            GitCloneWorkTracker.recordStart(context, graphId, clone.id, System.currentTimeMillis())
            provider(metered = true)

            // Gate precedes the clone yield: a metered network on an unmetered policy is reported as metered, not clone-running.
            assertEquals(1, runGated("unmetered", "metered"))
            // Gate passed -> clone yield: skipped before the body, so no git call.
            assertEquals(2, runGated("any", "clone-running"))
            assertEquals(0, fetches, "the body must not run while a clone is RUNNING")
        } finally {
            GitCloneWorkTracker.clear(context, graphId)
            BlockedCloneStandIn.release.countDown()
        }
    }

    @Test
    fun doWork_should_runWatchdogThenBodyThenPostRun_When_FetchSucceeds() {
        registerService(); provider(metered = false); recordingHooks()

        assertIs<ListenableWorker.Result.Success>(run())

        assertEquals(1, fetches)
        assertEquals(listOf("watchdog", "yield-check", "postRun:Partial"), events)
        assertEquals(1, log.records.size)
    }

    @Test
    fun doWork_should_runWatchdogButSkipPostRun_When_NetworkGateSkips() {
        registerService(); provider(metered = true); recordingHooks()

        assertIs<ListenableWorker.Result.Success>(run(policy = "unmetered"))

        assertEquals(listOf("watchdog"), events, "postRun (the notifier) must not run on a skipped tick")
        assertEquals(TaskRunResult.Skipped("metered"), onlyRecord().result)
    }

    @Test
    fun doWork_should_skipPostRun_When_CloneYieldSkips() {
        provider(metered = false); recordingHooks(yield = true)

        assertIs<ListenableWorker.Result.Success>(run())

        assertEquals(listOf("watchdog", "yield-check"), events)
        assertEquals(TaskRunResult.Skipped("clone-running"), onlyRecord().result)
        assertEquals(0, fetches)
    }

    @Test
    fun doWork_should_stayRecordedSuccessAndSucceed_When_PostRunThrowsAfterGoodFetch() {
        registerService(); provider(metered = false); recordingHooks(postRunFails = true)

        val result = run()

        assertIs<ListenableWorker.Result.Success>(result, "a notifier failure must not turn a successful fetch into Failed")
        assertEquals(1, fetches)
        assertTrue(onlyRecord().result !is TaskRunResult.Failed, "recorded: ${log.records}")
    }

    /** Runs the worker with no live service and returns the cumulative record count after asserting the newest skip reason. */
    private fun runGated(policy: String, expectedReason: String): Int {
        assertIs<ListenableWorker.Result.Success>(run(policy = policy))
        assertEquals(TaskRunResult.Skipped(expectedReason), log.records.last().result)
        return log.records.size
    }

    private companion object {
        const val CLONE_RUNNING_TIMEOUT_MS = 10_000L
    }
}
