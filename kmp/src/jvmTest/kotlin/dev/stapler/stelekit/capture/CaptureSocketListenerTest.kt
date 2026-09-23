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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for [CaptureSocketListener] — the Unix-domain-socket fast path for quick capture.
 * Uses a real bound socket (temp-dir path) and a real `IN_MEMORY`-backed [GraphManager] (same
 * construction pattern as `PendingCapturePollerTest`) rather than mocking [CaptureWriter],
 * which is an `object`.
 */
class CaptureSocketListenerTest {

    /**
     * [FakeFileSystem.fileExists] always returns `true`, which GraphManager.addGraph reads as
     * "vault marker present" and marks the graph paranoid-mode-locked (see the same override
     * in `CaptureWriterTest`/`PendingCapturePollerTest`). Override it so these tests get a
     * normal, writable graph.
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
        val graphPath = Files.createTempDirectory("capture-socket-listener-test").toString()
        val id = graphManager.addGraph(graphPath)
        graphManager.switchGraph(id)
        graphManager.awaitPendingMigration()

        // CaptureWriter's GraphWriter.savePage() enforces a security whitelist on the real
        // PlatformFileSystem — registerGraphRoot() must match the graph path or every save
        // silently resolves to Failed (the DB write still lands, but the file write doesn't).
        return ActiveSetup(graphManager, PlatformFileSystem.withRoot(graphPath))
    }

    private suspend fun blocksWithContent(graphManager: GraphManager, content: String): List<*> {
        val repoSet = requireNotNull(graphManager.getActiveRepositorySet())
        val journal = repoSet.journalService.ensureTodayJournal()
        val blocks = repoSet.blockRepository.getBlocksForPage(journal.uuid).first().getOrNull().orEmpty()
        return blocks.filter { it.content == content }
    }

    private fun newSocketPath(): Path =
        Files.createTempDirectory("capture-socket-listener-test-sock").resolve("stelekit.sock")

    /** Sends [payload] (without a trailing newline — callers add one when they want one) and reads the response. */
    private fun sendAndReceive(socketPath: Path, payload: ByteArray): String {
        SocketChannel.open(UnixDomainSocketAddress.of(socketPath)).use { channel ->
            channel.write(ByteBuffer.wrap(payload))
            channel.shutdownOutput()

            val response = StringBuilder()
            val buffer = ByteBuffer.allocate(4096)
            while (true) {
                buffer.clear()
                val n = channel.read(buffer)
                if (n < 0) break
                buffer.flip()
                while (buffer.hasRemaining()) response.append(buffer.get().toInt().toChar())
            }
            return response.toString()
        }
    }

    @Test
    fun captureSocketListener_should_WriteBlockDirectly_When_ValidPendingCaptureFileReceivedOverSocket() = runBlocking {
        val (graphManager, fileSystem) = newActiveSetup()
        val socketPath = newSocketPath()
        val listener = CaptureSocketListener(fileSystem, socketPath.toString())
        listener.attachGraphManager(graphManager)
        listener.start()

        try {
            val pending = PendingCaptureFile(
                captureId = UuidGenerator.generateV7(),
                text = "Buy milk",
                capturedAt = Clock.System.now().toString(),
            )
            val payload = (Json.encodeToString(pending) + "\n").toByteArray(Charsets.UTF_8)

            val response = sendAndReceive(socketPath, payload)

            assertEquals("OK\n", response)
            assertEquals(1, blocksWithContent(graphManager, "Buy milk").size)
        } finally {
            listener.stop()
        }
    }

    @Test
    fun captureSocketListener_should_CloseConnectionWithError_When_PayloadExceeds64Kb() = runBlocking {
        val (graphManager, fileSystem) = newActiveSetup()
        val socketPath = newSocketPath()
        val listener = CaptureSocketListener(fileSystem, socketPath.toString())
        listener.attachGraphManager(graphManager)
        listener.start()

        try {
            // No trailing newline: the listener must reject purely on the 64KB size cap, not
            // wait for a delimiter that never comes.
            val oversized = ByteArray(70 * 1024) { 'a'.code.toByte() }

            val response = sendAndReceive(socketPath, oversized)

            assertEquals("ERR\n", response)
        } finally {
            listener.stop()
        }
    }

    @Test
    fun captureSocketListener_should_DeleteStaleSocketFileAndRebind_When_BindFailsWithAddressAlreadyInUse() = runBlocking {
        val (graphManager, fileSystem) = newActiveSetup()
        val socketPath = newSocketPath()
        // Simulate a stale socket file left behind by a prior crash: a plain file at the
        // socket path is enough to make bind() fail with "address already in use", since the
        // path is already occupied on disk.
        Files.writeString(socketPath, "stale")
        assertTrue(socketPath.exists())

        val listener = CaptureSocketListener(fileSystem, socketPath.toString())
        listener.attachGraphManager(graphManager)
        listener.start()

        try {
            val pending = PendingCaptureFile(
                captureId = UuidGenerator.generateV7(),
                text = "Ship the feature",
                capturedAt = Clock.System.now().toString(),
            )
            val payload = (Json.encodeToString(pending) + "\n").toByteArray(Charsets.UTF_8)

            val response = sendAndReceive(socketPath, payload)

            assertEquals("OK\n", response, "expected rebind after deleting the stale socket file to succeed")
            assertEquals(1, blocksWithContent(graphManager, "Ship the feature").size)
        } finally {
            listener.stop()
        }
    }

    @Test
    fun captureSocketListener_should_SetSocketFilePermissionsTo0600_When_PosixFileAttributesSupported() = runBlocking {
        val supportsPosix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
        if (!supportsPosix) return@runBlocking

        val (graphManager, fileSystem) = newActiveSetup()
        val socketPath = newSocketPath()
        val listener = CaptureSocketListener(fileSystem, socketPath.toString())
        listener.attachGraphManager(graphManager)
        listener.start()

        try {
            val permissions = Files.getPosixFilePermissions(socketPath)
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), permissions)
        } finally {
            listener.stop()
        }
    }
}
