// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import arrow.core.Either
import dev.stapler.stelekit.git.GitSyncTask
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.jobs.schedule.LegacyConfigSeed
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskScope
import dev.stapler.stelekit.jobs.schedule.TaskSettingsStore
import dev.stapler.stelekit.platform.SCHEDULED_SYNC_ENABLED_KEY
import dev.stapler.stelekit.wiring.FakePlatformPorts
import dev.stapler.stelekit.wiring.FakeSettings
import dev.stapler.stelekit.wiring.WiredAppFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Settings switch drives the graph's `git-sync` store entry, not the legacy global flag. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ScheduledSyncControlsApplyTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dataDir: File = createTempDirectory("scheduled_sync_controls_").toFile()
    private var fixture: WiredAppFixture? = null

    @After
    fun tearDown() {
        fixture?.let { runBlocking { it.close() } }
        dataDir.deleteRecursively()
    }

    @Test
    fun setEnabledFalse_should_PersistDisabledToTheGraphStoreEntry_AndLeaveLegacyFlagAlone() {
        val settings = FakeSettings().also { it.putBoolean(SCHEDULED_SYNC_ENABLED_KEY, true) }
        val wired = runBlocking { WiredAppFixture.android(context, dataDir, ports = FakePlatformPorts(settings = settings)) }
        fixture = wired
        val graphId = "apply-graph"
        val key = TaskKey(GitSyncTask.KIND, TaskScope(graphId))
        val store = TaskSettingsStore(settings, LegacyConfigSeed.None, wired.deps.platformIntegrations.taskRegistry)
        val configRepo = StubConfigRepository(Either.Right(sampleConfig.copy(graphId = graphId, pollIntervalMinutes = 30)))

        var controls: ScheduledSyncControls? = null
        composeRule.setContent { controls = rememberGraphContentScheduledSyncControls(wired.deps, null, configRepo, graphId) }
        composeRule.waitUntil(5_000) { controls?.enabled == true }
        assertEquals(30, runBlocking { store.read(key) }.schedule.intervalMinutes, "the legacy flag and interval were migrated")

        composeRule.runOnIdle { controls!!.setEnabled(false) }
        composeRule.waitUntil(5_000) { controls?.enabled == false }

        assertFalse(runBlocking { store.read(key) }.enabled, "the store entry is disabled")
        assertTrue(settings.getBoolean(SCHEDULED_SYNC_ENABLED_KEY, false), "the legacy flag is not rewritten")
    }
}
