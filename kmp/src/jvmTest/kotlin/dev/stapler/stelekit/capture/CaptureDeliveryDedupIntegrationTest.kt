// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * End-to-end regression for the lost-ack scenario Story 3.2.1 describes: a headless CLI capture
 * reaches [CaptureSocketListener] (which writes the block and replies `"OK\n"`), but the client
 * never sees that reply — a dropped packet, a client crash right after sending — and falls back
 * to [PendingCaptureWriter] with the SAME `captureId` [CaptureSocketClient.trySend] already
 * generated. When [PendingCapturePoller] later replays that fallback file, the write must
 * resolve to the single block the listener already applied, not a duplicate — proven here via
 * [CaptureWriter]'s `INSERT OR REPLACE` semantics keyed on `captureId`.
 */
class CaptureDeliveryDedupIntegrationTest {

    private class NonVaultFakeFileSystem : FakeFileSystem() {
        override fun fileExists(path: String): Boolean = false
    }

    private suspend fun newActiveSqlDelightGraphManager(): Pair<GraphManager, PlatformFileSystem> {
        val graphManager = GraphManager(
            platformSettings = InMemorySettings(),
            driverFactory = DriverFactory(),
            fileSystem = NonVaultFakeFileSystem(),
            defaultBackend = GraphBackend.SQLDELIGHT,
        )
        val graphPath = Files.createTempDirectory("capture-dedup-integration").toString()
        val id = graphManager.addGraph(graphPath)
        graphManager.switchGraph(id)
        graphManager.awaitPendingMigration()
        return graphManager to PlatformFileSystem.withRoot(graphPath)
    }

    @Test
    fun captureDelivery_should_ResolveToSingleBlock_When_AckIsLostAfterListenerAlreadyWroteAndClientFallsBackToFile() =
        runBlocking {
            val (graphManager, fileSystem) = newActiveSqlDelightGraphManager()
            val captureId = UuidGenerator.generateV7()
            val text = "Ship the feature before the ack times out"

            // (a) The listener already applied the write — simulating CaptureSocketListener
            // having decoded the payload and called writeCaptureDirect before its "OK\n" reply
            // was lost in transit.
            val listenerWrite = CaptureWriter.writeCaptureDirect(graphManager, fileSystem, text, captureId)
            assertIs<CaptureResult.Saved>(listenerWrite)

            // (b) The client, having timed out waiting for the ack, falls back to writing the
            // same captureId to the pending-captures queue (CaptureSocketClient.trySend's
            // documented fallback contract).
            val pendingDir = Files.createTempDirectory("capture-dedup-pending")
            PendingCaptureWriter.write(text, captureId, pendingDir.toString())

            // (c) The poller later replays that fallback file against the same graph.
            val poller = PendingCapturePoller(fileSystem, directory = pendingDir.toString())
            poller.attachGraphManager(graphManager)
            poller.scanOnce()

            val repoSet = requireNotNull(graphManager.getActiveRepositorySet())
            val journal = repoSet.journalService.ensureTodayJournal()
            val blocks = repoSet.blockRepository.getBlocksForPage(journal.uuid).first().getOrNull().orEmpty()
            val matching = blocks.filter { it.uuid == BlockUuid(captureId) }
            assertEquals(
                1,
                matching.size,
                "expected the lost-ack replay to resolve to a single block via INSERT OR REPLACE, not a duplicate",
            )
            assertEquals(text, matching.single().content)
        }
}
