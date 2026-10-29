// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only

package dev.stapler.stelekit.wiring

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.FetchResult
import dev.stapler.stelekit.git.GitSyncBusyCounter
import dev.stapler.stelekit.git.GitStatus
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.logging.LogLevel
import dev.stapler.stelekit.logging.LogManager
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
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Scheduled sync through the real `rememberGraphContentGitSyncStack` over real SQLDelight (IT-16,
 * IT-30, IT-31 and the stack half of the driver behaviors). Virtual time drives the loop; the IO hop
 * of every tick is settled on state while the scheduler is pumped, so there are no real sleeps.
 *
 * Runs on the desktop fixture, not Robolectric: Robolectric's SQLite has no FTS5, so the Android
 * fixture cannot create the schema and yields no service (see `WiredAppFixture.android`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GitSyncStackEntryPointTest {

    private class CountingGitRepository : StubGitRepository() {
        val fetches = AtomicInteger(0)
        @Volatile var failFetchOnce: Boolean = false
        override suspend fun status(config: GitConfig) =
            GitStatus(hasLocalChanges = false, untrackedFiles = emptyList(), modifiedFiles = emptyList()).right()
        override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> {
            fetches.incrementAndGet()
            if (failFetchOnce) {
                failFetchOnce = false
                throw IllegalStateException("fetch exploded for https://user:ghp_leak@host/r.git")
            }
            return FetchResult(hasRemoteChanges = false, remoteCommitCount = 0).right()
        }
    }

    private val minute = 60_000L
    private val dataDir: File = createTempDirectory("git_sync_stack_").toFile()
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val repo = CountingGitRepository()
    private val settings = FakeSettings()
    private val busy = GitSyncBusyCounter()
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

    private fun open(flagOn: Boolean, pollMinutes: Int) {
        settings.putBoolean(SCHEDULED_SYNC_ENABLED_KEY, flagOn)
        val wired = runBlocking {
            DesktopWiredAppFixture.desktop(
                dataDir,
                graphPath = "/test/wired-graph-${System.nanoTime()}",
                ports =FakePlatformPorts(settings = settings, gitRepository = repo),
                seams = GitSyncServiceSeams(periodicSyncContext = dispatcher, isOnline = { true }),
                busyCounter = busy,
            )
        }
        fixture = wired
        graphId = wired.graphManager.graphRegistry.value.activeGraphId!!.value
        saveInterval(pollMinutes)
        headless = HeadlessComposition().also { composition ->
            composition.setContent {
                val vault = rememberGraphContentVaultSetup(wired.graphManager, wired.ports.fileSystem, null)
                val io = rememberGraphContentGraphIoStack(
                    wired.deps, vault.effectiveFileSystem, vault.activeGraphPath, vault.activeGraphInfo,
                    dev.stapler.stelekit.logging.Logger("GitSyncStackEntryPointTest"),
                )
                stack = rememberGraphContentGitSyncStack(wired.deps, io, vault.vaultCredentialStore)
            }
        }
    }

    private fun saveInterval(minutes: Int) {
        val configRepo = fixture!!.graphManager.createGitConfigRepository()!!
        runBlocking { configRepo.saveConfig(sampleConfig.copy(graphId = graphId, pollIntervalMinutes = minutes)) }
    }

    /** Pumps Compose frames and the virtual scheduler until [condition] holds, failing at [timeoutMs]. */
    private fun awaitCondition(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for $what")
            headless!!.advance()
            scheduler.runCurrent()
            Thread.yield()
        }
    }

    /**
     * Runs the scheduler until fetch number [expected] has started and its tick has finished. A tick
     * hops threads more than once (config read, fetch), so the hop count cannot say when it is done;
     * `tickInFlight` going false on the test thread, with the fetch counted, does - and by then the
     * same runCurrent() has already parked the loop in its next delay.
     */
    private fun settleUntilFetches(expected: Int) {
        awaitCondition("tick $expected to finish") {
            repo.fetches.get() >= expected && stack?.gitSyncService?.scheduledSyncStatus?.value?.tickInFlight == false
        }
    }

    /** Runs the immediate first tick (if due) and waits until its fetch has returned and the loop re-parked. */
    private fun settleImmediateTick() {
        awaitCondition("the loop to start") { stack?.gitSyncService?.scheduledSyncStatus?.value?.running == true }
        settleUntilFetches(1)
    }

    private fun advanceOneInterval(minutes: Int) = advanceAndSettle(minutes * minute)

    private fun advanceAndSettle(millis: Long) {
        val expected = repo.fetches.get() + 1
        scheduler.advanceTimeBy(millis)
        settleUntilFetches(expected)
    }

    private fun syncTickLines() = LogManager.logs.value.filter {
        it.tag == "PeriodicSyncDriver" && it.message.startsWith("SyncTick") && it.message.contains("graph=$graphId ")
    }

    @Test
    fun gitSyncStack_should_RecordImmediateFetchThenThreeIntervalFetchesAndSyncTickLines_When_Flag15MinAnd45MinutesPass() {
        open(flagOn = true, pollMinutes = 15)
        settleImmediateTick()
        repeat(3) { advanceOneInterval(15) }
        ObservableEffect.assert("scheduled-sync-tick") {
            assertEquals(4, repo.fetches.get(), "1 immediate tick after load + 3 ticks in 45 minutes")
            assertEquals(4, syncTickLines().size)
            assertTrue(syncTickLines().all { it.message.contains("decision=fetch") })
        }
    }

    @Test
    fun gitSyncStack_should_StopTicking_When_CompositionDisposed() {
        open(flagOn = true, pollMinutes = 15)
        settleImmediateTick()
        val before = repo.fetches.get()
        headless!!.dispose()
        scheduler.advanceTimeBy(15 * minute)
        scheduler.runCurrent()
        assertEquals(before, repo.fetches.get(), "no tick after shutdown() cancelled the loop")
    }

    @Test
    fun gitSyncStack_should_RestartOnceAtNewInterval_When_PollIntervalMinutesEdited() {
        open(flagOn = true, pollMinutes = 15)
        settleImmediateTick()
        saveInterval(5)
        awaitCondition("the restarted loop's next check to move to 5 minutes") {
            val next = stack?.gitSyncService?.scheduledSyncStatus?.value?.nextAtMs
            next != null && next - System.currentTimeMillis() < 6 * minute
        }
        val before = repo.fetches.get()
        scheduler.advanceTimeBy(5 * minute - 1)
        scheduler.runCurrent()
        assertEquals(before, repo.fetches.get(), "the next check is one new interval after the edit, not immediately")
        advanceAndSettle(1)
        assertEquals(before + 1, repo.fetches.get(), "exactly one loop exists: one fetch per 5 minutes")
        advanceOneInterval(5)
        assertEquals(before + 2, repo.fetches.get())
    }

    @Test
    fun gitSyncStack_should_StartNoLoop_When_FlagUnset() {
        open(flagOn = false, pollMinutes = 15)
        awaitCondition("the config to load") { stack?.scheduledSync?.hasRemote == true }
        scheduler.advanceTimeBy(45 * minute)
        scheduler.runCurrent()
        assertEquals(0, repo.fetches.get())
        assertEquals(false, stack?.gitSyncService?.scheduledSyncStatus?.value?.running)
    }

    @Test
    fun gitSyncStack_should_LaunchNothing_When_IntervalZero() {
        open(flagOn = true, pollMinutes = 0)
        awaitCondition("the config to load") { stack?.scheduledSync?.hasRemote == true }
        scheduler.advanceTimeBy(45 * minute)
        scheduler.runCurrent()
        assertEquals(0, repo.fetches.get())
    }

    @Test
    fun gitSyncStack_should_LogRedactedWarnAndRunNextTick_When_FetchThrows() {
        repo.failFetchOnce = true
        open(flagOn = true, pollMinutes = 15)
        settleImmediateTick()
        val warn = syncTickLines().single { it.level == LogLevel.WARN }
        assertTrue(warn.message.contains("IllegalStateException"), warn.message)
        assertTrue(!warn.message.contains("ghp_leak"), "secret must be redacted: ${warn.message}")
        advanceOneInterval(15)
        assertEquals(2, repo.fetches.get())
        assertEquals(0, stack?.gitSyncService?.scheduledSyncStatus?.value?.consecutiveFailures)
    }

    @Test
    fun gitSyncStack_should_SkipTickWithBusyAndMakeNoGitCall_When_ManualSyncRunning() {
        open(flagOn = true, pollMinutes = 15)
        settleImmediateTick()
        busy.begin()
        val before = repo.fetches.get()
        scheduler.advanceTimeBy(15 * minute)
        scheduler.runCurrent()
        assertEquals(before, repo.fetches.get(), "no git call while a manual sync is running")
        assertTrue(syncTickLines().any { it.message.contains("outcome=skip(busy)") })
        busy.end()
    }

    @Test
    fun gitSyncStack_should_ExposeServiceWithScheduledStatus_When_Composed() {
        open(flagOn = false, pollMinutes = 15)
        val service = assertNotNull(stack?.gitSyncService)
        assertEquals(false, service.scheduledSyncStatus.value.paused)
    }
}
