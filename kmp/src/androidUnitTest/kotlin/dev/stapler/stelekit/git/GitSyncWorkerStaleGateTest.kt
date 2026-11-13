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

import dev.stapler.stelekit.jobs.schedule.KnownScopes
import dev.stapler.stelekit.jobs.schedule.KnownScopesResult
import dev.stapler.stelekit.jobs.schedule.LegacyConfigSeed
import dev.stapler.stelekit.jobs.schedule.TaskEntry
import dev.stapler.stelekit.jobs.schedule.TaskSettingsStore
import dev.stapler.stelekit.jobs.schedule.TaskWorkerRuntime
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.platform.SteleKitContext

/** Issue #655: a surviving git-sync job honours the stored `enabled` flag (periodic only) and a readable `KnownScopes`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GitSyncWorkerStaleGateTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val graphId = "stale-gate-graph"
    private val key = TaskKey(GitSyncTask.KIND, TaskScope(graphId))
    private val log = object : TaskRunLog {
        val records = mutableListOf<TaskRunRecord>()
        override suspend fun append(record: TaskRunRecord) { records += record }
        override suspend fun recent(key: TaskKey) = records.filter { it.key == key }
        override suspend fun lastSuccessAtMs(key: TaskKey): Long? = null
    }
    private var fetches = 0
    private val store get() = TaskSettingsStore(PlatformSettings(), LegacyConfigSeed.None, TaskWorkerRuntime.registry)

    @Before
    fun setUp() {
        SteleKitContext.init(context)
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        GitSyncWorker.runLogOverride = { log }
        GitSyncWorker.snapshotProviderOverride = { NetworkSnapshotProvider { NetworkSnapshot(true, false) } }
        GitSyncWorker.hooksOverride = object : GitSyncWorkerHooks {
            override suspend fun runWatchdog(context: Context, graphId: String) = Unit
            override suspend fun shouldYieldToCloneWorker(context: Context, graphId: String) = false
            override suspend fun postRun(context: Context, key: TaskKey, input: androidx.work.Data, result: TaskRunResult) = Unit
            override suspend fun staleJobReason(context: Context, graphId: String, periodic: Boolean) =
                GitSyncAdapterHooks.staleJobReason(graphId, periodic)
        }
        TaskWorkerRuntime.knownScopes = KnownScopes { KnownScopesResult.Unknown }
        runBlocking { store.write(key, TaskEntry(enabled = true, overrides = null)) }
        registerService()
    }

    @After
    fun tearDown() {
        GitSyncWorker.hooksOverride = null
        GitSyncWorker.snapshotProviderOverride = null
        GitSyncWorker.runLogOverride = null
        TaskWorkerRuntime.knownScopes = KnownScopes { KnownScopesResult.Unknown }
        GitSyncServiceRegistry.unregister(graphId)
    }

    private fun registerService() {
        NetworkMonitor.init(context)
        val fs = StubFileSystem()
        val service = GitSyncService(
            gitRepository = object : StubGitRepository() {
                override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> {
                    fetches++
                    return FetchResult(false, 0).right()
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

    private fun run(periodic: Boolean = true): ListenableWorker.Result {
        val data = buildList {
            add(GitSyncWorker.KEY_GRAPH_ID to graphId)
            if (periodic) add(GitSyncWorker.KEY_INTERVAL_MINUTES to 15L)
        }
        val worker = TestListenableWorkerBuilder<GitSyncWorker>(context).setInputData(workDataOf(*data.toTypedArray())).build()
        return runBlocking { worker.doWork() }
    }

    private fun assertSkipped(reason: String) {
        assertEquals(0, fetches, "a skipped tick must not sync")
        assertEquals(TaskRunResult.Skipped(reason), log.records.single().result)
    }

    @Test
    fun doWork_should_skipWithoutSync_When_PeriodicTaskDisabled() {
        runBlocking { store.write(key, TaskEntry(enabled = false, overrides = null)) }
        assertIs<ListenableWorker.Result.Success>(run())
        assertSkipped("disabled")
    }

    @Test
    fun doWork_should_skipWithoutSync_When_GraphTombstoned() {
        runBlocking { store.tombstone(key) }
        assertIs<ListenableWorker.Result.Success>(run())
        assertSkipped("disabled")
    }

    @Test
    fun doWork_should_skipWithoutSync_When_GraphMissingFromKnownScopes() {
        TaskWorkerRuntime.knownScopes = KnownScopes { KnownScopesResult.Known(setOf(TaskScope("other"))) }
        assertIs<ListenableWorker.Result.Success>(run())
        assertSkipped("graph-removed")
    }

    @Test
    fun doWork_should_run_When_KnownScopesUnknown() {
        assertIs<ListenableWorker.Result.Success>(run())
        assertEquals(1, fetches)
        assertTrue(log.records.single().result !is TaskRunResult.Skipped)
    }

    @Test
    fun doWork_should_run_When_EnabledAndGraphKnown() {
        TaskWorkerRuntime.knownScopes = KnownScopes { KnownScopesResult.Known(setOf(TaskScope(graphId))) }
        assertIs<ListenableWorker.Result.Success>(run())
        assertEquals(1, fetches)
    }

    @Test
    fun doWork_should_run_When_ExplicitOneShotAndTaskDisabled() {
        runBlocking { store.write(key, TaskEntry(enabled = false, overrides = null)) }
        assertIs<ListenableWorker.Result.Success>(run(periodic = false))
        assertEquals(1, fetches)
    }
}
