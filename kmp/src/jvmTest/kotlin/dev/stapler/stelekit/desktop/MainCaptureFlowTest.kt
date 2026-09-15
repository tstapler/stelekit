// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.desktop

import com.tulskiy.keymaster.common.HotKey
import com.tulskiy.keymaster.common.HotKeyListener
import com.tulskiy.keymaster.common.MediaKey
import com.tulskiy.keymaster.common.Provider
import dev.stapler.stelekit.capture.CaptureController
import dev.stapler.stelekit.capture.CapturePopupState
import dev.stapler.stelekit.capture.CaptureResult
import dev.stapler.stelekit.capture.CaptureSocketClient
import dev.stapler.stelekit.capture.CaptureSocketListener
import dev.stapler.stelekit.capture.JKeymasterHotkeyListener
import dev.stapler.stelekit.capture.PendingCapturePoller
import dev.stapler.stelekit.capture.PendingCaptureWriter
import dev.stapler.stelekit.capture.SaveState
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import javax.swing.KeyStroke
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Coverage for the wiring in `Main.kt`'s `application { }` body — the real hotkey → popup →
 * save → journal flow, and the [CaptureSurfaces] fan-out/shutdown seams `main()` calls from
 * `onGraphManagerReady`/`onCloseRequest`. `Main.kt` itself launches a real Compose `Window`,
 * which this sandbox has no display for, so these tests exercise the same real, non-Compose
 * components (`CaptureController`, `JKeymasterHotkeyListener`, `PendingCapturePoller`,
 * `CaptureSocketListener`, `CaptureSurfaces`) `main()` wires together, without ever opening a
 * window — see [dev.stapler.stelekit.capture.CapturePopupWindowTest] for the Compose-rendering
 * side of the popup.
 */
class MainCaptureFlowTest {

    /**
     * Fires the registered hotkey synchronously inside `register()`, standing in for a real
     * hotkey press — same subclass-a-real-Provider pattern `JKeymasterHotkeyListenerTest` uses.
     */
    private class AutoFiringFakeProvider : Provider() {
        var stopCalled = false
        var resetCalled = false

