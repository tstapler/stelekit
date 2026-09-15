// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Regression coverage for the bug found wiring `GraphRelocationCoordinator` into the composition
 * root (`491d088cc4`): `StorageLocation.resolveRootPathOrNull()` returned null for
 * [StorageLocation.AppOwned], so every relocate touching it — the default location for every
 * newly-created graph — failed immediately with `Failed(DestinationNotWritable)` before any file
 * was ever copied. Asserts a `Relocate` with `AppOwned` as source or destination actually resolves
 * a real root path and reaches [StorageMoveUiState.Summary].
 */
class GraphRelocationCoordinatorAppOwnedTest : RelocationCoordinatorTestSupport() {

    @Test
    fun `relocate should ReachSummary When SourceIsAppOwned`() = runBlocking {
        val runId = System.nanoTime()
        val appOwnedPath = "/test/appowned-graph-$runId"
        val graphManager = newGraphManager()
        graphManager.openGraph(appOwnedPath)
        val graphId = graphManager.getActiveGraphId()!!

        val markdownFs = FakeRelocationFileSystem()
        markdownFs.writeFileBytes("$appOwnedPath/page1.md", "hello".encodeToByteArray())
        markdownFs.writeFileBytes("$appOwnedPath/page2.md", "world".encodeToByteArray())

        val coordinator = GraphRelocationCoordinator(graphManager, markdownFs, FakeGraphMoveQuiesceStrategy())
        val operation = StorageMoveOperation.Relocate(
            graphId = graphId.value,
            source = StorageLocation.AppOwned(graphId.value),
            destination = StorageLocation.DirectAccessFolder(graphId.value, "dest"),
            deleteSourceAfterVerify = false,
        )

        val states = coordinator.relocate(operation).toList()

        // The bug this test guards against: an unresolved AppOwned root used to fail here with
        // Failed(DestinationNotWritable) before a single file was copied.
        states.filterIsInstance<StorageMoveUiState.Failed>().forEach {
            throw AssertionError("relocate failed unexpectedly: ${it.reason}")
        }
        assertEquals(StorageMoveUiState.Summary, states.last())

        assertTrue(markdownFs.fileExists("dest/page1.md"), "expected page1.md copied to dest: ${markdownFs.allFilePaths()}")
        assertTrue(markdownFs.fileExists("dest/page2.md"), "expected page2.md copied to dest: ${markdownFs.allFilePaths()}")

        graphManager.shutdown()
    }

    @Test
    fun `relocate should ReachSummary When DestinationIsAppOwned`() = runBlocking {
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

        states.filterIsInstance<StorageMoveUiState.Failed>().forEach {
            throw AssertionError("relocate failed unexpectedly: ${it.reason}")
        }
        assertEquals(StorageMoveUiState.Summary, states.last())

        // A fresh app-owned path is allocated (never the graph's existing/source path) and
        // actually populated by the copy.
        assertTrue(
            markdownFs.fileExists("appowned/0/page1.md"),
            "expected page1.md copied to a freshly-allocated app-owned path: ${markdownFs.allFilePaths()}",
        )

        graphManager.shutdown()
    }

    @Test
    fun `relocate should FailWithDestinationNotWritable When LocationIsHostFolder`() = runBlocking {
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
            destination = StorageLocation.HostFolder(graphId.value, "notes"),
            deleteSourceAfterVerify = false,
        )

        val states = coordinator.relocate(operation).toList()

        val failed = assertIs<StorageMoveUiState.Failed>(states.last())
        val error = assertIs<dev.stapler.stelekit.error.DomainError.StorageError.DestinationNotWritable>(failed.reason)
        assertTrue(
            error.message.contains("HostDirectorySync") || error.message.contains("FileSystemDirectoryHandle"),
            "expected a documented-limitation message, got: ${error.message}",
        )

        graphManager.shutdown()
    }
}
