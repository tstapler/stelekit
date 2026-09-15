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
        override val persistsDestinationOnSuccess: Boolean = true,
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

    /**
     * Regression test for the app-owned-storage-clone spec-compliance sweep's highest-severity
     * finding: Android never wired a [HostLinkStep] (`androidMain`'s `createAndroidHostLinkStep()`,
     * new for Story 4.2.1), so every Android "Link" click on a git-cloned graph fell through to
     * [DestinationNotWritable] regardless of the UI's correct git-cloned gating. This test models
     * `createAndroidHostLinkStep()`'s contract — a step that always succeeds and sets
     * [HostLinkStep.persistsDestinationOnSuccess] `false` — without depending on `androidMain`
     * (unreachable from this `businessTest` source set); `HostLinkStepAndroidTest`
     * (`androidUnitTest`) separately covers the real Android factory function's behavior.
     */
    @Test
    fun `link should SucceedWithoutRepointing When StepDoesNotPersistDestination`() = runBlocking {
        val runId = System.nanoTime()
        val safPath = "/test/link-graph-saf-$runId"
        val graphManager = newGraphManager()
        graphManager.openGraph(safPath)
        val graphId = graphManager.getActiveGraphId()!!

        // Mirrors Story 4.2.1's own acceptance-criteria scenario: an Android git-cloned graph
        // currently on SafFolder, "Link" chosen with destination AppOwned.
        val step = FakeHostLinkStep(persistsDestinationOnSuccess = false)
        val coordinator = newLinkedCoordinator(graphManager, step)
        val source = StorageLocation.SafFolder(graphId.value, "content://tree/123")
        val destination = StorageLocation.AppOwned(graphId.value)
        val operation = StorageMoveOperation.Link(
            graphId = graphId.value,
            source = source,
            destination = destination,
        )

        val states = coordinator.link(operation).toList()

        states.filterIsInstance<StorageMoveUiState.Failed>().forEach {
            throw AssertionError("link failed unexpectedly: ${it.reason}")
        }
        assertEquals(StorageMoveUiState.Summary, states.last())
        assertEquals(1, step.calls.size)

        // The core assertion: a step that opts out of persisting must leave storage_locations
        // untouched — "Link never repoints the location of record — only Relocate does."
        assertEquals(null, graphManager.getStorageLocation(graphId.value))
        // GraphInfo.path is likewise untouched — link() never calls updateGraphContentPath.
        assertEquals(safPath, graphManager.getGraphInfo(graphId)?.path)

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

    /**
     * MAJOR finding from the PR #327 review: `FakeGraphMoveQuiesceStrategy.releaseSourceGrantCalls`
     * was recorded but never asserted anywhere, so a regression that dropped or mistimed
     * `GraphRelocationCoordinator.relocate`'s `releaseSourceGrant(operation.source)` call (Epic 5.2 —
     * releases the source's OS-level SAF grant once a move is confirmed) would pass every existing
     * test. Housed here rather than in `GraphRelocationCoordinatorTest.kt`: `relocate()`, not
     * `link()`, is the one that calls it — see `reopenAndRepersistAfterRelocate`'s doc comment.
     */
    @Test
    fun `relocate should ReleaseSourceGrant When HappyPath`() = runBlocking {
        val runId = System.nanoTime()
        val graphManager = newGraphManager()
        graphManager.openGraph("/test/relocate-release-grant-$runId")
        val graphId = graphManager.getActiveGraphId()!!

        val markdownFs = FakeRelocationFileSystem()
        markdownFs.writeFileBytes("source/page1.md", "hello".encodeToByteArray())
        val quiesce = FakeGraphMoveQuiesceStrategy()
        val coordinator = GraphRelocationCoordinator(graphManager, markdownFs, quiesce)

        val source: StorageLocation = StorageLocation.DirectAccessFolder(graphId.value, "source")
        val operation = StorageMoveOperation.Relocate(
            graphId = graphId.value,
            source = source,
            destination = StorageLocation.DirectAccessFolder(graphId.value, "dest"),
            deleteSourceAfterVerify = false,
        )

        val states = coordinator.relocate(operation).toList()

        assertEquals(StorageMoveUiState.Summary, states.last())
        assertEquals(listOf(source), quiesce.releaseSourceGrantCalls)

        graphManager.shutdown()
    }

    /** Negative counterpart to the happy-path assertion above — a failed copy must never release
     * the source's grant, since the app may still need to fall back to reading it. */
    @Test
    fun `relocate should NotReleaseSourceGrant When CopyFails`() = runBlocking {
        val runId = System.nanoTime()
        val graphManager = newGraphManager()
        graphManager.openGraph("/test/relocate-release-grant-fail-$runId")
        val graphId = graphManager.getActiveGraphId()!!

        val markdownFs = FakeRelocationFileSystem()
        markdownFs.writeFileBytes("source/page1.md", "hello".encodeToByteArray())
        val quiesce = FakeGraphMoveQuiesceStrategy()
        val failingStep = CopyAndVerifyStep { _, _, _ ->
            DomainError.StorageError.DestinationNotWritable("dest/page1.md").left()
        }
        val coordinator = GraphRelocationCoordinator(graphManager, markdownFs, quiesce, failingStep)

        val operation = StorageMoveOperation.Relocate(
            graphId = graphId.value,
            source = StorageLocation.DirectAccessFolder(graphId.value, "source"),
            destination = StorageLocation.DirectAccessFolder(graphId.value, "dest"),
            deleteSourceAfterVerify = false,
        )

        val states = coordinator.relocate(operation).toList()

        assertIs<StorageMoveUiState.Failed>(states.last())
        assertTrue(quiesce.releaseSourceGrantCalls.isEmpty(), "grant must not be released on a failed copy")

        graphManager.shutdown()
    }
}
