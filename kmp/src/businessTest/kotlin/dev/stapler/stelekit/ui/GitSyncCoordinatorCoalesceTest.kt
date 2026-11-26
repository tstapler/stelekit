// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.FetchResult
import dev.stapler.stelekit.git.GitSyncService
import dev.stapler.stelekit.git.buildGitSyncTestService
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.jobs.InProcessJobs
import dev.stapler.stelekit.jobs.JobState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Story 1.6 (Task 1.6a): a press that loses the single-flight race announces itself and changes nothing else. */
class GitSyncCoordinatorCoalesceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = InProcessJobs()
    private val release = CompletableDeferred<Unit>()
    private val fetches = AtomicInteger()
    private val entered = CompletableDeferred<Unit>()

    private val repository = object : StubGitRepository() {
        override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> {
            fetches.incrementAndGet()
            entered.complete(Unit)
            release.await()
            return DomainError.GitError.FetchFailed("released").left()
        }
    }
    private val service: GitSyncService = buildGitSyncTestService(
        gitRepository = repository,
        configRepository = StubConfigRepository(Either.Right(sampleConfig)),
        isOnline = { true },
    )
    private val uiState = MutableStateFlow(AppState())
    private val coordinator = GitSyncCoordinator(
        activeGitSyncService = MutableStateFlow(service),
        localChangesCountFlow = null,
        activeGraphIdProvider = { sampleConfig.graphId },
        onDismissGitDetection = null,
        onDismissContentMismatchBanner = null,
        onDismissBrowserOnlySyncBanner = null,
        scope = scope,
        uiState = uiState,
        jobTracker = jobs,
    )

    @AfterTest
    fun cleanup() {
        release.complete(Unit)
        service.shutdown()
        scope.cancel()
    }

    private suspend fun holdTheGate() {
        coordinator.triggerFetchOnly()
        entered.await()
        withTimeout(5_000) { while (coordinator.syncState.value != SyncState.Fetching) yield() } // real-clock hang guard: awaits real threads
    }

    private fun collectEvents(): MutableList<SyncUiEvent> {
        val seen = Collections.synchronizedList(mutableListOf<SyncUiEvent>())
        scope.launch(start = CoroutineStart.UNDISPATCHED) { coordinator.syncEvents.collect { seen += it } }
        return seen
    }

    private suspend fun awaitEvent(seen: List<SyncUiEvent>) {
        withTimeout(5_000) { while (seen.isEmpty()) yield() } // real-clock hang guard: awaits real threads
    }

    // runBlocking: the coordinator runs on a real Dispatchers.Default scope the test has to wait on
    @Test
    fun triggerSync_should_EmitOneAlreadySyncing_AndChangeNoState_When_ARunHoldsTheGate() = runBlocking {
        val seen = collectEvents()
        holdTheGate()
        val before = coordinator.syncState.value

        coordinator.triggerSync()
        awaitEvent(seen)

        assertEquals(listOf<SyncUiEvent>(SyncUiEvent.AlreadySyncing), seen.toList())
        assertEquals(before, coordinator.syncState.value)
        assertEquals(1, fetches.get(), "the coalesced press must not issue a second fetch")
        assertFalse(jobs.jobs.first().any { it.state is JobState.Failed }, "a coalesced press is not a failed job")
    }

    // runBlocking: the coordinator runs on a real Dispatchers.Default scope the test has to wait on
    @Test
    fun triggerFetchOnly_should_EmitAlreadySyncing_When_ARunHoldsTheGate() = runBlocking {
        val seen = collectEvents()
        holdTheGate()

        coordinator.triggerFetchOnly()
        awaitEvent(seen)

        assertEquals(listOf<SyncUiEvent>(SyncUiEvent.AlreadySyncing), seen.toList())
        assertEquals(1, fetches.get())
    }

    // runBlocking: the coordinator runs on a real Dispatchers.Default scope the test has to wait on
    @Test
    fun confirmMassChangeSync_should_BeRefusedAndKeepTheDialog_When_ARunIsInFlight() = runBlocking {
        val seen = collectEvents()
        holdTheGate()
        uiState.value = uiState.value.copy(massChangeConfirmVisible = true)

        coordinator.safety.confirmMassChangeSync()
        awaitEvent(seen)

        val event = assertIs<SyncUiEvent.MassChangeConfirmRefused>(seen.single())
        assertEquals("Wait for the running sync to finish, then confirm again", event.announcement)
        assertTrue(uiState.value.massChangeConfirmVisible, "the dialog stays open so the user can confirm again")
        assertEquals(1, fetches.get(), "the lifted press must never be carried through a coalesce or queued")
    }

    // runBlocking: the coordinator runs on a real Dispatchers.Default scope the test has to wait on
    @Test
    fun confirmMassChangeSync_should_CloseTheDialogWithoutRefusal_When_NothingIsInFlight() = runBlocking {
        release.complete(Unit)
        val seen = collectEvents()
        uiState.value = uiState.value.copy(massChangeConfirmVisible = true)

        coordinator.safety.confirmMassChangeSync()

        assertFalse(uiState.value.massChangeConfirmVisible)
        yield()
        assertTrue(seen.isEmpty(), "no refusal when the gate is free")
    }
}
