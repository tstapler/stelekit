// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Story 3.1.2 (Phase 4 repair, P1-2): the thin [GitSyncWorker] evaluates the shared `NetworkGate` and appends
 * exactly one run-log record per run through the shared `recordRun`, with no registry and no store.
 * The clone-yield hook ordering (watchdog -> gate -> yield -> body -> notify) needs a RUNNING clone
 * `WorkInfo` under a cold process and is not driven here.
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

    @After
    fun tearDown() {
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
}
