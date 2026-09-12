// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.desktop

import dev.stapler.stelekit.capture.CaptureSocketListener
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage for `Main.kt`'s headless CLI capture branch (`stelekit --capture-text "..."`),
 * exercised through [runHeadlessCapture] — the function `main()`'s CLI branch calls, extracted
 * from `main()` because `main()` itself launches a real Compose `Window` in its non-CLI branch
 * (see the class doc on `main()`), which this sandbox has no display for and which the CLI
 * branch must never reach anyway (it `return`s before any Compose/OTel/logging setup runs).
 * Calling [runHeadlessCapture] directly proves the exact wiring `main()` uses without going
 * through `main()`'s `args` parsing or its early `return`.
 */
class MainCliCaptureIntegrationTest {

    private class NonVaultFakeFileSystem : FakeFileSystem() {
        override fun fileExists(path: String): Boolean = false
    }

    /** A real, bound [CaptureSocketListener] attached to a live SQLDELIGHT graph. */
    private suspend fun newBoundListener(socketPath: String): CaptureSocketListener {
        val graphManager = GraphManager(
            platformSettings = InMemorySettings(),
            driverFactory = DriverFactory(),
            fileSystem = NonVaultFakeFileSystem(),
            defaultBackend = GraphBackend.IN_MEMORY,
        )
        val graphPath = Files.createTempDirectory("main-cli-capture-test").toString()
        val id = graphManager.addGraph(graphPath)
        graphManager.switchGraph(id)
        graphManager.awaitPendingMigration()

        val fileSystem = PlatformFileSystem.withRoot(graphPath)
        val listener = CaptureSocketListener(fileSystem, socketPath = socketPath)
        listener.attachGraphManager(graphManager)
        listener.start()
        return listener
    }

    @Test
    fun main_should_WritePendingCaptureFileAndExit_When_InvokedWithCaptureTextFlag_WithoutLaunchingComposeWindow() =
        runBlocking {
            val socketDir = Files.createTempDirectory("main-cli-capture-socket")
            val pendingDir = Files.createTempDirectory("main-cli-capture-pending")
            val boundSocketPath = socketDir.resolve("stelekit.sock").toString()

            // Delivered path: a real listener is bound, so runHeadlessCapture must not fall back
            // to writing a pending-capture file.
            val listener = newBoundListener(boundSocketPath)
            try {
                val delivered = runHeadlessCapture("Delivered via socket", boundSocketPath, pendingDir.toString())
                assertTrue(delivered.delivered, "expected the bound listener to accept the payload")
                assertTrue(
                    pendingDir.listDirectoryEntries().isEmpty(),
                    "no pending-capture file should be written on the delivered path",
                )
            } finally {
                listener.stop()
            }

            // Fallback path: nothing is bound at this socket path, so runHeadlessCapture must
            // write a pending-capture file rather than silently dropping the capture. This is the
            // headless "no window, no side effect other than the file" behavior the CLI mode
            // promises — proven here without ever invoking main() or Compose.
            val unboundSocketPath = socketDir.resolve("no-listener.sock").toString()
            val fallback = runHeadlessCapture("Fallback to file", unboundSocketPath, pendingDir.toString())
            assertFalse(fallback.delivered, "expected no listener bound at this path")
            val files = pendingDir.listDirectoryEntries().filter { it.toString().endsWith(".json") }
            assertEquals(1, files.size, "expected exactly one pending-capture file from the fallback path")
        }

    @Test
    fun cliCaptureBranch_should_ReuseSameCaptureId_When_FallingBackToPendingCaptureWriter() {
        val pendingDir = Files.createTempDirectory("main-cli-capture-pending-2")
        val unboundSocketPath = Files.createTempDirectory("main-cli-capture-socket-2")
            .resolve("no-listener.sock").toString()

        val result = runHeadlessCapture("Buy milk", unboundSocketPath, pendingDir.toString())

        assertFalse(result.delivered)
        assertTrue(
            pendingDir.resolve("${result.captureId}.json").exists(),
            "expected PendingCaptureWriter to reuse trySend's captureId as the filename",
        )
    }
}
