// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only

package dev.stapler.stelekit.wiring

import dev.stapler.stelekit.git.GitAuth
import dev.stapler.stelekit.git.GitSyncBusyCounter
import dev.stapler.stelekit.git.JvmGitRepository
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.testsupport.BareOriginFixtures
import dev.stapler.stelekit.model.RepoRoot
import dev.stapler.stelekit.platform.SCHEDULED_SYNC_ENABLED_KEY
import dev.stapler.stelekit.ui.GitSyncServiceSeams
import dev.stapler.stelekit.ui.GraphContentGitSyncStack
import dev.stapler.stelekit.ui.rememberGraphContentGitSyncStack
import dev.stapler.stelekit.ui.rememberGraphContentGraphIoStack
import dev.stapler.stelekit.ui.rememberGraphContentVaultSetup
import dev.stapler.stelekit.ui.rememberScheduledSyncSettingsState
import dev.stapler.stelekit.ui.components.settings.ScheduledSyncSettingsState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.eclipse.jgit.api.Git
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Metric 4 through the real `rememberGraphContentGitSyncStack` against a real temp origin: with the app
 * open and the first sync confirmed, a scheduled tick pulls two remote commits into the working tree;
 * without that confirmation the same tick only fetches. No network (the origin is a local bare repo).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledSyncOriginE2ETest {

    private val dataDir: File = createTempDirectory("scheduled_origin_").toFile()
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val settings = FakeSettings()
    private val repository = JvmGitRepository()
    private val temps = mutableListOf<File>()
    private var fixture: DesktopWiredAppFixture? = null
    private var headless: HeadlessComposition? = null
    private var stack: GraphContentGitSyncStack? = null
    private var settingsState: ScheduledSyncSettingsState? = null
    private var graphId = ""
    private var reviewTaps = 0

    @AfterTest
    fun tearDown() {
        headless?.close()
        fixture?.let { runBlocking { it.close() } }
        temps.forEach { it.deleteRecursively() }
        dataDir.deleteRecursively()
    }

    private class Remote(val origin: File, val clone: File, val config: GitConfig)

    private fun remoteTwoCommitsAhead(): Remote {
        val origin = BareOriginFixtures.createBareOrigin("scheduled_origin_remote_", "main").also { temps += it }
        val clone = File(createTempDirectory("scheduled_origin_clone_").toFile().also { temps += it }, "repo")
        runBlocking { assertTrue(repository.clone(origin.absolutePath, RepoRoot(clone.absolutePath), GitAuth.None, onProgress = {}).isRight()) }
        Git.open(clone).use { BareOriginFixtures.setIdentity(it) }
        BareOriginFixtures.pushCommit(origin, "main", "journals/2026_10_08.md", "- remote one\n", "remote one")
        BareOriginFixtures.pushCommit(origin, "main", "journals/2026_10_09.md", "- remote two\n", "remote two")
        val config = GitConfig(
            graphId = "g", repoRoot = RepoRoot(clone.absolutePath), wikiSubdir = null, remoteBranch = "main",
            authType = GitAuthType.NONE, pollIntervalMinutes = INTERVAL_MINUTES,
        )
        return Remote(origin, clone, config)
    }

    private fun open(remote: Remote, firstSyncConfirmed: Boolean) {
        settings.putBoolean(SCHEDULED_SYNC_ENABLED_KEY, true)
        val wired = runBlocking {
            DesktopWiredAppFixture.desktop(
                dataDir,
                graphPath = "/test/origin-graph-${System.nanoTime()}",
                ports = FakePlatformPorts(settings = settings, gitRepository = repository),
                seams = GitSyncServiceSeams(periodicSyncContext = dispatcher, isOnline = { true }),
                busyCounter = GitSyncBusyCounter(),
            )
        }
        fixture = wired
        graphId = wired.graphManager.graphRegistry.value.activeGraphId!!.value
        if (firstSyncConfirmed) settings.putString("git_first_sync_confirmed_$graphId", "origin/main")
        val configRepo = wired.graphManager.createGitConfigRepository()!!
        runBlocking { configRepo.saveConfig(remote.config.copy(graphId = graphId)) }
        headless = HeadlessComposition().also { composition ->
            composition.setContent {
                val vault = rememberGraphContentVaultSetup(wired.graphManager, wired.ports.fileSystem, null)
                val io = rememberGraphContentGraphIoStack(
                    wired.deps, vault.effectiveFileSystem, vault.activeGraphPath, vault.activeGraphInfo,
                    dev.stapler.stelekit.logging.Logger("ScheduledSyncOriginE2ETest"),
                )
                stack = rememberGraphContentGitSyncStack(wired.deps, io, vault.vaultCredentialStore)
                settingsState = rememberScheduledSyncSettingsState(stack!!, onReviewFirstSync = { reviewTaps++ }, onSetUpGit = {})
            }
        }
    }

    private fun awaitCondition(what: String, timeoutMs: Long = 60_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for $what")
            headless!!.advance()
            scheduler.runCurrent()
            Thread.yield()
        }
    }

    private fun tickFinished() = stack?.gitSyncService?.scheduledSyncStatus?.value.let { it?.lastAttemptAtMs != null && !it.tickInFlight }

    @Test
    fun scheduledTick_should_PullTwoRemoteCommitsIntoTheWorkingTree_When_FirstSyncConfirmedAndEditorIdle() {
        val remote = remoteTwoCommitsAhead()
        open(remote, firstSyncConfirmed = true)

        awaitCondition("the first tick to finish") { tickFinished() }

        val state = assertIs<SyncState.Success>(stack!!.gitSyncService!!.syncState.value)
        assertEquals(2, state.remoteCommitsMerged)
        assertEquals("- remote one\n", File(remote.clone, "journals/2026_10_08.md").readText())
        assertEquals("- remote two\n", File(remote.clone, "journals/2026_10_09.md").readText())
        assertEquals(BareOriginFixtures.remoteHead(remote.origin, "main"), Git.open(remote.clone).use { it.repository.resolve("HEAD").name })
        awaitCondition("the settings state") { settingsState?.hasRemote == true }
        assertFalse(settingsState!!.firstSyncReviewRequired, "a confirmed graph needs no review")
    }

    @Test
    fun scheduledTick_should_OnlyFetch_When_FirstSyncNotConfirmed() {
        val remote = remoteTwoCommitsAhead()
        open(remote, firstSyncConfirmed = false)

        awaitCondition("the first tick to finish") { tickFinished() }

        assertFalse(File(remote.clone, "journals/2026_10_08.md").exists(), "an unconfirmed graph must not be merged")
        Git.open(remote.clone).use { git ->
            val tracking = git.repository.exactRef("refs/remotes/origin/main")!!.objectId.name
            assertEquals(BareOriginFixtures.remoteHead(remote.origin, "main"), tracking, "the fetch still ran")
            assertTrue(git.repository.resolve("HEAD").name != tracking, "HEAD did not move")
        }
        awaitCondition("the settings state") { settingsState?.hasRemote == true }
        assertTrue(settingsState!!.firstSyncReviewRequired, "the Settings switch asks for the review first")
        settingsState!!.onReviewFirstSync()
        assertEquals(1, reviewTaps)
    }

    private companion object {
        const val INTERVAL_MINUTES = 15
    }
}
