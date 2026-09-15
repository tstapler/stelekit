// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.EditLock
import dev.stapler.stelekit.git.FetchResult
import dev.stapler.stelekit.git.GitAuth
import dev.stapler.stelekit.git.GitCommit
import dev.stapler.stelekit.git.GitConfigRepository
import dev.stapler.stelekit.git.GitRepository
import dev.stapler.stelekit.git.GitStatus
import dev.stapler.stelekit.git.GitSyncService
import dev.stapler.stelekit.git.MergeResult
import dev.stapler.stelekit.git.MergeSide
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.ui.fixtures.FakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.FakePageRepository
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Unit tests for Epic 4.3 Story 4.3.2: [StelekitViewModel.syncState] combines the raw
 * [GitSyncService] state with [StelekitViewModelDependencies.localChangesCountFlow], upgrading
 * an otherwise-[SyncState.Idle] state to [SyncState.LocalChangesPending] when dirty files exist,
 * while never interrupting an in-progress state.
 *
 * See [StelekitViewModelSyncStateIntegrationTest] (jvmTest) for the JVM/Android
 * zero-regression proof (`localChangesCountFlow == null`).
 */
class StelekitViewModelSyncStateTest {

    /** [GitRepository] stub that throws for any method not overridden by a test. */
    private open class StubGitRepository : GitRepository {
        override suspend fun isGitRepo(path: String): Boolean = error("not implemented in stub")
        override suspend fun init(repoRoot: String): Either<DomainError.GitError, Unit> = error("not implemented in stub")
        override suspend fun clone(url: String, localPath: String, auth: GitAuth, onProgress: (String) -> Unit): Either<DomainError.GitError, Unit> = error("not implemented in stub")
        override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> = error("not implemented in stub")
        override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> = error("not implemented in stub")
        override suspend fun stageSubdir(config: GitConfig): Either<DomainError.GitError, Unit> = error("not implemented in stub")
        override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> = error("not implemented in stub")
        override suspend fun merge(config: GitConfig): Either<DomainError.GitError, MergeResult> = error("not implemented in stub")
        override suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit> = error("not implemented in stub")
        override suspend fun log(config: GitConfig, maxCount: Int): Either<DomainError.GitError, List<GitCommit>> = error("not implemented in stub")
        override suspend fun abortMerge(config: GitConfig): Either<DomainError.GitError, Unit> = error("not implemented in stub")
        override suspend fun checkoutFile(config: GitConfig, filePath: String, side: MergeSide): Either<DomainError.GitError, Unit> = error("not implemented in stub")
        override suspend fun markResolved(config: GitConfig, filePath: String): Either<DomainError.GitError, Unit> = error("not implemented in stub")
        override suspend fun hasDetachedHead(config: GitConfig): Boolean = false
        override suspend fun removeStaleLockFile(config: GitConfig): Either<DomainError.GitError, Unit> = Unit.right()
    }

    private class StubConfigRepository(
        private val configResult: Either<DomainError, GitConfig?>,
    ) : GitConfigRepository {
        override suspend fun getConfig(graphId: String): Either<DomainError, GitConfig?> = configResult
        override suspend fun saveConfig(config: GitConfig): Either<DomainError, Unit> = Unit.right()
        override suspend fun deleteConfig(graphId: String): Either<DomainError, Unit> = Unit.right()
        override fun observeConfig(graphId: String) = kotlinx.coroutines.flow.flowOf(configResult)
    }

    private val sampleConfig = GitConfig(
        graphId = "test-graph",
        repoRoot = "/repo",
        wikiSubdir = "",
        authType = GitAuthType.NONE,
    )

    /**
     * Builds a real [GitSyncService] with a stub [GitRepository]/[GitConfigRepository], mirroring
     * `GitSyncServiceTest.buildService()` (businessTest) — [NetworkMonitor] is an `expect class`
     * with no test seam, but [GitSyncService.commitLocalChanges] (used below) never reads it, so
     * the real instance is safe to construct without any network dependency in this test.
     */
    private fun buildGitSyncService(gitRepository: GitRepository): GitSyncService {
        val fileSystem = FakeFileSystem()
        val graphLoader = GraphLoader(
            fileSystem = fileSystem,
            pageRepository = InMemoryPageRepository(),
            blockRepository = InMemoryBlockRepository(),
        )
        val graphWriter = GraphWriter(fileSystem)
        return GitSyncService(
            gitRepository = gitRepository,
            graphLoader = graphLoader,
            graphWriter = graphWriter,
            editLock = EditLock(),
            configRepository = StubConfigRepository(sampleConfig.right()),
            networkMonitor = NetworkMonitor(),
            fileSystem = fileSystem,
        )
    }

