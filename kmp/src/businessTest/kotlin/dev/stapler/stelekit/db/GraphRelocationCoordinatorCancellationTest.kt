// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Task 3.1.5l — dedicated regression coverage for cancellation-triggered flag-clearing/reopening,
 * previously only an acceptance-criterion claim with no task or test implementing it.
 *
 * - Cancelling mid-copy and separately mid-verify (after the copy sub-phase of the copy-and-verify
 *   step has finished but before it has returned — the point at which the UI would be showing
 *   `Verifying`) both still reopen the driver, suspend on `awaitPendingMigration()` under
 *   `NonCancellable`, discard the destination, never call `onGraphLocationDetermined`, and clear
 *   `MoveInProgressFlag`.
 * - Cancelling during quiesce (before the driver is closed) clears the flag with NO reopen call.
 */
class GraphRelocationCoordinatorCancellationTest : RelocationCoordinatorTestSupport() {

    private fun operationFor(graphId: dev.stapler.stelekit.model.GraphId) = StorageMoveOperation.Relocate(
        graphId = graphId.value,
        source = StorageLocation.DirectAccessFolder(graphId.value, "source"),
        destination = StorageLocation.DirectAccessFolder(graphId.value, "dest"),
        deleteSourceAfterVerify = false,
    )

    @Test
    fun `relocate should ReopenAndDiscardDestination When CancelledMidCopy`() = runBlocking {
        val graphManager = newGraphManager()
        val graphId = graphManager.addGraph("/test/cancel-midcopy-${System.nanoTime()}")

        val markdownFs = FakeRelocationFileSystem()
        markdownFs.writeFileBytes("source/page1.md", "hello".encodeToByteArray())
        val enteredCopy = CompletableDeferred<Unit>()
        val hangingMidCopy = CopyAndVerifyStep { _, _, _ ->
            enteredCopy.complete(Unit)
            awaitCancellation() // never reaches a Verifying transition — cancelled while "Copying"
        }
        val coordinator = GraphRelocationCoordinator(graphManager, markdownFs, FakeGraphMoveQuiesceStrategy(), hangingMidCopy)

        val collectJob = launch { coordinator.relocate(operationFor(graphId)).collect { } }
        withTimeout(5_000) { enteredCopy.await() }

        collectJob.cancelAndJoin()

        assertNotNull(graphManager.activeRepositorySet.value, "driver must be reopened at the original location")
        assertFalse(MoveInProgressFlag.isMoveInProgress(graphId.value))
        assertFalse(markdownFs.fileExists("dest/page1.md"), "destination must be discarded, never repointed to")

        graphManager.shutdown()
        assertNull(selectStorageLocationRow(graphId.value), "onGraphLocationDetermined must never run on a cancelled relocate")
    }

    @Test
    fun `relocate should ReopenAndDiscardDestination When CancelledMidVerify`() = runBlocking {
        val graphManager = newGraphManager()
        val graphId = graphManager.addGraph("/test/cancel-midverify-${System.nanoTime()}")

        val markdownFs = FakeRelocationFileSystem()
        markdownFs.writeFileBytes("source/page1.md", "hello".encodeToByteArray())
        val reachedVerifying = CompletableDeferred<Unit>()
        // Simulates every file already copied (BulkCopyVerifier's per-file copy sub-phase done)
        // and hash-verification now in progress — the checkpoint the UI would show as Verifying.
        val hangingMidVerify = CopyAndVerifyStep { _, _, onProgress ->
            onProgress(1, 1)
            reachedVerifying.complete(Unit)
            awaitCancellation()
        }
        val coordinator = GraphRelocationCoordinator(graphManager, markdownFs, FakeGraphMoveQuiesceStrategy(), hangingMidVerify)

        val states = mutableListOf<StorageMoveUiState>()
        val collectJob = launch { coordinator.relocate(operationFor(graphId)).collect { states += it } }
        withTimeout(5_000) { reachedVerifying.await() }
        yield() // let the Verifying emission (triggered by onProgress above) actually land

        assertTrue(states.any { it is StorageMoveUiState.Verifying }, "expected Verifying before cancellation: $states")

        collectJob.cancelAndJoin()

        assertNotNull(graphManager.activeRepositorySet.value, "driver must be reopened at the original location")
        assertFalse(MoveInProgressFlag.isMoveInProgress(graphId.value))
        assertFalse(markdownFs.fileExists("dest/page1.md"), "destination must be discarded, never repointed to")

        graphManager.shutdown()
        assertNull(selectStorageLocationRow(graphId.value), "onGraphLocationDetermined must never run on a cancelled relocate")
    }

    @Test
    fun `relocate should ClearFlagWithNoReopen When CancelledDuringQuiesce`() = runBlocking {
        val graphManager = newGraphManager()
        val graphId = graphManager.addGraph("/test/cancel-quiesce-${System.nanoTime()}")

        val markdownFs = FakeRelocationFileSystem()
        // Never-completing quiesce means the coordinator is guaranteed to still be inside step
        // 1-2 (before the driver is ever closed) at the moment of cancellation.
        val quiesce = FakeGraphMoveQuiesceStrategy(neverCompletes = true)
        val coordinator = GraphRelocationCoordinator(graphManager, markdownFs, quiesce)

        val collectJob = launch { coordinator.relocate(operationFor(graphId)).collect { } }
        // Let the coordinator reach and enter quiesce (it never returns on its own).
        withTimeout(5_000) {
            while (quiesce.quiesceCalls.isEmpty()) yield()
        }

        collectJob.cancelAndJoin()

        assertFalse(MoveInProgressFlag.isMoveInProgress(graphId.value))
        // The driver was never closed on this path — nothing to reopen, so it stays exactly as
        // addGraph() left it: registered but never switched to.
        assertNull(graphManager.activeRepositorySet.value)
    }
}
