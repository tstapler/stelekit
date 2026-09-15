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
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertTrue

/**
 * Coverage for [PendingCapturePoller] against a real `SQLDELIGHT`-backed [GraphManager] and a
 * real temp-dir pending-captures directory — [PendingCapturePollerTest]'s unit tests use
 * `IN_MEMORY`, and drive [PendingCapturePoller.scanOnce] directly rather than the real
 * [PendingCapturePoller.start] poll loop.
 */
class PendingCapturePollerIntegrationTest {

    /** [FakeFileSystem.fileExists] always returning `true` marks the graph paranoid-mode-locked. */
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
        val graphPath = Files.createTempDirectory("pending-capture-poller-integration").toString()
        val id = graphManager.addGraph(graphPath)
        graphManager.switchGraph(id)
        graphManager.awaitPendingMigration()

        // GraphWriter's security whitelist requires the real PlatformFileSystem's registered
        // root to match the graph path (see the same note in CaptureWriterIntegrationTest).
        return graphManager to PlatformFileSystem.withRoot(graphPath)
    }

    @Test
    fun pendingCapturePoller_should_DrainRealPendingCapturesDirectory_When_StartedAgainstLiveSqlDelightGraphManager() =
        runBlocking {
            val (graphManager, fileSystem) = newActiveSqlDelightGraphManager()
            val pendingDir = Files.createTempDirectory("pending-captures-integration")
            val captures = listOf("Buy milk", "Call the dentist", "Ship the feature")
            captures.forEach { text ->
                PendingCaptureWriter.write(text, UuidGenerator.generateV7(), pendingDir.toString())
            }
            assertTrue(pendingDir.listDirectoryEntries().isNotEmpty(), "expected the pending-capture files to exist before draining")

            // A short poll interval keeps this test fast without weakening the assertion — the
            // poller still runs its real start()/scanOnce()/delay() loop, just on a tighter cycle.
            val poller = PendingCapturePoller(fileSystem, directory = pendingDir.toString(), pollIntervalMs = 200L)
            poller.attachGraphManager(graphManager)
            try {
                poller.start()
                awaitDrained(pendingDir)
                assertJournalContainsAllCaptures(graphManager, captures)
            } finally {
                poller.stop()
            }
        }

    private suspend fun awaitDrained(pendingDir: java.nio.file.Path) {
        withTimeout(10_000) {
            while (pendingDir.listDirectoryEntries().any { it.toString().endsWith(".json") }) {
                delay(50)
            }
        }
    }

    private suspend fun assertJournalContainsAllCaptures(graphManager: GraphManager, captures: List<String>) {
        val repoSet = requireNotNull(graphManager.getActiveRepositorySet())
        val journal = repoSet.journalService.ensureTodayJournal()
        val blocks = repoSet.blockRepository.getBlocksForPage(journal.uuid).first().getOrNull().orEmpty()
        captures.forEach { text ->
            assertTrue(
                blocks.any { it.content == text },
                "expected today's journal to contain a block for '$text' after the poller drained it",
            )
        }
    }
}
