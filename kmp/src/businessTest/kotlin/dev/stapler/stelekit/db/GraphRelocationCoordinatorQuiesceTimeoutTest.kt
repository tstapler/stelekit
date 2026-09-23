// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Task 3.1.5i — quiesce timeout surfaces `Failed(QuiesceTimedOut)` instead of hanging, clears
 * `MoveInProgressFlag`, and never invokes `switchGraph` (the driver was never closed on this
 * path).
 *
 * Uses `runTest` (virtual time) rather than `runBlocking` — `FakeGraphMoveQuiesceStrategy(
 * neverCompletes = true)` suspends forever via `awaitCancellation()`, so the 30s
 * `QUIESCE_TIMEOUT_MS` bound must be crossed by fast-forwarding the coroutine test scheduler, not
 * by an actual 30-second sleep.
 */
class GraphRelocationCoordinatorQuiesceTimeoutTest : RelocationCoordinatorTestSupport() {

    @Test
    fun `relocate should FailWithQuiesceTimedOut When QuiesceNeverCompletes`() = runTest {
        val runId = System.nanoTime()
        val graphManager = newGraphManager()
        val graphId = graphManager.addGraph("/test/graph-quiesce-timeout-$runId")

        val markdownFs = FakeRelocationFileSystem()
        val quiesce = FakeGraphMoveQuiesceStrategy(neverCompletes = true)
        val coordinator = GraphRelocationCoordinator(graphManager, markdownFs, quiesce)

        val operation = StorageMoveOperation.Relocate(
            graphId = graphId.value,
            source = StorageLocation.DirectAccessFolder(graphId.value, "source"),
            destination = StorageLocation.DirectAccessFolder(graphId.value, "dest"),
            deleteSourceAfterVerify = false,
        )

        val states = coordinator.relocate(operation).toList()

        val failed = assertIs<StorageMoveUiState.Failed>(states.last())
        val reason = assertIs<DomainError.StorageError.QuiesceTimedOut>(failed.reason)
        assertEquals(GraphRelocationCoordinator.QUIESCE_TIMEOUT_MS, reason.waitedMs)

        // Driver was never closed on this path.
        assertNull(graphManager.activeRepositorySet.value)

        assertEquals(1, quiesce.releaseCalls.size)
        assertFalse(MoveInProgressFlag.isMoveInProgress(graphId.value))
    }
}
