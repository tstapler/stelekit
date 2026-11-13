// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only

package dev.stapler.stelekit.wiring

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.FetchResult
import dev.stapler.stelekit.git.GitStatus
import dev.stapler.stelekit.git.GitSyncTask
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.jobs.schedule.AppliedConfigLedger
import dev.stapler.stelekit.jobs.schedule.FakeBackgroundTaskScheduler
import dev.stapler.stelekit.jobs.schedule.InProcessTaskScheduler
import dev.stapler.stelekit.jobs.schedule.RunSource
import dev.stapler.stelekit.jobs.schedule.SettingsTaskRunLog
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskScope
import dev.stapler.stelekit.platform.SCHEDULED_SYNC_ENABLED_KEY
import dev.stapler.stelekit.ui.GitSyncServiceSeams
import dev.stapler.stelekit.ui.GraphContentGitSyncStack
import dev.stapler.stelekit.ui.rememberGraphContentGitSyncStack
import dev.stapler.stelekit.ui.rememberGraphContentGraphIoStack
import dev.stapler.stelekit.ui.rememberGraphContentVaultSetup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Desktop owns the git loop through `PeriodicSyncDriver` (ADR-001 fallback): opening a graph migrates the
 * legacy `scheduled_sync_enabled` flag into the per-graph store, and the port's `apply` is never called, so
 * there is exactly one tick source.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskSchedulerDesktopEntryPointTest {
    private class CountingGitRepository : StubGitRepository() {
        val fetches = AtomicInteger(0)
        override suspend fun status(config: GitConfig) =
            GitStatus(hasLocalChanges = false, untrackedFiles = emptyList(), modifiedFiles = emptyList()).right()
        override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> {
            fetches.incrementAndGet()
            return FetchResult(hasRemoteChanges = false, remoteCommitCount = 0).right()
        }
    }

    private val minute = 60_000L
    private val dataDir: File = createTempDirectory("task_scheduler_desktop_").toFile()
    private val scheduler = TestCoroutineScheduler()
    private val repo = CountingGitRepository()
    private val settings = FakeSettings()
    private var fixture: DesktopWiredAppFixture? = null
    private var headless: HeadlessComposition? = null
    private var stack: GraphContentGitSyncStack? = null
    private var graphId = ""

    @AfterTest
    fun tearDown() {
        headless?.close()
        fixture?.let { runBlocking { it.close() } }
        dataDir.deleteRecursively()
    }

    private fun open(pollMinutes: Int, taskScheduler: dev.stapler.stelekit.jobs.schedule.BackgroundTaskScheduler? = null) {
        settings.putBoolean(SCHEDULED_SYNC_ENABLED_KEY, true)
        val wired = runBlocking {
            DesktopWiredAppFixture.desktop(
                dataDir,
                graphPath = "/test/wired-graph-${System.nanoTime()}",
                ports = FakePlatformPorts(settings = settings, gitRepository = repo),
                seams = GitSyncServiceSeams(periodicSyncContext = StandardTestDispatcher(scheduler), isOnline = { true }),
                taskScheduler = taskScheduler,
            )
        }
        fixture = wired
        graphId = wired.graphManager.graphRegistry.value.activeGraphId!!.value
        val configRepo = wired.graphManager.createGitConfigRepository()!!
        runBlocking { configRepo.saveConfig(sampleConfig.copy(graphId = graphId, pollIntervalMinutes = pollMinutes)) }
        headless = HeadlessComposition().also { composition ->
            composition.setContent {
                val vault = rememberGraphContentVaultSetup(wired.graphManager, wired.ports.fileSystem, null)
                val io = rememberGraphContentGraphIoStack(
                    wired.deps, vault.effectiveFileSystem, vault.activeGraphPath, vault.activeGraphInfo,
                    dev.stapler.stelekit.logging.Logger("TaskSchedulerDesktopEntryPointTest"),
                )
                stack = rememberGraphContentGitSyncStack(wired.deps, io, vault.vaultCredentialStore)
            }
        }
    }

    private fun awaitCondition(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for $what")
            headless!!.advance()
            scheduler.runCurrent()
            Thread.yield()
        }
    }

    private val entryKey get() = settings.getString(dev.stapler.stelekit.jobs.schedule.TaskSettingsKeys.entry(TaskKey(GitSyncTask.KIND, TaskScope(graphId))), "")

    @Test
    fun desktopOpen_should_MigrateLegacyFlagAndStartLoopAtLegacyInterval_When_NoEntryExists() {
        open(pollMinutes = 30)
        awaitCondition("the loop to start") { stack?.gitSyncService?.scheduledSyncStatus?.value?.running == true }

        val controls = assertNotNull(stack).scheduledSync
        assertTrue(controls.enabled, "a Desktop user with sync on never sees it turn off after upgrade")
        assertEquals(30, controls.configuredIntervalMinutes)
        val next = assertNotNull(stack!!.gitSyncService!!.scheduledSyncStatus.value.nextAtMs)
        assertTrue(next - System.currentTimeMillis() in 29 * minute..31 * minute, "the driver waits the migrated 30 minutes")
        assertTrue(entryKey.isNotEmpty(), "the migrated entry is persisted")
        assertEquals(true, settings.getBoolean(SCHEDULED_SYNC_ENABLED_KEY, false), "legacy flag untouched")
    }

    @Test
    fun desktopRoot_should_NeverCallApplyOrRegisterGitSync_When_GitSyncEnabled() {
        val counting = FakeBackgroundTaskScheduler(
            capabilities = FakeBackgroundTaskScheduler.DEFAULT_CAPABILITIES.copy(minIntervalMinutes = 1, runsWhenClosed = false),
            ledger = AppliedConfigLedger(settings),
        )
        open(pollMinutes = 15, taskScheduler = counting)
        awaitCondition("the loop to start") { stack?.gitSyncService?.scheduledSyncStatus?.value?.running == true }
        awaitCondition("the immediate tick") {
            repo.fetches.get() >= 1 && stack?.gitSyncService?.scheduledSyncStatus?.value?.tickInFlight == false
        }

        assertEquals(1, repo.fetches.get(), "PeriodicSyncDriver is the one tick source")
        assertEquals(emptyList(), counting.calls.filter { it.startsWith("apply:") }, "apply is reserved for schedulers that run when closed")
        assertNull(runBlocking { counting.describe(TaskKey(GitSyncTask.KIND, TaskScope(graphId))) })
    }

    @Test
    fun inProcessScheduler_should_HaveNoGitSyncWork_When_DesktopLoopRuns() {
        open(pollMinutes = 15)
        awaitCondition("the loop to start") { stack?.gitSyncService?.scheduledSyncStatus?.value?.running == true }
        assertNull(runBlocking { fixture!!.backgroundTaskScheduler.describe(TaskKey(GitSyncTask.KIND, TaskScope(graphId))) })
    }

    @Test
    fun desktopRoot_should_RecordForegroundRunInTheSharedRunLog_When_InProcessLoopTicks() {
        open(pollMinutes = 15)
        assertTrue(fixture!!.backgroundTaskScheduler is InProcessTaskScheduler, "Desktop's root names the in-process scheduler")
        awaitCondition("the immediate tick") {
            repo.fetches.get() >= 1 && stack?.gitSyncService?.scheduledSyncStatus?.value?.tickInFlight == false
        }
        val key = TaskKey(GitSyncTask.KIND, TaskScope(graphId))

        ObservableEffect.assert("in-process-task-tick") {
            val records = runBlocking { SettingsTaskRunLog(settings).recent(key) }
            assertEquals(1, records.size, "the one in-app tick is the only run-log row")
            assertEquals(RunSource.Foreground, records.single().source)
            assertEquals(1, repo.fetches.get(), "the recorded run is a real fetch")
        }
    }
}
