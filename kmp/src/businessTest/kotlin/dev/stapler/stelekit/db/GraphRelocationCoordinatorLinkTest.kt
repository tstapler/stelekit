// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Epic 4.1 (Task 4.1.1a): [GraphRelocationCoordinator.link] coverage — the `Link` sibling to
 * [relocate]. Asserts the new function never touches [relocate]'s own logic (a separate function,
 * not a branch inside it — see [GraphRelocationCoordinator.link]'s doc comment) and correctly
 * fails fast when no [HostLinkStep] is wired (every non-Web construction site).
 */
class GraphRelocationCoordinatorLinkTest : RelocationCoordinatorTestSupport() {

    private class FakeHostLinkStep(
        private val outcome: Either<DomainError.StorageError, Unit> = Unit.right(),
    ) : HostLinkStep {
        val calls = mutableListOf<Pair<String, StorageLocation>>()
        override suspend fun link(existingOpfsPath: String, destination: StorageLocation): Either<DomainError.StorageError, Unit> {
            calls += existingOpfsPath to destination
            return outcome
        }
    }

    private fun newLinkedCoordinator(
        graphManager: GraphManager,
        step: HostLinkStep? = FakeHostLinkStep(),
    ): GraphRelocationCoordinator = GraphRelocationCoordinator(
        graphManager,
        FakeRelocationFileSystem(),
        FakeGraphMoveQuiesceStrategy(),
        hostLinkStep = step,
    )

    @Test
    fun `link should ReachSummaryAndInvokeStep When SourceIsAppOwnedAndStepSucceeds`() = runBlocking {
        val runId = System.nanoTime()
        val appOwnedPath = "/test/link-graph-$runId"
        val graphManager = newGraphManager()
        graphManager.openGraph(appOwnedPath)
        val graphId = graphManager.getActiveGraphId()!!

        val step = FakeHostLinkStep()
        val coordinator = newLinkedCoordinator(graphManager, step)
        val destination = StorageLocation.HostFolder(graphId.value, "Documents")
        val operation = StorageMoveOperation.Link(
            graphId = graphId.value,
            source = StorageLocation.AppOwned(graphId.value),
            destination = destination,
        )

        val states = coordinator.link(operation).toList()

        states.filterIsInstance<StorageMoveUiState.Failed>().forEach {
            throw AssertionError("link failed unexpectedly: ${it.reason}")
        }
        assertEquals(StorageMoveUiState.Summary, states.last())
        assertTrue(states.contains(StorageMoveUiState.Quiescing))
        assertTrue(states.contains(StorageMoveUiState.Verifying))
        // Never ReopenFailed — link() has no reopen step at all (OPFS's driver is never closed).
        assertTrue(states.none { it is StorageMoveUiState.ReopenFailed })

        assertEquals(1, step.calls.size)
        assertEquals(appOwnedPath, step.calls.single().first)
        assertEquals(destination, step.calls.single().second)

        assertEquals(destination, graphManager.getStorageLocation(graphId.value))
        // Unlike relocate(), link() never repoints GraphInfo.path — OPFS remains the content root.
        assertEquals(appOwnedPath, graphManager.getGraphInfo(graphId)?.path)

        graphManager.shutdown()
    }

    @Test
    fun `link should FailWithoutInvokingStep When NoHostLinkStepIsWired`() = runBlocking {
        val runId = System.nanoTime()
        val graphManager = newGraphManager()
        graphManager.openGraph("/test/link-graph-nostep-$runId")
        val graphId = graphManager.getActiveGraphId()!!

        // hostLinkStep defaults to null — every non-Web construction site today.
        val coordinator = newLinkedCoordinator(graphManager, step = null)
        val operation = StorageMoveOperation.Link(
            graphId = graphId.value,
            source = StorageLocation.AppOwned(graphId.value),
            destination = StorageLocation.HostFolder(graphId.value, "Documents"),
        )

        val states = coordinator.link(operation).toList()

        val failed = assertIs<StorageMoveUiState.Failed>(states.single())
        assertIs<DomainError.StorageError.DestinationNotWritable>(failed.reason)

        graphManager.shutdown()
    }

    @Test
    fun `link should FailWithStepReason When StepFails`() = runBlocking {
        val runId = System.nanoTime()
        val graphManager = newGraphManager()
        graphManager.openGraph("/test/link-graph-stepfail-$runId")
        val graphId = graphManager.getActiveGraphId()!!

        val reason = DomainError.StorageError.DestinationNotWritable("connect rejected")
        val coordinator = newLinkedCoordinator(graphManager, FakeHostLinkStep(outcome = reason.left()))
        val operation = StorageMoveOperation.Link(
            graphId = graphId.value,
            source = StorageLocation.AppOwned(graphId.value),
            destination = StorageLocation.HostFolder(graphId.value, "Documents"),
        )

        val states = coordinator.link(operation).toList()

        val failed = assertIs<StorageMoveUiState.Failed>(states.last())
        assertEquals(reason, failed.reason)
        // storage_locations must NOT be persisted on a failed link.
        assertEquals(null, graphManager.getStorageLocation(graphId.value))

        graphManager.shutdown()
    }

    @Test
    fun `link should NeverCloseTheDriver When Running`() = runBlocking {
        // Regression guard for this class's own doc comment: unlike relocate(), link() must never
        // call tearDownActiveGraphResources — the graph stays open/editable throughout.
        val runId = System.nanoTime()
        val graphManager = newGraphManager()
        graphManager.openGraph("/test/link-graph-nodriverclose-$runId")
        val graphId = graphManager.getActiveGraphId()!!
        val activeBeforeLink = graphManager.getActiveGraphId()

        val coordinator = newLinkedCoordinator(graphManager)
        val operation = StorageMoveOperation.Link(
            graphId = graphId.value,
            source = StorageLocation.AppOwned(graphId.value),
            destination = StorageLocation.HostFolder(graphId.value, "Documents"),
        )

        coordinator.link(operation).toList()

        // Still the same active graph, never torn down/reopened by link().
        assertEquals(activeBeforeLink, graphManager.getActiveGraphId())

        graphManager.shutdown()
    }
}