    private fun makeViewModel(
        activeGitSyncService: StateFlow<GitSyncService?> = MutableStateFlow(null),
        localChangesCountFlow: StateFlow<Int>? = null,
    ): StelekitViewModel {
        val pageRepo = FakePageRepository()
        val blockRepo = FakeBlockRepository()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val fileSystem = FakeFileSystem()
        return StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = fileSystem,
                pageRepository = pageRepo,
                blockRepository = blockRepo,
                searchRepository = InMemorySearchRepository(),
                graphLoader = GraphLoader(fileSystem, pageRepo, blockRepo),
                graphWriter = GraphWriter(fileSystem),
                platformSettings = InMemorySettings(),
                scope = scope,
                activeGitSyncService = activeGitSyncService,
                localChangesCountFlow = localChangesCountFlow,
            )
        )
    }

    // ── TC-4.3.2-A: Idle + nonzero count → LocalChangesPending ─────────────────

    @Test
    fun `TC-4_3_2-A syncState combine emits LocalChangesPending when raw state is Idle and count greater than zero`() = runBlocking {
        val countFlow = MutableStateFlow(0)
        val vm = makeViewModel(
            activeGitSyncService = MutableStateFlow(null), // raw state is always SyncState.Idle
            localChangesCountFlow = countFlow,
        )

        assertEquals(SyncState.Idle, vm.syncState.value, "precondition: count is 0, so no upgrade yet")

        countFlow.value = 3

        // stateIn is Eagerly-shared, so the combine recomputes as soon as the upstream emits;
        // poll briefly rather than assuming synchronous propagation across dispatchers.
        val observed = withPolledValue(vm) { it is SyncState.LocalChangesPending }
        assertEquals(SyncState.LocalChangesPending(3), observed)
    }

    @Test
    fun `TC-4_3_2-A syncState stays Idle when count is zero`() = runBlocking {
        val countFlow = MutableStateFlow(0)
        val vm = makeViewModel(
            activeGitSyncService = MutableStateFlow(null),
            localChangesCountFlow = countFlow,
        )

        assertEquals(SyncState.Idle, vm.syncState.value)
    }

    // ── TC-4.3.2-B: in-progress state is never overridden ──────────────────────

    @Test
    fun `TC-4_3_2-B syncState combine never overrides an in-progress state with LocalChangesPending`() = runBlocking {
        // GitRepository whose status() reports local changes but stageSubdir() fails —
        // GitSyncService.commitLocalChanges() sets syncState = Committing immediately before
        // calling stageSubdir(), then returns Left without resetting it back to Idle. This is
        // the same "known non-Idle value" technique GitSyncServiceTest (businessTest) uses, and
        // requires no network access (commitLocalChanges never reads NetworkMonitor.isOnline).
        val gitRepository = object : StubGitRepository() {
            override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> =
                GitStatus(hasLocalChanges = true, untrackedFiles = emptyList(), modifiedFiles = listOf("pages/foo.md")).right()

            override suspend fun stageSubdir(config: GitConfig): Either<DomainError.GitError, Unit> =
                DomainError.GitError.CommitFailed("stage failed").left()
        }
        val service = buildGitSyncService(gitRepository)
        service.commitLocalChanges("test-graph")
        assertEquals(SyncState.Committing, service.syncState.value, "precondition: raw state must be non-Idle")

        val countFlow = MutableStateFlow(0)
        val vm = makeViewModel(
            activeGitSyncService = MutableStateFlow(service),
            localChangesCountFlow = countFlow,
        )

        // Give the combine a moment to observe the raw Committing state.
        val precondition = withPolledValue(vm) { it is SyncState.Committing }
        assertIs<SyncState.Committing>(precondition)

        // Now report dirty files — the combine must NOT upgrade an in-progress state.
        countFlow.value = 5

        // No transition happens, so just assert the value stays Committing after a short delay.
        kotlinx.coroutines.delay(200)
        assertEquals(
            SyncState.Committing,
            vm.syncState.value,
            "LocalChangesPending must only ever override Idle, never an in-progress state",
        )
    }

    /** Polls [vm]'s syncState until [predicate] matches, or times out. */
    private suspend fun withPolledValue(
        vm: StelekitViewModel,
        timeoutMs: Long = 2_000,
        predicate: (SyncState) -> Boolean,
    ): SyncState = kotlinx.coroutines.withTimeout(timeoutMs) {
        var current = vm.syncState.value
        while (!predicate(current)) {
            kotlinx.coroutines.delay(10)
            current = vm.syncState.value
        }
        current
    }
}
