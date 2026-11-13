// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only

package dev.stapler.stelekit.wiring

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.desktop.createDesktopTaskRegistry
import dev.stapler.stelekit.desktop.createDesktopTaskScheduler
import dev.stapler.stelekit.git.GitSyncTask
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.jobs.schedule.SettingsTaskRunLog
import dev.stapler.stelekit.jobs.schedule.TaskConstraints
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskScope
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.platform.SCHEDULED_SYNC_ENABLED_KEY
import dev.stapler.stelekit.ui.GitSyncServiceSeams
import dev.stapler.stelekit.ui.GraphContentGitSyncStack
import dev.stapler.stelekit.ui.components.settings.BackgroundTasksSettingsState
import dev.stapler.stelekit.ui.components.settings.PanelEdit
import dev.stapler.stelekit.ui.components.settings.SaveResult
import dev.stapler.stelekit.ui.rememberBackgroundTasksSettingsState
import dev.stapler.stelekit.ui.rememberGraphContentGitSyncStack
import dev.stapler.stelekit.ui.rememberGraphContentGraphIoStack
import dev.stapler.stelekit.ui.rememberGraphContentVaultSetup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Desktop composition root's scheduling wiring (M1/M3/M4 of the phase 5 review): the registry the root
 * builds lists `git-sync`, one settings instance serves scheduler and UI, and a Settings edit reaches the
 * running `PeriodicSyncDriver` loop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopTaskRootWiringTest {
    private val minute = 60_000L
    private val dataDir: File = createTempDirectory("desktop_task_root_").toFile()
    private val testScheduler = TestCoroutineScheduler()
    private val settings = FakeSettings()
    private var fixture: DesktopWiredAppFixture? = null
    private var headless: HeadlessComposition? = null
    private var stack: GraphContentGitSyncStack? = null
    private var tasksState by mutableStateOf<BackgroundTasksSettingsState?>(null)
    private var graphId = ""

    @AfterTest
    fun tearDown() {
        headless?.close()
        fixture?.let { runBlocking { it.close() } }
        dataDir.deleteRecursively()
    }

    private fun open(pollMinutes: Int) {
        settings.putBoolean(SCHEDULED_SYNC_ENABLED_KEY, true)
        val wired = runBlocking {
            DesktopWiredAppFixture.desktop(
                dataDir,
                graphPath = "/test/wired-graph-${System.nanoTime()}",
                ports = FakePlatformPorts(settings = settings, gitRepository = StubGitRepository()),
                seams = GitSyncServiceSeams(periodicSyncContext = StandardTestDispatcher(testScheduler), isOnline = { true }),
            )
        }
        fixture = wired
        graphId = wired.graphManager.graphRegistry.value.activeGraphId!!.value
        runBlocking { wired.graphManager.createGitConfigRepository()!!.saveConfig(sampleConfig.copy(graphId = graphId, pollIntervalMinutes = pollMinutes)) }
        headless = HeadlessComposition().also { composition ->
            composition.setContent {
                val vault = rememberGraphContentVaultSetup(wired.graphManager, wired.ports.fileSystem, null)
                val io = rememberGraphContentGraphIoStack(
                    wired.deps, vault.effectiveFileSystem, vault.activeGraphPath, vault.activeGraphInfo,
                    dev.stapler.stelekit.logging.Logger("DesktopTaskRootWiringTest"),
                )
                val gitStack = rememberGraphContentGitSyncStack(wired.deps, io, vault.vaultCredentialStore)
                stack = gitStack
                tasksState = rememberBackgroundTasksSettingsState(
                    wired.deps, gitStack, emptyList(), onAddGraph = {},
                )
            }
        }
    }

    private fun awaitCondition(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for $what")
            headless!!.advance()
            testScheduler.runCurrent()
            Thread.yield()
        }
    }

    private fun nextCheckInMinutes(): Long? =
        stack?.gitSyncService?.scheduledSyncStatus?.value?.nextAtMs?.let { (it - System.currentTimeMillis()) / minute }

    @Test
    fun desktopRoot_should_ListGitSyncInBackgroundTasksWithDefaultJitter_When_RegistryBuiltByRootFactory() {
        open(pollMinutes = 30)
        val registry = fixture!!.taskRegistry
        assertTrue(GitSyncTask.KIND in registry.kinds(), "the Desktop registry lists git-sync")
        assertEquals(GitSyncTask.defaultConfig.schedule.jitterMinutes, registry.specFor(GitSyncTask.KIND)!!.defaultConfig.schedule.jitterMinutes)
        awaitCondition("the Background tasks state") { tasksState != null }
        val controller = assertNotNull(tasksState).controller
        assertEquals(listOf(GitSyncTask.KIND), controller.kinds(), "Settings lists exactly the registered kinds")
        assertEquals("Git sync", controller.titleOf(GitSyncTask.KIND))
    }

    @Test
    fun desktopRoot_should_MakeSchedulerRunLogVisibleThroughTheUiSettingsInstance_When_OneInstanceIsShared() {
        val prefsDir = createTempDirectory("desktop_task_root_prefs_").toFile()
        System.setProperty("stelekit.devDataDir", prefsDir.absolutePath)
        try {
            val shared = PlatformSettings()
            val separate = PlatformSettings() // what Main.kt used to build for the scheduler
            val registry = createDesktopTaskRegistry()
            val scheduler = createDesktopTaskScheduler(registry, shared)
            val key = TaskKey(GitSyncTask.KIND, TaskScope("g1"))
            try {
                runBlocking {
                    scheduler.runOnce(key, TaskConstraints())
                    val deadline = System.nanoTime() + 10_000_000_000L
                    while (SettingsTaskRunLog(shared).recent(key).isEmpty() && System.nanoTime() < deadline) kotlinx.coroutines.delay(20)
                }
            } finally {
                scheduler.stop()
            }
            assertTrue(runBlocking { SettingsTaskRunLog(shared).recent(key) }.isNotEmpty(), "the instance the UI reads sees the scheduler's run record")
            assertTrue(
                runBlocking { SettingsTaskRunLog(separate).recent(key) }.isEmpty(),
                "a second PlatformSettings holds its own stale copy, which is why the root must share one",
            )
        } finally {
            System.clearProperty("stelekit.devDataDir")
            prefsDir.deleteRecursively()
        }
    }

    @Test
    fun settingsEdit_should_ChangeRunningLoopScheduleAndEnabledState_When_PanelControllerSaves() {
        open(pollMinutes = 30)
        awaitCondition("the loop to start") { stack?.gitSyncService?.scheduledSyncStatus?.value?.running == true }
        awaitCondition("the Background tasks state") { tasksState != null }
        assertTrue(nextCheckInMinutes()!! in 29L..31L, "the loop starts on the migrated 30 minutes")
        val controller = tasksState!!.controller
        val key = TaskKey(GitSyncTask.KIND, TaskScope(graphId))

        assertEquals(SaveResult.Saved, runBlocking { controller.edit(key, PanelEdit.Interval(15)) })
        awaitCondition("the running loop to take the 15 minute interval") { (nextCheckInMinutes() ?: -1L) in 14L..16L }

        assertEquals(SaveResult.Saved, runBlocking { controller.edit(key, PanelEdit.Enabled(false)) })
        awaitCondition("the running loop to stop") { stack?.gitSyncService?.scheduledSyncStatus?.value?.running == false }
    }
}
