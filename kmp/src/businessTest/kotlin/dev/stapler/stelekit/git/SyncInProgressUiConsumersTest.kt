// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.error.SyncOrigin
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.jobs.JobHandle
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.ui.failUnlessCoalesced
import dev.stapler.stelekit.wiring.WiringInputs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A coalesced trigger ([DomainError.GitError.SyncInProgress]) is a non-failure in the UI-side consumers: no red job, no error state. */
class SyncInProgressUiConsumersTest {

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store[key] ?: defaultValue
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private class RecordingHandle : JobHandle {
        val failures = mutableListOf<String?>()
        override fun progress(percent: Int?) = Unit
        override fun succeed() = Unit
        override fun fail(reason: String?) { failures += reason }
        override fun cancelled() = Unit
    }

    private val busy = DomainError.GitError.SyncInProgress(SyncOrigin.Scheduled)

    @Test
    fun jobHandle_should_NotFail_When_TriggerCoalesced_AndFail_ForRealErrors() {
        val handle = RecordingHandle()

        handle.failUnlessCoalesced(busy)
        assertTrue(handle.failures.isEmpty(), "a coalesced press must not mark the job failed")

        handle.failUnlessCoalesced(DomainError.GitError.FetchFailed("boom"))
        assertEquals(listOf<String?>("boom"), handle.failures)
    }

    @Test
    fun coordinatorAndSafetyActions_should_RouteEveryJobFailureThroughTheCoalescedCheck() {
        val sources = WiringInputs.productionSources
        for (file in listOf("GitSyncCoordinator.kt", "SyncSafetyActions.kt")) {
            val text = checkNotNull(sources["commonMain/dev/stapler/stelekit/ui/$file"]) { "missing $file" }
            assertFalse(text.contains("handle.fail(it.message)"), "$file fails the job for every Left, including SyncInProgress")
            assertTrue(text.contains("failUnlessCoalesced"), "$file must use failUnlessCoalesced")
        }
    }

    @Test
    fun changeBackProposal_should_ReturnBusy_NotSaveFailed_When_ProbeFetchLosesTheGate() = runTest {
        val firstSync = FirstSyncConfirmation(MapSettings())
        firstSync.markReviewPending(sampleConfig.graphId, previousBranch = "old-main")
        val repo = object : StubGitRepository() {
            override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> = busy.left()
        }
        val service = BranchRepairService(StubConfigRepository(sampleConfig.right()), firstSync)

        val result = service.changeBackProposal(repo, sampleConfig.graphId)

        assertEquals(BranchRepairResult.Busy.left(), result)
        assertIs<Either.Left<BranchRepairResult>>(result)
    }

    @Test
    fun changeBackProposal_should_StillReportSaveFailed_When_ProbeFetchFailsForARealReason() = runTest {
        val firstSync = FirstSyncConfirmation(MapSettings())
        firstSync.markReviewPending(sampleConfig.graphId, previousBranch = "old-main")
        val repo = object : StubGitRepository() {
            override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> =
                DomainError.GitError.FetchFailed("boom").left()
        }
        val service = BranchRepairService(StubConfigRepository(sampleConfig.right()), firstSync)

        assertEquals(BranchRepairResult.SaveFailed("boom").left(), service.changeBackProposal(repo, sampleConfig.graphId))
    }
}
