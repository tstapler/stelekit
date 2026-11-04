// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.FetchResult
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.buildGitSyncTestService
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.jobs.InProcessJobs
import dev.stapler.stelekit.jobs.JobKind
import dev.stapler.stelekit.jobs.JobState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitSyncCoordinatorJobsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = InProcessJobs()

    /** A fetch that parks until [release] completes, so the job stays running while the test acts on it. */
    private class ParkedFetchRepository(private val release: CompletableDeferred<Unit>) : StubGitRepository() {
        val entered = CompletableDeferred<Unit>()
        override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> {
            entered.complete(Unit)
            release.await()
            return DomainError.GitError.FetchFailed("released").left()
        }
    }

    private fun coordinator(repository: StubGitRepository): GitSyncCoordinator {
        val service = buildGitSyncTestService(
            gitRepository = repository,
            configRepository = StubConfigRepository(Either.Right(sampleConfig)),
            isOnline = { true },
        )
        return GitSyncCoordinator(
            activeGitSyncService = MutableStateFlow(service),
            localChangesCountFlow = null,
            activeGraphIdProvider = { sampleConfig.graphId },
            onDismissGitDetection = null,
            onDismissContentMismatchBanner = null,
            onDismissBrowserOnlySyncBanner = null,
            scope = scope,
            uiState = MutableStateFlow(AppState()),
            jobTracker = jobs,
        )
    }

    @AfterTest
    fun cleanup() = scope.cancel()

    @Test
    fun triggerFetchOnly_should_ListCancellableJob_When_FetchIsInFlight() = runTest {
        val repository = ParkedFetchRepository(CompletableDeferred())
        coordinator(repository).triggerFetchOnly()
        repository.entered.await()

        val job = jobs.jobs.first().single()

        assertEquals(JobKind.GitSync, job.kind)
        assertTrue(job.cancellable)
        assertIs<JobState.Running>(job.state)
    }

    @Test
    fun cancel_should_StopTheFetchAndMarkJobCancelled_When_UserCancelsFromList() = runTest {
        val repository = ParkedFetchRepository(CompletableDeferred())
        coordinator(repository).triggerFetchOnly()
        repository.entered.await()

        jobs.cancel(jobs.jobs.first().single().id)

        val settled = jobs.jobs.first { list -> list.none { it.state is JobState.Running } }
        assertIs<JobState.Cancelled>(settled.single().state)
    }

    @Test
    fun triggerFetchOnly_should_FailJobWithMessage_When_FetchFails() = runTest {
        val release = CompletableDeferred(Unit)
        val repository = ParkedFetchRepository(release)

        coordinator(repository).triggerFetchOnly()

        val settled = jobs.jobs.first { list -> list.any { it.state is JobState.Failed } }
        assertIs<JobState.Failed>(settled.single().state)
    }

    @Test
    fun triggerSync_should_ListSyncJob_When_SyncStarts() = runTest {
        val repository = ParkedFetchRepository(CompletableDeferred(Unit))

        coordinator(repository).triggerSync()

        val job = jobs.jobs.first { it.isNotEmpty() }.single()
        assertEquals(JobKind.GitSync, job.kind)
        assertEquals("Syncing with remote", job.title)
    }
}
