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
import kotlinx.coroutines.runBlocking

/**
 * Task 3.1.5m — a reopen that genuinely fails after a relocate (`awaitPendingMigration()` returns
 * `null`) emits `StorageMoveUiState.ReopenFailed` carrying `DomainError.StorageError.ReopenFailed`,
 * never a plain `Failed`, and still clears `MoveInProgressFlag` and calls `quiesceStrategy.release`.
 *
 * Drives the reopen failure by making the SECOND `switchGraph()` call's driver creation fail —
 * `stelekit.devDataDir` is repointed, right before `relocate()` runs, to a path directly under a
 * read-only directory, so `DriverFactory.jvm.kt`'s own `parentDir.mkdirs()` guard throws
 * `IOException` inside `switchGraph()`'s internal `graphScope.launch { ... }` (caught there by its
 * `catch (e: Exception)`, never rethrown — exactly `awaitPendingMigration()`'s documented `null`
 * path). The FIRST open (before `relocate()` is invoked) uses the normal writable temp directory,
 * isolating "reopen after relocate failed" from "graph never opened at all."
 */
class GraphRelocationCoordinatorReopenFailureTest : RelocationCoordinatorTestSupport() {

    @Test
    fun `relocate should EmitReopenFailed When ReopenGenuinelyFailsAfterHappyPathCopy`() = runBlocking {
        val runId = System.nanoTime()
        val graphManager = newGraphManager()
        graphManager.openGraph("/test/graph-reopenfail-$runId")
        val graphId = graphManager.getActiveGraphId()!!

        val markdownFs = FakeRelocationFileSystem()
        markdownFs.writeFileBytes("source/page1.md", "hello".encodeToByteArray())
        val quiesce = FakeGraphMoveQuiesceStrategy()
        val coordinator = GraphRelocationCoordinator(graphManager, markdownFs, quiesce)

        val operation = StorageMoveOperation.Relocate(
            graphId = graphId.value,
            source = StorageLocation.DirectAccessFolder(graphId.value, "source"),
            destination = StorageLocation.DirectAccessFolder(graphId.value, "dest"),
            deleteSourceAfterVerify = false,
        )

        val readOnlyParent = kotlin.io.path.createTempDirectory("stelekit_reopen_failure_readonly_").toFile()
        check(readOnlyParent.setWritable(false, false)) { "test requires a filesystem that honors POSIX write permissions" }
        val unwritableDevDataDir = "${readOnlyParent.absolutePath}/nested/data-dir"

        val previousDevDataDir = System.getProperty("stelekit.devDataDir")
        try {
            System.setProperty("stelekit.devDataDir", unwritableDevDataDir)
            val states = coordinator.relocate(operation).toList()

            val reopenFailed = assertIs<StorageMoveUiState.ReopenFailed>(states.last())
            assertEquals(graphId, reopenFailed.graphId)
            assertIs<DomainError.StorageError.ReopenFailed>(reopenFailed.cause)

            assertFalse(states.any { it is StorageMoveUiState.Failed }, "must never fold into a plain Failed: $states")
            assertFalse(states.any { it is StorageMoveUiState.Summary }, "must never report Summary on this path: $states")

            assertEquals(1, quiesce.releaseCalls.size)
            assertFalse(MoveInProgressFlag.isMoveInProgress(graphId.value))
        } finally {
            if (previousDevDataDir != null) {
                System.setProperty("stelekit.devDataDir", previousDevDataDir)
            } else {
                System.clearProperty("stelekit.devDataDir")
            }
            readOnlyParent.setWritable(true, false)
            readOnlyParent.deleteRecursively()
        }

        // onGraphLocationDetermined must never have run — no confirmed-open connection to write
        // storage_locations through, even though the underlying copy+verify was a happy path.
        assertNull(selectStorageLocationRow(graphId.value, previousDevDataDir))
    }
}

/** Overload reading the row via an explicit devDataDir, for use after the property has been restored. */
private fun selectStorageLocationRow(graphId: String, devDataDirOverride: String?): Storage_locations? {
    val previous = System.getProperty("stelekit.devDataDir")
    try {
        if (devDataDirOverride != null) System.setProperty("stelekit.devDataDir", devDataDirOverride)
        return selectStorageLocationRow(graphId)
    } finally {
        if (previous != null) System.setProperty("stelekit.devDataDir", previous) else System.clearProperty("stelekit.devDataDir")
    }
}
