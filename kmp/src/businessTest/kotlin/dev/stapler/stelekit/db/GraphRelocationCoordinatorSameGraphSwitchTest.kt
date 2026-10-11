// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Task 3.1.5j — relocating the currently-active graph reopens the driver despite unchanged
 * `graphId`, and `awaitPendingMigration()` observably returns only after the reopened
 * `RepositorySet` is ready. Regression for both the `switchGraph` idempotency-guard gap (Task
 * 3.1.5b) and the fire-and-forget reopen gap (Task 3.1.5e).
 */
class GraphRelocationCoordinatorSameGraphSwitchTest : RelocationCoordinatorTestSupport() {

    @Test
    fun `relocate should ReopenDriver When TargetIsCurrentlyActiveGraph`() = runBlocking {
        val runId = System.nanoTime()
        val graphManager = newGraphManager()
        val originalRepoSet = graphManager.openGraph("/test/graph-same-$runId")
        val graphId = graphManager.getActiveGraphId()!!

        val markdownFs = FakeRelocationFileSystem()
        markdownFs.writeFileBytes("source/page1.md", "hi".encodeToByteArray())
        val coordinator = GraphRelocationCoordinator(graphManager, markdownFs, FakeGraphMoveQuiesceStrategy())

        val operation = StorageMoveOperation.Relocate(
            graphId = graphId.value,
            source = StorageLocation.DirectAccessFolder(graphId.value, "source"),
            destination = StorageLocation.DirectAccessFolder(graphId.value, "dest"),
            deleteSourceAfterVerify = false,
        )

        val states = coordinator.relocate(operation).toList()
        assertEquals(StorageMoveUiState.Summary, states.last())

        // Pre-Task-3.1.5b, switchGraph(id)'s idempotency guard would have treated this as an
        // already-active no-op and never actually reopened the connection — the RepositorySet
        // instance would be unchanged. forceReinit=true bypasses that guard.
        val reopenedRepoSet = graphManager.activeRepositorySet.value
        assertNotNull(reopenedRepoSet)
        assertNotSame(
            originalRepoSet,
            reopenedRepoSet,
            "relocating the active graph must actually reopen its RepositorySet, not no-op via the idempotency guard",
        )

        graphManager.shutdown()
        assertEquals("DirectAccessFolder", selectStorageLocationRow(graphId.value)?.kind)
    }
}
