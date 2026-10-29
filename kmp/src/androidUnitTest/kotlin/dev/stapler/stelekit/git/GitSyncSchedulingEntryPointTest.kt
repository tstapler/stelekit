// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import arrow.core.Either
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.platform.SCHEDULED_SYNC_ENABLED_KEY
import dev.stapler.stelekit.ui.ScheduledSyncControls
import dev.stapler.stelekit.ui.rememberGraphContentScheduledSyncControls
import dev.stapler.stelekit.wiring.FakePlatformPorts
import dev.stapler.stelekit.wiring.FakeSettings
import dev.stapler.stelekit.wiring.ObservableEffect
import dev.stapler.stelekit.wiring.WiredAppFixture
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
 * IT-32: the Android scheduling half of the sync stack. With `scheduled_sync_enabled` on and a
 * stored interval of 5 minutes, composing the stack enqueues exactly one unique periodic job named
 * for the graph at WorkManager's 15-minute floor; turning the switch off cancels it.
 *
 * The scheduling part of the stack needs a `GitConfigRepository`, which only the SQLDelight backend
 * provides, and Robolectric's SQLite has no FTS5 (see `WiredAppFixture.android`). So this test composes
 * the stack's scheduling function directly over a stub config repository; the loop half over a real
 * database runs in `GitSyncStackEntryPointTest` (jvmTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GitSyncSchedulingEntryPointTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dataDir: File = createTempDirectory("git_sync_scheduling_").toFile()
    private var fixture: WiredAppFixture? = null
    private val graphId = "scheduling-graph"

    @After
    fun tearDown() {
        fixture?.let { runBlocking { it.close() } }
        dataDir.deleteRecursively()
    }

    private fun workInfos(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerSyncScheduler.workNameFor(graphId)).get()

    @Test
    fun androidStack_should_EnqueueOneUniquePeriodic15MinWork_When_FlagOnInterval5_AndCancelWhenOff() {
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
                schedulerFactory = BackgroundSyncSchedulerFactory(
                    minimumIntervalMinutes = WorkManagerSyncScheduler.MIN_PERIODIC_INTERVAL_MINUTES.toInt(),
                ) { id -> WorkManagerSyncScheduler(context, id) },
            )
        }
        fixture = wired
        val configRepo = StubConfigRepository(Either.Right(sampleConfig.copy(graphId = graphId, pollIntervalMinutes = 5)))

        var controls: ScheduledSyncControls? = null
        composeRule.setContent { controls = rememberGraphContentScheduledSyncControls(wired.deps, null, configRepo, graphId) }
        composeRule.waitForIdle()

        ObservableEffect.assert("periodic-work-enqueued") {
            val infos = workInfos()
            assertEquals(1, infos.size, "one unique periodic job named for the graph")
            assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
            val period = assertNotNull(infos.single().periodicityInfo)
            assertEquals(TimeUnit.MINUTES.toMillis(15), period.repeatIntervalMillis, "5 minutes is raised to the 15-minute floor")
        }
        assertEquals(true, controls?.closedAppChecks)
        assertEquals(15, controls?.minimumIntervalMinutes)

        composeRule.runOnIdle { controls!!.setEnabled(false) }
        composeRule.waitForIdle()
        assertEquals(WorkInfo.State.CANCELLED, workInfos().single().state, "turning the switch off cancels the job")
    }

    @Test
    fun androidStack_should_EnqueueNothing_When_FlagUnset() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        val wired = runBlocking {
            WiredAppFixture.android(
                context,
                dataDir,
                schedulerFactory = BackgroundSyncSchedulerFactory(minimumIntervalMinutes = 15) { id -> WorkManagerSyncScheduler(context, id) },
            )
        }
        fixture = wired
        val configRepo = StubConfigRepository(Either.Right(sampleConfig.copy(graphId = graphId, pollIntervalMinutes = 5)))
        composeRule.setContent { rememberGraphContentScheduledSyncControls(wired.deps, null, configRepo, graphId) }
        composeRule.waitForIdle()
        assertEquals(emptyList(), workInfos().filter { it.state == WorkInfo.State.ENQUEUED })
    }
}
