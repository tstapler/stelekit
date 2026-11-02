// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.DeletionSummary
import dev.stapler.stelekit.git.EditLock
import dev.stapler.stelekit.git.FetchResult
import dev.stapler.stelekit.git.GitStatus
import dev.stapler.stelekit.git.GitSyncService
import dev.stapler.stelekit.git.GuardScope
import dev.stapler.stelekit.git.RepositoryHealth
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Routing of the sync-problem detail sheets, the interrupted-merge abort, and the foreground mass-change confirm. */
class GitSyncCoordinatorTest {
    private val graphId = "test-graph"

    private class Spy : StubGitRepository() {
        var health = RepositoryHealth.Clean
        var deletions = DeletionSummary.None
        val calls = mutableListOf<String>()

        override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> =
            GitStatus(false, emptyList(), emptyList()).right()

        override suspend fun repositoryHealth(config: GitConfig): RepositoryHealth = health
        override suspend fun deletionSummary(config: GitConfig, scope: GuardScope) = deletions

        override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> {
            calls += "fetch"
            return FetchResult(false, 0).right()
        }

        override suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit> {
            calls += "push"
            return Unit.right()
        }

        override suspend fun abortMerge(config: GitConfig): Either<DomainError.GitError, Unit> {
            calls += "abort"
            health = RepositoryHealth.Clean
            return Unit.right()
        }
    }

    private class Harness(val spy: Spy) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val uiState = MutableStateFlow(AppState())
        val service: GitSyncService = StubFileSystem().let { fs ->
            GitSyncService(
                gitRepository = spy,
                graphLoader = GraphLoader(fs, InMemoryPageRepository(), InMemoryBlockRepository()),
                graphWriter = GraphWriter(fileSystem = fs),
                editLock = EditLock(),
                configRepository = StubConfigRepository(sampleConfig.right()),
                networkMonitor = NetworkMonitor(),
                fileSystem = fs,
                graphId = "test-graph",
                isOnline = { true },
            )
        }
        val coordinator = GitSyncCoordinator(
            activeGitSyncService = MutableStateFlow(service),
            localChangesCountFlow = null,
            activeGraphIdProvider = { "test-graph" },
            onDismissGitDetection = null,
            onDismissContentMismatchBanner = null,
            onDismissBrowserOnlySyncBanner = null,
            scope = scope,
            uiState = uiState,
        )

        fun close() {
            service.shutdown()
            scope.cancel()
        }
    }

    private suspend fun eventually(what: String, condition: () -> Boolean) {
        withTimeout(AWAIT_MILLIS) { while (!condition()) yield() }
        assertTrue(condition(), what)
    }

    private fun manyDeletions() = DeletionSummary(List(30) { "pages/p$it.md" }, previousTreeSize = 100)

    @Test
    fun `openSyncDetails_should_OpenTheRepairDialog_When_StateIsRepairNeeded`() = runBlocking {
        val h = Harness(Spy().apply { health = RepositoryHealth(false, "MERGING") })
        h.service.sync(graphId)
        assertTrue((h.service.syncState.value as SyncState.Error).error is DomainError.GitError.RepairNeeded)
        eventually("coordinator mirrors the state") { h.coordinator.syncState.value is SyncState.Error }

        h.coordinator.safety.openSyncDetails()

        assertTrue(h.uiState.value.repairNeededVisible)
        assertFalse(h.uiState.value.massChangeConfirmVisible)
        assertFalse(h.uiState.value.conflictResolutionVisible)
        assertEquals(emptyList(), h.spy.calls, "opening the dialog must not touch the repository")
        h.close()
    }

    @Test
    fun `openSyncDetails_should_DoNothing_When_StateHasNoDetailSurface`() = runBlocking {
        val h = Harness(Spy())

        h.coordinator.safety.openSyncDetails()

        assertEquals(AppState(), h.uiState.value)
        h.close()
    }

    @Test
    fun `abortInterruptedMerge_should_AbortAndCloseTheDialog_AndAllowTheNextSync`() = runBlocking {
        val h = Harness(Spy().apply { health = RepositoryHealth(false, "MERGING") })
        h.service.sync(graphId)
        eventually("mirrored") { h.coordinator.syncState.value is SyncState.Error }
        h.coordinator.safety.openSyncDetails()

        h.coordinator.safety.abortInterruptedMerge()

        eventually("abort ran") { "abort" in h.spy.calls }
        eventually("dialog closed") { !h.uiState.value.repairNeededVisible }
        assertEquals(SyncState.Idle, h.service.syncState.value)
        assertTrue(h.service.sync(graphId).isRight(), "the repository is safe again, so sync proceeds")
        h.close()
    }

    @Test
    fun `confirmMassChangeSync_should_LiftTheGuardForOneManualRun_AndCloseTheConfirm`() = runBlocking {
        val h = Harness(Spy().apply { deletions = manyDeletions() })
        val blocked = h.service.sync(graphId)
        assertTrue((blocked as Either.Left).value is DomainError.GitError.MassChangeBlocked)
        assertFalse("push" in h.spy.calls)
        eventually("mirrored") { h.coordinator.syncState.value is SyncState.Error }
        h.coordinator.safety.openSyncDetails()
        assertTrue(h.uiState.value.massChangeConfirmVisible)

        h.coordinator.safety.confirmMassChangeSync()

        assertFalse(h.uiState.value.massChangeConfirmVisible)
        eventually("push ran with the guard lifted") { "push" in h.spy.calls }
        eventually("sync succeeded") { h.service.syncState.value is SyncState.Success }
        // The override is one run only: the next plain sync is blocked again.
        assertTrue((h.service.sync(graphId) as Either.Left).value is DomainError.GitError.MassChangeBlocked)
        h.close()
    }

    @Test
    fun `dismissals_should_CloseTheirDialogs`() = runBlocking {
        val h = Harness(Spy())
        h.uiState.value = AppState(repairNeededVisible = true, massChangeConfirmVisible = true)

        h.coordinator.safety.dismissRepairNeeded()
        h.coordinator.safety.dismissMassChangeConfirm()

        assertEquals(AppState(), h.uiState.value)
        h.close()
    }

    private companion object {
        const val AWAIT_MILLIS = 5_000L
    }
}
