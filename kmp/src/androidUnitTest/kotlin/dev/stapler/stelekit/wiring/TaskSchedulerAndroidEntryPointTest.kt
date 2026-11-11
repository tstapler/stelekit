// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.wiring

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import arrow.core.Either
import dev.stapler.stelekit.git.ColdPathRunner
import dev.stapler.stelekit.git.GitSyncTask
import dev.stapler.stelekit.git.GitSyncWorker
import dev.stapler.stelekit.git.GitSyncWorkerBinding
import dev.stapler.stelekit.git.TickOutcome
import dev.stapler.stelekit.git.WorkManagerSyncScheduler
import dev.stapler.stelekit.git.registerGitSyncTask
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.jobs.schedule.AppliedConfigLedger
import dev.stapler.stelekit.jobs.schedule.BackgroundTaskScheduler
import dev.stapler.stelekit.jobs.schedule.LegacyConfigSeed
import dev.stapler.stelekit.jobs.schedule.NetworkSnapshot
import dev.stapler.stelekit.jobs.schedule.NetworkSnapshotProvider
import dev.stapler.stelekit.jobs.schedule.SettingsTaskRunLog
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskRunResult
import dev.stapler.stelekit.jobs.schedule.TaskScope
import dev.stapler.stelekit.jobs.schedule.TaskRegistry
import dev.stapler.stelekit.jobs.schedule.TaskSettingsStore
import dev.stapler.stelekit.jobs.schedule.WorkManagerTaskScheduler
import dev.stapler.stelekit.platform.SCHEDULED_SYNC_ENABLED_KEY
import dev.stapler.stelekit.ui.rememberGraphContentScheduledSyncControls
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The Android root's port adapter ([WorkManagerTaskScheduler], built in `MainActivity`) reaches WorkManager
 * when the real scheduling stack composes: the enqueued unique periodic request carries the period and the
 * network constraint the stored config asked for, read back from WorkManager rather than from the adapter.
 * Shares its Robolectric limits with `GitSyncSchedulingEntryPointTest` (no FTS5, so a stub config repository).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TaskSchedulerAndroidEntryPointTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dataDir: File = createTempDirectory("task_scheduler_android_").toFile()
    private var fixture: WiredAppFixture? = null
    private val graphId = "task-scheduler-graph"

    @After
    fun tearDown() {
        GitSyncWorker.runLogOverride = null
        GitSyncWorker.snapshotProviderOverride = null
        GitSyncWorker.coldPathOverride = null
        fixture?.let { runBlocking { it.close() } }
        dataDir.deleteRecursively()
    }

    private fun workInfos(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerSyncScheduler.workNameFor(graphId)).get()

    private fun rootScheduler(ports: FakePlatformPorts, registry: TaskRegistry): BackgroundTaskScheduler {
        registerGitSyncTask(registry)
        return WorkManagerTaskScheduler(
            context = context,
            registry = registry,
            store = TaskSettingsStore(ports.settings, LegacyConfigSeed.None, registry),
            ledger = AppliedConfigLedger(ports.settings),
            workers = GitSyncWorkerBinding.resolver,
        )
    }

    @Test
    fun androidRoot_should_EnqueueUniquePeriodicWorkWithStoredConstraints_When_GitSyncStackComposes() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        val settings = FakeSettings().also { it.putBoolean(SCHEDULED_SYNC_ENABLED_KEY, true) }
        val wired = runBlocking {
            WiredAppFixture.android(
                context,
                dataDir,
                ports = FakePlatformPorts(settings = settings),
                taskScheduler = this@TaskSchedulerAndroidEntryPointTest::rootScheduler,
            )
        }
        fixture = wired
        val configRepo = StubConfigRepository(Either.Right(sampleConfig.copy(graphId = graphId, pollIntervalMinutes = 30)))

        composeRule.setContent { rememberGraphContentScheduledSyncControls(wired.deps, null, configRepo, graphId) }
        composeRule.waitForIdle()
        composeRule.waitUntil(10_000) { workInfos().isNotEmpty() }

        ObservableEffect.assert("task-work-enqueued") {
            val info = workInfos().single()
            assertEquals(WorkInfo.State.ENQUEUED, info.state)
            assertEquals(TimeUnit.MINUTES.toMillis(30), assertNotNull(info.periodicityInfo).repeatIntervalMillis)
            assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        }
    }

    @Test
    fun wiredApp_should_recordRunLogEntry_When_ScheduledWorkerFires() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        val settings = FakeSettings().also { it.putBoolean(SCHEDULED_SYNC_ENABLED_KEY, true) }
        // The worker's log goes to the same Settings the wired app reads its Logs screen from.
        val runLog = SettingsTaskRunLog(settings)
        GitSyncWorker.runLogOverride = { runLog }
        GitSyncWorker.snapshotProviderOverride = { NetworkSnapshotProvider { NetworkSnapshot(online = true, isMetered = false) } }
        GitSyncWorker.coldPathOverride = ColdPathRunner { TickOutcome.Fetched }
        val wired = runBlocking {
            WiredAppFixture.android(
                context,
                dataDir,
                ports = FakePlatformPorts(settings = settings),
                taskScheduler = this@TaskSchedulerAndroidEntryPointTest::rootScheduler,
            )
        }
        fixture = wired
        val configRepo = StubConfigRepository(Either.Right(sampleConfig.copy(graphId = graphId, pollIntervalMinutes = 30)))
        composeRule.setContent { rememberGraphContentScheduledSyncControls(wired.deps, null, configRepo, graphId) }
        composeRule.waitForIdle()
        composeRule.waitUntil(10_000) { workInfos().isNotEmpty() }

        val workId = workInfos().single().id
        val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        driver.setAllConstraintsMet(workId)
        driver.setPeriodDelayMet(workId)
        val key = TaskKey(GitSyncTask.KIND, TaskScope(graphId))
        composeRule.waitUntil(10_000) { runBlocking { runLog.recent(key).isNotEmpty() } }

        ObservableEffect.assert("task-work-enqueued") {
            val record = runBlocking { runLog.recent(key) }.first()
            assertEquals(TaskRunResult.Partial("fetched"), record.result)
            assertEquals(graphId, record.key.scope.value)
        }
    }
}
