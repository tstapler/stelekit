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
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Regression coverage for the gap found after wiring `GraphRelocationCoordinator` into the
 * composition root (`80a7ca8bf1`): a successful relocate copied, verified, and reopened the
 * graph's driver, and called `onGraphLocationDetermined` to persist `storage_locations`' kind/uri
 * metadata — but nothing updated `GraphInfo.path`, the registry field `GraphManager` actually uses
 * to open/read/write the graph's content day to day (`App.kt`'s `currentGraphPath` init,
 * `FilePathRootMigration`, and this coordinator's own `resolveSourceRoot` for `AppOwned`). Without
 * this, the app would keep reading/writing the graph at its OLD path after a "successful" relocate,
 * while the new copy at the destination sat untouched.
 */
class GraphRelocationCoordinatorPathPersistenceTest : RelocationCoordinatorTestSupport() {

    @Test
    fun `relocate should UpdateRegisteredGraphPath When HappyPath`() = runBlocking {
        val runId = System.nanoTime()
        val graphManager = newGraphManager()
        graphManager.openGraph("/test/graph-$runId")
        val graphId = graphManager.getActiveGraphId()!!

        val markdownFs = FakeRelocationFileSystem()
        markdownFs.writeFileBytes("source/page1.md", "hello".encodeToByteArray())

        val coordinator = GraphRelocationCoordinator(graphManager, markdownFs, FakeGraphMoveQuiesceStrategy())
        val operation = StorageMoveOperation.Relocate(
            graphId = graphId.value,
            source = StorageLocation.DirectAccessFolder(graphId.value, "source"),
            destination = StorageLocation.DirectAccessFolder(graphId.value, "dest"),
            deleteSourceAfterVerify = false,
        )

        val states = coordinator.relocate(operation).toList()
        assertEquals(StorageMoveUiState.Summary, states.last())

        // The single most important assertion for the whole relocate feature to actually work: a
        // subsequent lookup of the graph's registered path reflects the NEW destination, not the
        // stale path it was opened at.
        assertEquals("dest", graphManager.getGraphInfo(graphId)?.path)
        // GraphId itself must be untouched — a relocate is not a re-key (see updateGraphContentPath's
        // doc contrasting it with the legacy updateGraphPath()).
        assertEquals(graphId, graphManager.getActiveGraphId())

        graphManager.shutdown()
    }

    @Test
    fun `relocate should UpdateRegisteredGraphPath ToTheActuallyAllocatedRoot When DestinationIsAppOwned`() = runBlocking {
        val runId = System.nanoTime()
        val graphManager = newGraphManager()
        graphManager.openGraph("/test/graph-$runId")
        val graphId = graphManager.getActiveGraphId()!!

        val markdownFs = FakeRelocationFileSystem()
        markdownFs.writeFileBytes("source/page1.md", "hello".encodeToByteArray())

        val coordinator = GraphRelocationCoordinator(graphManager, markdownFs, FakeGraphMoveQuiesceStrategy())
        val operation = StorageMoveOperation.Relocate(
            graphId = graphId.value,
            source = StorageLocation.DirectAccessFolder(graphId.value, "source"),
            destination = StorageLocation.AppOwned(graphId.value),
            deleteSourceAfterVerify = false,
        )

        val states = coordinator.relocate(operation).toList()
        assertEquals(StorageMoveUiState.Summary, states.last())

        // FakeRelocationFileSystem.newAppOwnedGraphPath() hands out a fresh path ("appowned/0",
        // "appowned/1", ...) on every call — this pins the registry to the SAME path the files were
        // actually copied into (allocated once, inside copyIntoStagingThenRepoint), not a second,
        // independently-allocated path that would silently diverge from where the bytes really are.
        assertEquals("appowned/0", graphManager.getGraphInfo(graphId)?.path)
        assertTrue(markdownFs.fileExists("appowned/0/page1.md"), "expected page1.md at the registered app-owned path: ${markdownFs.allFilePaths()}")

        graphManager.shutdown()
    }

    @Test
    fun `relocate should LeaveRegisteredGraphPathUnchanged When VerificationFails`() = runBlocking {
        val runId = System.nanoTime()
        val originalPath = "/test/graph-failure-$runId"
        val graphManager = newGraphManager()
        val graphId = graphManager.addGraph(originalPath)

        val markdownFs = FakeRelocationFileSystem()
        markdownFs.writeFileBytes("source/page1.md", "hello".encodeToByteArray())
        val failingStep = CopyAndVerifyStep { _, _, _ ->
            DomainError.StorageError.VerificationFailed("page1.md", "hash mismatch").left()
        }
        val coordinator = GraphRelocationCoordinator(graphManager, markdownFs, FakeGraphMoveQuiesceStrategy(), failingStep)

        val operation = StorageMoveOperation.Relocate(
            graphId = graphId.value,
            source = StorageLocation.DirectAccessFolder(graphId.value, "source"),
            destination = StorageLocation.DirectAccessFolder(graphId.value, "dest"),
            deleteSourceAfterVerify = false,
        )

        val states = coordinator.relocate(operation).toList()
        assertIs<StorageMoveUiState.Failed>(states.last())

        // Step 7 (and the path update alongside it) must never run on a failure path — the registry
        // must still point at the untouched original location.
        assertEquals(originalPath, graphManager.getGraphInfo(graphId)?.path)

        graphManager.shutdown()
    }
}
