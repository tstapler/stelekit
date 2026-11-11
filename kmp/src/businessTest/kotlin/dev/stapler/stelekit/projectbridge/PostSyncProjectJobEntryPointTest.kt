// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.EditLock
import dev.stapler.stelekit.git.FetchResult
import dev.stapler.stelekit.git.GitStatus
import dev.stapler.stelekit.git.GitSyncBusyCounter
import dev.stapler.stelekit.git.GitSyncService
import dev.stapler.stelekit.git.lfs.MemoryFileSystem
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.jobs.InProcessJobs
import dev.stapler.stelekit.jobs.JobKind
import dev.stapler.stelekit.jobs.JobState
import dev.stapler.stelekit.jobs.JobStatus
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.ui.StelekitViewModel
import dev.stapler.stelekit.ui.StelekitViewModelDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Drives the real [StelekitViewModel] with a real [GitSyncService] through syncs that end in `Success` and in
 * `LfsActionRequired`, and observes the post-sync job through its jobs-list entries. No wiring-catalog line
 * (R-NOWIRE): this test is the entry-point proof for the spine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PostSyncProjectJobEntryPointTest {
    private val graphId = sampleConfig.graphId
    private val photo = "assets/photo.png"
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(3_000)
    private val services = mutableListOf<GitSyncService>()
    private var viewModel: StelekitViewModel? = null

    // sync() records lastSyncAt (the trigger) before it publishes its final state and only then ends the busy
    // bracket. Production's barrier sees the counter; here it blocks the test thread (not the sync's IO thread)
    // until idle, because virtual-time polling would burn every attempt while the real sync is still running.
    private val busyCounter = GitSyncBusyCounter()

    @AfterTest
    fun cleanup() {
        viewModel?.close()
        services.forEach { it.shutdown() }
    }

    private class CleanRepo(private val status: GitStatus) : StubGitRepository() {
        override suspend fun status(config: GitConfig) = status.right()
        override suspend fun stageSubdir(config: GitConfig, excludePaths: Set<String>): Either<DomainError.GitError, Unit> = Unit.right()
        override suspend fun hasStagedChanges(config: GitConfig) = true
        override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> = "abc123".right()
        override suspend fun fetch(config: GitConfig) = FetchResult(hasRemoteChanges = false, remoteCommitCount = 0).right()
        override suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit> = Unit.right()
    }

    private fun gitService(modified: String, files: Map<String, ByteArray>, settings: BridgeMapSettings): GitSyncService {
        val fs = MemoryFileSystem(files.mapKeys { "/repo/${it.key}" }.toMutableMap())
        fs.files["/repo/.gitattributes"] = "assets/** filter=lfs\n".encodeToByteArray()
        val stub = StubFileSystem()
        return GitSyncService(
            gitRepository = CleanRepo(GitStatus(true, emptyList(), listOf(modified))),
            graphLoader = GraphLoader(stub, InMemoryPageRepository(), InMemoryBlockRepository()),
            graphWriter = GraphWriter(fileSystem = stub),
            editLock = EditLock(),
            configRepository = StubConfigRepository(sampleConfig.right()),
            networkMonitor = NetworkMonitor(),
            fileSystem = fs,
            graphId = graphId,
            settings = settings,
            gitSyncBusyCounter = busyCounter,
            isOnline = { true },
        ).also { services += it }
    }

    private class Harness(val viewModel: StelekitViewModel, val jobs: InProcessJobs, val router: () -> Any?) {
        suspend fun entries(): List<JobStatus> = jobs.jobs.first().filter { it.kind == JobKind.ProjectDigest }
    }

    private fun TestScope.harness(service: GitSyncService, settings: BridgeMapSettings, withRouter: Boolean): Harness {
        val pages = InMemoryPageRepository()
        val blocks = InMemoryBlockRepository()
        val stub = StubFileSystem()
        val jobs = InProcessJobs()
        val router = if (withRouter) newBridgeTestRouter() else null
        val bridge = bridgeDeps(settings, jobs, router = { router }, isGitSyncBusy = { runBlocking { busyCounter.awaitIdle() }; false }, readyGraphIds = MutableStateFlow(graphId))
        val vm = StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = stub,
                pageRepository = pages,
                blockRepository = blocks,
                searchRepository = InMemorySearchRepository(),
                graphLoader = GraphLoader(stub, pages, blocks),
                graphWriter = GraphWriter(stub),
                platformSettings = settings,
                scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
                activeGitSyncService = MutableStateFlow(service),
                activeGraphIdProvider = { graphId },
                jobTracker = jobs,
                projectBridge = bridge,
            ),
        )
        viewModel = vm
        return Harness(vm, jobs) { router }
    }

    @Test
    fun runsAfterASyncThatEndsInSuccess() = runTest {
        val settings = BridgeMapSettings().also { enableBridge(it, graphId) }
        val service = gitService("pages/a.md", mapOf("pages/a.md" to "# a".encodeToByteArray()), settings)
        val h = harness(service, settings, withRouter = true)
        advanceUntilIdle()
        val atOpen = h.entries().size

        service.sync(graphId)
        advanceUntilIdle()

        assertIs<SyncState.Success>(service.syncState.value)
        val afterSync = h.entries()
        assertEquals(atOpen + 1, afterSync.size, "one more run for the new lastSyncAt")
        assertEquals(JobState.Succeeded, afterSync.first().state)
        assertEquals("Project digest", afterSync.first().title)
    }

    @Test
    fun runsAfterASyncThatEndsInLfsActionRequired_AndNotesIt() = runTest {
        val settings = BridgeMapSettings().also { enableBridge(it, graphId) }
        val service = gitService(photo, mapOf(photo to png), settings)
        val h = harness(service, settings, withRouter = true)
        advanceUntilIdle()
        val atOpen = h.entries().size

        service.sync(graphId)
        advanceUntilIdle()

        assertIs<SyncState.LfsActionRequired>(service.syncState.value)
        val afterSync = h.entries()
        assertEquals(atOpen + 1, afterSync.size, "Success was replaced by LfsActionRequired, yet the job still ran")
        assertEquals("Project digest (sync left LFS files held)", afterSync.first().title)
    }

    @Test
    fun runsNothing_When_TheFlagIsOff() = runTest {
        val settings = BridgeMapSettings()
        ProjectBridgeSettings(settings).setDigestEnabled(graphId, true)
        val service = gitService("pages/a.md", mapOf("pages/a.md" to "# a".encodeToByteArray()), settings)
        val h = harness(service, settings, withRouter = true)

        service.sync(graphId)
        advanceUntilIdle()

        assertEquals(emptyList(), h.entries())
    }

    @Test
    fun recordsDigestUnavailable_When_NoRouterIsPublished() = runTest {
        val settings = BridgeMapSettings().also { enableBridge(it, graphId) }
        val service = gitService("pages/a.md", mapOf("pages/a.md" to "# a".encodeToByteArray()), settings)
        val h = harness(service, settings, withRouter = false)

        service.sync(graphId)
        advanceUntilIdle()

        val failed = h.entries().map { it.state }.filterIsInstance<JobState.Failed>()
        assertEquals(true, failed.isNotEmpty())
        assertEquals("digest unavailable: no writer on this platform", failed.first().reason)
    }
}