        override fun init() = Unit
        override fun reset() { resetCalled = true }
        override fun stop() { stopCalled = true }
        override fun register(keyStroke: KeyStroke, listener: HotKeyListener) {
            listener.onHotKey(HotKey(keyStroke, listener))
        }
        override fun register(mediaKey: MediaKey, listener: HotKeyListener) = Unit
        override fun unregister(keyStroke: KeyStroke) = Unit
        override fun unregister(mediaKey: MediaKey) = Unit
    }

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
        val graphPath = Files.createTempDirectory("main-capture-flow-test").toString()
        val id = graphManager.addGraph(graphPath)
        graphManager.switchGraph(id)
        graphManager.awaitPendingMigration()
        return graphManager to PlatformFileSystem.withRoot(graphPath)
    }

    @Test
    fun captureFlow_should_AppendBlockToTodaysJournal_When_HotkeyTypeSaveEndToEndFlowRuns() = runBlocking {
        val (graphManager, fileSystem) = newActiveSqlDelightGraphManager()
        val controller = CaptureController(fileSystem)
        controller.attachGraphManager(graphManager)
        val hotkeyListener = JKeymasterHotkeyListener(providerFactory = { AutoFiringFakeProvider() })

        // start() registers the hotkey; the fake provider fires it immediately, so show() has
        // already run by the time start() returns.
        controller.start(hotkeyListener)
        assertIs<CapturePopupState.Shown>(controller.state.value)

        controller.updateText("Buy milk")
        controller.save()

        val saved = withTimeout(5_000) {
            var state = controller.state.first { it is CapturePopupState.Shown && it.saveState != SaveState.Saving }
            while (state is CapturePopupState.Shown && state.saveState == SaveState.Idle) {
                delay(10)
                state = controller.state.value
            }
            state
        }
        assertIs<CapturePopupState.Shown>(saved).let { assertTrue(it.saveState == SaveState.Saved, "expected Saved, got $it") }

        val repoSet = requireNotNull(graphManager.getActiveRepositorySet())
        val journal = repoSet.journalService.ensureTodayJournal()
        val blocks = repoSet.blockRepository.getBlocksForPage(journal.uuid).first().getOrNull().orEmpty()
        assertTrue(blocks.any { it.content == "Buy milk" }, "expected today's journal to contain the typed capture")
    }

    @Test
    fun onCloseRequest_should_StopHotkeyListenerAndSocketListener_Before_ExitApplication() = runBlocking {
        val (graphManager, fileSystem) = newActiveSqlDelightGraphManager()
        val controller = CaptureController(fileSystem)
        val fakeProvider = AutoFiringFakeProvider()
        val hotkeyListener = JKeymasterHotkeyListener(providerFactory = { fakeProvider })
        controller.start(hotkeyListener) // registers with fakeProvider; also fires show() once

        val socketPath = Files.createTempDirectory("main-shutdown-socket").resolve("stelekit.sock").toString()
        val socketListener = CaptureSocketListener(fileSystem, socketPath = socketPath)
        socketListener.attachGraphManager(graphManager)
        socketListener.start()
        assertTrue(java.nio.file.Path.of(socketPath).exists(), "expected the socket listener to have bound")

        val pendingDir = Files.createTempDirectory("main-shutdown-pending")
        val poller = PendingCapturePoller(fileSystem, directory = pendingDir.toString(), pollIntervalMs = 100L)
        poller.attachGraphManager(graphManager)
        poller.start()

        val surfaces = CaptureSurfaces(controller, hotkeyListener, poller, socketListener)

        // main()'s real onCloseRequest calls exactly this before exitApplication() — proving
        // all three real shutdowns happen is the closest headless equivalent to asserting
        // "before exitApplication", since exitApplication() itself is Compose-only and is called
        // strictly after this returns in Main.kt.
        surfaces.stopAll()

        assertTrue(fakeProvider.stopCalled, "expected CaptureController.stop() to unregister the hotkey listener")

        assertFalse(java.nio.file.Path.of(socketPath).exists(), "expected CaptureSocketListener.stop() to delete the socket file")

        // Prove the poller's loop actually terminated, not just that stop() didn't throw: write
        // a new file after stopping and confirm it survives past what would have been the next
        // poll tick.
        PendingCaptureWriter.write("late capture", directory = pendingDir.toString())
        delay(300)
        assertTrue(
            pendingDir.listDirectoryEntries().any { it.toString().endsWith(".json") },
            "expected the poller's poll loop to have stopped, leaving a post-shutdown file undrained",
        )
    }

    @Test
    fun onGraphManagerReady_should_AttachGraphManagerToControllerPollerAndSocketListener_When_GraphBecomesActive() =
        runBlocking {
            val (graphManager, fileSystem) = newActiveSqlDelightGraphManager()
            val controller = CaptureController(fileSystem)
            val hotkeyListener = JKeymasterHotkeyListener(providerFactory = { AutoFiringFakeProvider() })
            val pendingDir = Files.createTempDirectory("main-attach-pending")
            val poller = PendingCapturePoller(fileSystem, directory = pendingDir.toString())
            val socketPath = Files.createTempDirectory("main-attach-socket").resolve("stelekit.sock").toString()
            val socketListener = CaptureSocketListener(fileSystem, socketPath = socketPath)

            val surfaces = CaptureSurfaces(controller, hotkeyListener, poller, socketListener)

            // The one call under test: a single GraphManager reference fans out to all three.
            surfaces.attachGraphManager(graphManager)

            assertControllerSeesAttachedGraph(controller)
            assertPollerDrainsUsingAttachedGraph(poller, pendingDir)
            assertSocketListenerWritesUsingAttachedGraph(socketListener, socketPath)

            val repoSet = requireNotNull(graphManager.getActiveRepositorySet())
            val journal = repoSet.journalService.ensureTodayJournal()
            val blocks = repoSet.blockRepository.getBlocksForPage(journal.uuid).first().getOrNull().orEmpty()
            assertTrue(blocks.any { it.content == "Buy milk" })
            assertTrue(blocks.any { it.content == "Ship the feature" })

            socketListener.stop()
            poller.stop()
        }

    /** Controller: show() resolves real availability instead of NoActiveGraph. */
    private fun assertControllerSeesAttachedGraph(controller: CaptureController) {
        controller.show()
        val shown = assertIs<CapturePopupState.Shown>(controller.state.value)
        assertTrue(
            shown.captureResult !is CaptureResult.NoActiveGraph,
            "expected the controller to see the attached GraphManager as active",
        )
    }

    /** Poller: a pending file actually drains via the attached GraphManager. */
    private suspend fun assertPollerDrainsUsingAttachedGraph(poller: PendingCapturePoller, pendingDir: java.nio.file.Path) {
        PendingCaptureWriter.write("Buy milk", directory = pendingDir.toString())
        poller.scanOnce()
        assertTrue(
            pendingDir.listDirectoryEntries().none { it.toString().endsWith(".json") },
            "expected the poller to have drained the file using the attached GraphManager",
        )
    }

    /** Socket listener: a real client round-trip is accepted and written. */
    private fun assertSocketListenerWritesUsingAttachedGraph(socketListener: CaptureSocketListener, socketPath: String) {
        socketListener.start()
        val sendResult = CaptureSocketClient.trySend("Ship the feature", socketPath)
        assertTrue(sendResult.delivered, "expected the socket listener to accept the payload using the attached GraphManager")
    }
}
