// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Task 3.1.5k — `MoveInProgressFlag` is cleared on quiesce-timeout, copy-failure,
 * verification-failure, AND cancellation exit paths, not just success. Each scenario here uses a
 * distinct `graphId` (via `System.nanoTime()`) so `MoveInProgressFlag`'s process-wide singleton
 * state can never leak between test cases.
 */
class GraphRelocationCoordinatorFlagClearingTest : RelocationCoordinatorTestSupport() {

    private fun operationFor(graphId: dev.stapler.stelekit.model.GraphId) = StorageMoveOperation.Relocate(
        graphId = graphId.value,
        source = StorageLocation.DirectAccessFolder(graphId.value, "source"),
        destination = StorageLocation.DirectAccessFolder(graphId.value, "dest"),
        deleteSourceAfterVerify = false,
    )

    @Test
    fun `MoveInProgressFlag should BeCleared When QuiesceTimesOut`() = runTest {
        val graphManager = newGraphManager()
        val graphId = graphManager.addGraph("/test/flag-timeout-${System.nanoTime()}")
        val coordinator = GraphRelocationCoordinator(
            graphManager,
            FakeRelocationFileSystem(),
            FakeGraphMoveQuiesceStrategy(neverCompletes = true),
        )

        coordinator.relocate(operationFor(graphId)).toList()

        assertFalse(MoveInProgressFlag.isMoveInProgress(graphId.value))
    }

    @Test
    fun `MoveInProgressFlag should BeCleared When CopyFails`() = runBlocking {
        val graphManager = newGraphManager()
        val graphId = graphManager.addGraph("/test/flag-copyfail-${System.nanoTime()}")
        val failingStep = CopyAndVerifyStep { _, _, _ ->
            DomainError.StorageError.DestinationNotWritable("dest/page1.md").left()
        }
        val coordinator = GraphRelocationCoordinator(
            graphManager,
            FakeRelocationFileSystem(),
            FakeGraphMoveQuiesceStrategy(),
            failingStep,
        )

        coordinator.relocate(operationFor(graphId)).toList()

        assertFalse(MoveInProgressFlag.isMoveInProgress(graphId.value))
        graphManager.shutdown()
    }

    @Test
    fun `MoveInProgressFlag should BeCleared When VerificationFails`() = runBlocking {
        val graphManager = newGraphManager()
        val graphId = graphManager.addGraph("/test/flag-verifyfail-${System.nanoTime()}")
        val failingStep = CopyAndVerifyStep { _, _, _ ->
            DomainError.StorageError.VerificationFailed("page1.md", "hash mismatch").left()
        }
        val coordinator = GraphRelocationCoordinator(
            graphManager,
            FakeRelocationFileSystem(),
            FakeGraphMoveQuiesceStrategy(),
            failingStep,
        )

        coordinator.relocate(operationFor(graphId)).toList()

        assertFalse(MoveInProgressFlag.isMoveInProgress(graphId.value))
        graphManager.shutdown()
    }

    @Test
    fun `MoveInProgressFlag should BeCleared When CancelledMidCopy`() = runBlocking {
        val graphManager = newGraphManager()
        val graphId = graphManager.addGraph("/test/flag-cancel-${System.nanoTime()}")
        val hangingStep = CopyAndVerifyStep { _, _, _ -> awaitCancellation() }
        val coordinator = GraphRelocationCoordinator(
            graphManager,
            FakeRelocationFileSystem(),
            FakeGraphMoveQuiesceStrategy(),
            hangingStep,
        )

        assertTrue(MoveInProgressFlag.isMoveInProgress(graphId.value).not())
        val collectJob = launch { coordinator.relocate(operationFor(graphId)).collect { } }
        withTimeout(5_000) {
            while (!MoveInProgressFlag.isMoveInProgress(graphId.value)) yield()
        }

        collectJob.cancelAndJoin()

        assertFalse(MoveInProgressFlag.isMoveInProgress(graphId.value))
        graphManager.shutdown()
    }

    /**
     * BLOCKER 2 regression test (PR #327 review): a second `relocate()` call for a graph that's
     * already mid-move must be rejected immediately with `SourceInFlight` — never proceed to
     * quiesce, never re-set `MoveInProgressFlag`. Without this guard, two concurrent relocates for
     * the same graph would share one `RelocationStagingDirectory.stagingPath()` and could race
     * each other's driver close/reopen lifecycle.
     */
    @Test
    fun `relocate should RejectImmediately When AnotherMoveAlreadyInProgressForSameGraph`() = runBlocking {
        val graphManager = newGraphManager()
        val graphId = graphManager.addGraph("/test/flag-reentrant-${System.nanoTime()}")
        val hangingStep = CopyAndVerifyStep { _, _, _ -> awaitCancellation() }
        val quiesce = FakeGraphMoveQuiesceStrategy()
        val coordinator = GraphRelocationCoordinator(graphManager, FakeRelocationFileSystem(), quiesce, hangingStep)
        val operation = operationFor(graphId)

        val firstJob = launch { coordinator.relocate(operation).collect { } }
        withTimeout(5_000) {
            while (!MoveInProgressFlag.isMoveInProgress(graphId.value)) yield()
        }

        val secondStates = coordinator.relocate(operation).toList()
        val failed = assertIs<StorageMoveUiState.Failed>(secondStates.single())
        assertIs<DomainError.StorageError.SourceInFlight>(failed.reason)
        assertEquals(1, quiesce.quiesceCalls.size, "the rejected second call must never reach quiesce()")

        firstJob.cancelAndJoin()
        assertFalse(MoveInProgressFlag.isMoveInProgress(graphId.value))
        graphManager.shutdown()
    }

    /** [link] sibling of the relocate re-entrancy guard above — same guard, same rationale. */
    @Test
    fun `link should RejectImmediately When AnotherMoveAlreadyInProgressForSameGraph`() = runBlocking {
        val graphManager = newGraphManager()
        graphManager.openGraph("/test/flag-reentrant-link-${System.nanoTime()}")
        val graphId = graphManager.getActiveGraphId()!!
        val quiesce = FakeGraphMoveQuiesceStrategy()
        val hangingLink = HostLinkStep { _, _ -> awaitCancellation() }
        val coordinator = GraphRelocationCoordinator(
            graphManager,
            FakeRelocationFileSystem(),
            quiesce,
            hostLinkStep = hangingLink,
        )
        val operation = StorageMoveOperation.Link(
            graphId = graphId.value,
            source = StorageLocation.AppOwned(graphId.value),
            destination = StorageLocation.HostFolder(graphId.value, "Documents"),
        )

        val firstJob = launch { coordinator.link(operation).collect { } }
        withTimeout(5_000) {
            while (!MoveInProgressFlag.isMoveInProgress(graphId.value)) yield()
        }

        val secondStates = coordinator.link(operation).toList()
        val failed = assertIs<StorageMoveUiState.Failed>(secondStates.single())
        assertIs<DomainError.StorageError.SourceInFlight>(failed.reason)

        firstJob.cancelAndJoin()
        assertFalse(MoveInProgressFlag.isMoveInProgress(graphId.value))
        graphManager.shutdown()
    }
}
