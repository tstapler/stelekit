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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Task 3.1.5h — verification-failure and copy-failure both reopen the driver via
 * `switchGraph(forceReinit = true)` AND await `awaitPendingMigration()` BEFORE emitting `Failed`,
 * leave the source untouched, never call `onGraphLocationDetermined`, and clear
 * `MoveInProgressFlag` (validation.md
 * `relocate_should_ReopenAndConfirmDriverBeforeEmittingFailed_When_VerificationOrCopyFails`).
 *
 * Both scenarios below drive the reopen through a `GraphManager` constructed with a `preFlightJob`
 * that is NOT completed until the test explicitly completes it — `switchGraph()`'s internal
 * `graphScope.launch(PlatformDispatcher.IO) { ... }` awaits this before `_activeRepositorySet` is
 * ever set (see `GraphManagerDatabaseLifecycleTest`'s identical precedent for this seam). This
 * makes the reopen genuinely asynchronous/delayed rather than same-thread — the test asserts
 * nothing terminal is observable until the gate is released, which would fail if the coordinator
 * merely *called* `switchGraph` without actually awaiting the reopen it schedules.
 */
class GraphRelocationCoordinatorFailureTest : RelocationCoordinatorTestSupport() {

    @Test
    fun `relocate should ReopenAndConfirmDriverBeforeEmittingFailed When VerificationFails`() = runBlocking {
        runFailureScenario(
            runId = System.nanoTime(),
            failingStep = CopyAndVerifyStep { _, _, _ ->
                DomainError.StorageError.VerificationFailed("page1.md", "hash mismatch").left()
            },
        )
    }

    @Test
    fun `relocate should ReopenAndConfirmDriverBeforeEmittingFailed When CopyFails`() = runBlocking {
        runFailureScenario(
            runId = System.nanoTime(),
            failingStep = CopyAndVerifyStep { _, _, _ ->
                DomainError.StorageError.DestinationNotWritable("dest/page1.md").left()
            },
        )
    }

    /**
     * BLOCKER 3 regression test (PR #327 review): real platform [GraphMoveQuiesceStrategy]
     * implementations can throw a raw `Throwable` from `quiesce()` rather than returning
     * `Either.Left` — `relocate()` must convert that into a normal `Failed` state rather than
     * letting it propagate out of the `channelFlow` uncaught.
     */
    @Test
    fun `relocate should EmitFailed When QuiesceThrowsRawThrowable`() = runBlocking {
        val runId = System.nanoTime()
        val graphManager = newGraphManager()
        val graphId = graphManager.addGraph("/test/graph-quiesce-throws-$runId")

        val quiesce = FakeGraphMoveQuiesceStrategy(throwOnQuiesce = IllegalStateException("boom"))
        val coordinator = GraphRelocationCoordinator(graphManager, FakeRelocationFileSystem(), quiesce)

        val operation = StorageMoveOperation.Relocate(
            graphId = graphId.value,
            source = StorageLocation.DirectAccessFolder(graphId.value, "source"),
            destination = StorageLocation.DirectAccessFolder(graphId.value, "dest"),
            deleteSourceAfterVerify = false,
        )

        // The bug this guards against: an uncaught Throwable here would fail this collect (and,
        // in production, crash whatever scope is collecting the flow) instead of yielding a
        // well-formed terminal state.
        val states = withTimeout(5_000) { coordinator.relocate(operation).toList() }

        val failed = assertIs<StorageMoveUiState.Failed>(states.last())
        assertIs<DomainError.StorageError.RelocationFailed>(failed.reason)
        assertFalse(MoveInProgressFlag.isMoveInProgress(graphId.value))
        assertEquals(1, quiesce.releaseCalls.size)

        graphManager.shutdown()
    }

    private suspend fun CoroutineScope.runFailureScenario(runId: Long, failingStep: CopyAndVerifyStep) {
        val preFlightGate = CompletableDeferred<Unit>()
        val graphManager = newGraphManager(preFlightJob = preFlightGate)
        // Registered but never opened — tearDownActiveGraphResources() is a safe no-op, and the
        // coordinator's own reopen is then the ONLY switchGraph() call in this scenario.
        val graphId = graphManager.addGraph("/test/graph-failure-$runId")

        val markdownFs = FakeRelocationFileSystem()
        markdownFs.writeFileBytes("source/page1.md", "hello".encodeToByteArray())
        val quiesce = FakeGraphMoveQuiesceStrategy()
        val coordinator = GraphRelocationCoordinator(graphManager, markdownFs, quiesce, failingStep)

        val operation = StorageMoveOperation.Relocate(
            graphId = graphId.value,
            source = StorageLocation.DirectAccessFolder(graphId.value, "source"),
            destination = StorageLocation.DirectAccessFolder(graphId.value, "dest"),
            deleteSourceAfterVerify = false,
        )

        val states = mutableListOf<StorageMoveUiState>()
        val collectJob = launch(Dispatchers.Default) {
            coordinator.relocate(operation).collect { states += it }
        }

        // The reopen is blocked on preFlightGate — give the (fast, in-memory) quiesce/copy-failure
        // steps ample real time to run to completion and reach the reopen suspension point.
        delay(300)
        assertTrue(
            states.none { it is StorageMoveUiState.Failed },
            "Failed must not be emitted before the reopen actually completes: $states",
        )
        assertNull(
            graphManager.activeRepositorySet.value,
            "graph must not be considered editable before awaitPendingMigration() returns",
        )

        preFlightGate.complete(Unit)
        withTimeout(5_000) { collectJob.join() }

        val failed = assertIs<StorageMoveUiState.Failed>(states.last())
        assertEquals(quiesce.releaseCalls.size, 1)
        assertFalse(MoveInProgressFlag.isMoveInProgress(graphId.value))
        assertNotNull(
            graphManager.activeRepositorySet.value,
            "graph must be confirmed editable again by the time Failed is observed",
        )

        // Source untouched, nothing landed at the destination.
        assertTrue(markdownFs.fileExists("source/page1.md"))
        assertFalse(markdownFs.fileExists("dest/page1.md"))

        // onGraphLocationDetermined never ran on this path.
        graphManager.shutdown()
        assertNull(selectStorageLocationRow(graphId.value))
        assertIs<DomainError.StorageError>(failed.reason)
    }
}
