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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Coverage for [PendingCapturePoller] — cold-start replay of [PendingCaptureFile]s left on
 * disk by [PendingCaptureWriter] through the same [CaptureWriter.writeCaptureDirect] chain the
 * live hotkey popup uses. Uses a real `IN_MEMORY`-backed [GraphManager] (same construction
 * pattern as `CaptureControllerTest`) rather than mocking [CaptureWriter], which is an `object`.
 */
class PendingCapturePollerTest {

    /**
     * [FakeFileSystem.fileExists] always returns `true`, which GraphManager.addGraph reads as
     * "vault marker present" and marks the graph paranoid-mode-locked (see the same override
     * in `CaptureWriterTest`/`CaptureControllerTest`). Override it so these tests get a normal,
     * writable graph.
     */
    private class NonVaultFakeFileSystem : FakeFileSystem() {
        override fun fileExists(path: String): Boolean = false
    }

    private data class ActiveSetup(val graphManager: GraphManager, val fileSystem: PlatformFileSystem)

    private suspend fun newActiveSetup(): ActiveSetup {
        val graphManager = GraphManager(
            platformSettings = InMemorySettings(),
            driverFactory = DriverFactory(),
            fileSystem = NonVaultFakeFileSystem(),
            defaultBackend = GraphBackend.IN_MEMORY,
        )
        val graphPath = Files.createTempDirectory("pending-capture-poller-test").toString()
        val id = graphManager.addGraph(graphPath)
        graphManager.switchGraph(id)
        graphManager.awaitPendingMigration()

        // CaptureWriter's GraphWriter.savePage() enforces a security whitelist on the real
        // PlatformFileSystem — registerGraphRoot() must match the graph path or every save
        // silently resolves to Failed (the DB write still lands, but the file write doesn't).
        return ActiveSetup(graphManager, PlatformFileSystem.withRoot(graphPath))
    }

    private fun writePendingCapture(dir: Path, text: String, captureId: String = UuidGenerator.generateV7()): Path =
        PendingCaptureWriter.write(text = text, captureId = captureId, directory = dir.toString())

    private suspend fun blocksWithContent(graphManager: GraphManager, content: String): List<*> {
        val repoSet = requireNotNull(graphManager.getActiveRepositorySet())
        val journal = repoSet.journalService.ensureTodayJournal()
        val blocks = repoSet.blockRepository.getBlocksForPage(journal.uuid).first().getOrNull().orEmpty()
        return blocks.filter { it.content == content }
    }

    @Test
    fun scanOnce_should_ReplayAndDeleteFile_When_GraphManagerAttachedAndWriteSucceeds() = runBlocking {
        val (graphManager, fileSystem) = newActiveSetup()
        val dir = Files.createTempDirectory("pending-captures")
        val file = writePendingCapture(dir, "Buy milk")

        val poller = PendingCapturePoller(fileSystem, directory = dir.toString())
        poller.attachGraphManager(graphManager)
        poller.scanOnce()

        assertFalse(file.exists(), "expected the pending capture file to be deleted on successful replay")
        assertEquals(1, blocksWithContent(graphManager, "Buy milk").size)
    }

    @Test
    fun scanOnce_should_LeaveFileInPlace_When_GraphManagerNotYetAttached() = runBlocking {
        val fileSystem = PlatformFileSystem()
        val dir = Files.createTempDirectory("pending-captures")
        val file = writePendingCapture(dir, "Buy milk")

        val poller = PendingCapturePoller(fileSystem, directory = dir.toString())
        poller.scanOnce()

        assertTrue(file.exists(), "expected the pending capture file to be left in place for the next scan")
    }

    @Test
    fun scanOnce_should_RenameToFailedAndSkip_When_JsonIsMalformed() = runBlocking {
        val (graphManager, fileSystem) = newActiveSetup()
        val dir = Files.createTempDirectory("pending-captures")
        val malformed = dir.resolve("not-valid.json")
        Files.writeString(malformed, "{ this is not valid json")

        val poller = PendingCapturePoller(fileSystem, directory = dir.toString())
        poller.attachGraphManager(graphManager)
        poller.scanOnce()

        assertFalse(malformed.exists(), "expected the malformed file to be moved away from its original name")
        val failed = dir.resolve("${malformed.name}.failed")
        assertTrue(failed.exists(), "expected the malformed file to be renamed to .failed")
    }

    @Test
    fun scanOnce_should_ResolveToSingleBlock_When_CaptureIdAlreadyAppliedButFileNotDeleted() = runBlocking {
        val (graphManager, fileSystem) = newActiveSetup()
        val dir = Files.createTempDirectory("pending-captures")
        val captureId = UuidGenerator.generateV7()

        // Simulate a crash between the write landing and the pending-capture file being
        // deleted: the block already exists, but the file is still on disk.
        val preCrashResult = CaptureWriter.writeCaptureDirect(graphManager, fileSystem, "Ship the feature", captureId)
        assertIs<CaptureResult.Saved>(preCrashResult)
        writePendingCapture(dir, "Ship the feature", captureId)

        val poller = PendingCapturePoller(fileSystem, directory = dir.toString())
        poller.attachGraphManager(graphManager)
        poller.scanOnce()

        assertEquals(
            1,
            blocksWithContent(graphManager, "Ship the feature").size,
            "expected replaying an already-applied captureId to resolve to a single row, not a duplicate",
        )
    }
}
