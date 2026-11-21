// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.error.SyncOrigin
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.jobs.schedule.TaskRunResult
import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A lost gate race ([DomainError.GitError.SyncInProgress]) is a busy skip on every scheduled path, never a failed tick or backoff. */
class SyncInProgressConsumersTest {

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store[key] ?: defaultValue
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private val busy = DomainError.GitError.SyncInProgress(SyncOrigin.UiPress)
    private val success = SyncState.Success(localCommitsMade = 0, remoteCommitsMerged = 0, lastSyncAt = 0L)
    private var fullSyncResult: Either<DomainError.GitError, SyncState.Success> = busy.left()
    private var fetchResult: Either<DomainError.GitError, FetchResult> = busy.left()
    private var fullSyncCalls = 0
    private var now = 1_000L

    private fun runner(confirmed: Boolean, config: Boolean = true): ScheduledSyncRunner {
        val settings = MapSettings()
        val confirmation = FirstSyncConfirmation(settings)
        if (confirmed) confirmation.confirm(sampleConfig.graphId, sampleConfig.remoteName, sampleConfig.remoteBranch)
        return ScheduledSyncRunner(
            gitRepository = StubGitRepository(),
            configRepository = StubConfigRepository((if (config) sampleConfig else null).right()),
            editLock = EditLock(),
            busyCounter = GitSyncBusyCounter(),
            firstSyncConfirmation = confirmation,
            credentialAccessProvider = null,
            isOnline = { true },
            clock = { now },
            isAppOwnedPath = { true },
            syncState = MutableStateFlow(SyncState.Idle),
            fullSync = { fullSyncCalls++; fullSyncResult },
            fetch = { fetchResult },
        )
    }

    @Test
    fun runFull_should_SkipBusy_AndNeverStartBackoff_When_SyncInProgress() = runTest {
        val runner = runner(confirmed = true)

        repeat(3) { assertEquals(TickOutcome.Skip(SkipReason.Busy), runner.run(sampleConfig.graphId)) }

        fullSyncResult = success.right()
        assertEquals(TickOutcome.Success, runner.run(sampleConfig.graphId), "a lost race must not leave a backoff behind")
        assertEquals(4, fullSyncCalls)
    }

    @Test
    fun fetchPath_should_SkipBusy_When_SyncInProgress_ThroughToTickOutcome() = runTest {
        // No stored config: the tick takes the bare `fetch(graphId).toTickOutcome()` path.
        assertEquals(TickOutcome.Skip(SkipReason.Busy), runner(confirmed = true, config = false).run(sampleConfig.graphId))
        assertEquals(TickOutcome.Skip(SkipReason.Busy), fetchResult.toTickOutcome())
    }

    @Test
    fun fetchAnywaySkipPath_should_SkipBusy_When_SyncInProgress() = runTest {
        // Unconfirmed first sync is a policy skip that still fetches; that fetch can lose the gate too.
        assertEquals(TickOutcome.Skip(SkipReason.Busy), runner(confirmed = false).run(sampleConfig.graphId))
        assertEquals(0, fullSyncCalls)
    }

    @Test
    fun otherFetchFailures_should_StillBeFailures() {
        val failed: Either<DomainError.GitError, FetchResult> = DomainError.GitError.FetchFailed("boom").left()
        assertIs<TickOutcome.Failure>(failed.toTickOutcome())
    }

    @Test
    fun taskMapping_should_MapSyncInProgressFailureToBusySkip() {
        assertEquals(TaskRunResult.Skipped("busy"), TickOutcome.Failure(busy.message, busy).toTaskRunResult())
        assertIs<TaskRunResult.Failed>(TickOutcome.Failure("boom", DomainError.GitError.FetchFailed("boom")).toTaskRunResult())
    }
}
