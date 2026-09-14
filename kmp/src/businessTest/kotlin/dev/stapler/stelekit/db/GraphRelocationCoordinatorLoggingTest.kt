// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.LogLevel
import dev.stapler.stelekit.logging.LogManager
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Task 5.1.1b — asserts the four structured log points (`MoveStarted`/`MoveVerified`/
 * `MoveCompleted`/`MoveFailed`) added to `GraphRelocationCoordinator` in Story 5.1.1 actually
 * fire, using this codebase's existing `LogManager`/`Logger` mechanism and the same
 * `LogManager.clearLogs()` + `LogManager.logs.value.filter { it.tag == ... }` idiom
 * `GraphWriterTest` already establishes (validation.md REQ-10).
 */
class GraphRelocationCoordinatorLoggingTest : RelocationCoordinatorTestSupport() {

    @Test
    fun `graphRelocationCoordinator should LogAllFourLifecyclePoints When HappyPathCompletes`() = runBlocking {
        val runId = System.nanoTime()
        val graphManager = newGraphManager()
        graphManager.openGraph("/test/graph-logging-$runId")
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

        LogManager.clearLogs()
        val states = coordinator.relocate(operation).toList()
        assertTrue(states.last() is StorageMoveUiState.Summary, "expected happy path: $states")

        val coordinatorLogs = LogManager.logs.value.filter { it.tag == "GraphRelocationCoordinator" }

        assertTrue(
            coordinatorLogs.any {
                it.level == LogLevel.INFO &&
                    it.message.startsWith("MoveStarted") &&
                    it.message.contains(graphId.value) &&
                    it.message.contains("DirectAccessFolder") &&
                    it.message.contains("operation=Relocate")
            },
            "expected a MoveStarted log entry: $coordinatorLogs",
        )
        assertTrue(
            coordinatorLogs.any {
                it.level == LogLevel.INFO &&
                    it.message.startsWith("MoveVerified") &&
                    it.message.contains("passed=true")
            },
            "expected a MoveVerified log entry: $coordinatorLogs",
        )
        assertTrue(
            coordinatorLogs.any {
                it.level == LogLevel.INFO &&
                    it.message.startsWith("MoveCompleted") &&
                    it.message.contains(graphId.value)
            },
            "expected a MoveCompleted log entry: $coordinatorLogs",
        )
        assertTrue(
            coordinatorLogs.none { it.message.startsWith("MoveFailed") },
            "did not expect a MoveFailed log entry on the happy path: $coordinatorLogs",
        )

        graphManager.shutdown()
    }

    @Test
    fun `graphRelocationCoordinator should LogMoveFailedWithStorageErrorSubtype When VerificationFails`() = runBlocking {
        val runId = System.nanoTime()
        val graphManager = newGraphManager()
        val graphId = graphManager.addGraph("/test/graph-logging-failure-$runId")

        val markdownFs = FakeRelocationFileSystem()
        markdownFs.writeFileBytes("source/page1.md", "hello".encodeToByteArray())

        val failingStep = CopyAndVerifyStep { _, _, _ ->
            DomainError.StorageError.VerificationFailed("page1.md", "hash mismatch").left()
        }
        val coordinator = GraphRelocationCoordinator(
            graphManager,
            markdownFs,
            FakeGraphMoveQuiesceStrategy(),
            failingStep,
        )
        val operation = StorageMoveOperation.Relocate(
            graphId = graphId.value,
            source = StorageLocation.AppOwned(graphId.value),
            destination = StorageLocation.DirectAccessFolder(graphId.value, "dest"),
            deleteSourceAfterVerify = false,
        )

        LogManager.clearLogs()
        val states = coordinator.relocate(operation).toList()
        assertTrue(states.last() is StorageMoveUiState.Failed, "expected a Failed terminal state: $states")

        val coordinatorLogs = LogManager.logs.value.filter { it.tag == "GraphRelocationCoordinator" }

        assertTrue(
            coordinatorLogs.any {
                it.level == LogLevel.ERROR &&
                    it.message.startsWith("MoveFailed") &&
                    it.message.contains(graphId.value) &&
                    it.message.contains("AppOwned") &&
                    it.message.contains("DirectAccessFolder") &&
                    it.message.contains("reason=VerificationFailed")
            },
            "expected a MoveFailed log entry naming the DomainError.StorageError subtype: $coordinatorLogs",
        )

        graphManager.shutdown()
    }
}
