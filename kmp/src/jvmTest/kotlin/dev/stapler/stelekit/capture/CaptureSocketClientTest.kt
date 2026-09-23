// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage for [CaptureSocketClient] — the client side of [CaptureSocketListener]'s Unix-
 * domain-socket fast path. Uses a real bound [CaptureSocketListener] on a temp-dir socket path
 * for the positive case and an unbound path for the negative case, plus a real `IN_MEMORY`-
 * backed [GraphManager] (same construction pattern as `CaptureSocketListenerTest`).
 */
class CaptureSocketClientTest {

    /**
     * [FakeFileSystem.fileExists] always returns `true`, which GraphManager.addGraph reads as
     * "vault marker present" and marks the graph paranoid-mode-locked (see the same override
     * in `CaptureSocketListenerTest`). Override it so these tests get a normal, writable graph.
     */
    private class NonVaultFakeFileSystem : FakeFileSystem() {
        override fun fileExists(path: String): Boolean = false
    }

    private suspend fun newActiveGraphManager(): Pair<GraphManager, PlatformFileSystem> {
        val graphManager = GraphManager(
            platformSettings = InMemorySettings(),
            driverFactory = DriverFactory(),
            fileSystem = NonVaultFakeFileSystem(),
            defaultBackend = GraphBackend.IN_MEMORY,
        )
        val graphPath = Files.createTempDirectory("capture-socket-client-test").toString()
        val id = graphManager.addGraph(graphPath)
        graphManager.switchGraph(id)
        graphManager.awaitPendingMigration()

        // CaptureWriter's GraphWriter.savePage() enforces a security whitelist on the real
        // PlatformFileSystem — registerGraphRoot() must match the graph path or every save
        // silently resolves to Failed (the DB write still lands, but the file write doesn't).
        return graphManager to PlatformFileSystem.withRoot(graphPath)
    }

    private suspend fun blocksWithContent(graphManager: GraphManager, content: String): List<*> {
        val repoSet = requireNotNull(graphManager.getActiveRepositorySet())
        val journal = repoSet.journalService.ensureTodayJournal()
        val blocks = repoSet.blockRepository.getBlocksForPage(journal.uuid).first().getOrNull().orEmpty()
        return blocks.filter { it.content == content }
    }

    private fun newSocketPath() =
        Files.createTempDirectory("capture-socket-client-test-sock").resolve("stelekit.sock")

    @Test
    fun trySend_should_ReturnDeliveredTrue_And_WriteNoFile_When_ListenerIsBoundAndAcknowledges() = runBlocking {
        val (graphManager, fileSystem) = newActiveGraphManager()
        val socketPath = newSocketPath()
        val listener = CaptureSocketListener(fileSystem, socketPath.toString())
        listener.attachGraphManager(graphManager)
        listener.start()
        val pendingCapturesDir = Files.createTempDirectory("capture-socket-client-test-pending")

        try {
            val result = CaptureSocketClient.trySend("Buy milk", socketPath.toString())

            assertTrue(result.delivered, "expected delivery over a live socket to succeed")
            assertTrue(result.captureId.isNotBlank())
            assertEquals(1, blocksWithContent(graphManager, "Buy milk").size)

            val pendingFiles = Files.list(pendingCapturesDir).use { it.toList() }
            assertTrue(pendingFiles.isEmpty(), "trySend must never write a pending-capture file itself")
        } finally {
            listener.stop()
        }
    }

    @Test
    fun trySend_should_ReturnDeliveredFalse_When_NoListenerBoundWithinTimeout() {
        val socketPath = newSocketPath() // nothing bound to this path

        val result = CaptureSocketClient.trySend("Buy milk", socketPath.toString())

        assertFalse(result.delivered)
        assertTrue(result.captureId.isNotBlank())
    }
}
