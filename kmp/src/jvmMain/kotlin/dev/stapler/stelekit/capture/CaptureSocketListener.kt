// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.PlatformFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.net.BindException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Unix-domain-socket fast path for quick capture (JEP-380): while SteleKit is running, a CLI
 * capture (`stelekit --capture-text "..."`, via [CaptureSocketClient]) is written directly
 * through [CaptureWriter.writeCaptureDirect] instead of waiting for [PendingCapturePoller]'s
 * up-to-5s poll interval. No pending-capture file is ever written for a capture delivered this
 * way — the file path stays a fallback for when no listener is bound.
 */
class CaptureSocketListener(
    private val fileSystem: PlatformFileSystem,
    private val socketPath: String = "${System.getProperty("user.home")}/.stelekit/stelekit.sock",
) {

    @Volatile
    private var graphManager: GraphManager? = null

    @Volatile
    private var serverChannel: ServerSocketChannel? = null

    private val logger = Logger("CaptureSocketListener")

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            logger.error("Capture socket listener failure", e)
        },
    )

    fun attachGraphManager(gm: GraphManager) {
        graphManager = gm
    }

    /**
     * Binds the socket and launches the accept loop. Never throws: a bind failure leaves the
     * listener inactive (fail-open) so hotkey/UI capture and the poller fallback still work.
     */
    fun start() {
        val socketFile = Path.of(socketPath)
        try {
            socketFile.parent?.let { Files.createDirectories(it) }
        } catch (e: Exception) {
            logger.error("Failed to create capture socket directory; socket listener inactive", e)
            return
        }

        val channel = try {
            openAndBind(socketFile)
        } catch (e: BindException) {
            // A prior crash can leave a stale socket file behind, which makes bind() fail as
            // though the address were still in use — delete it and retry once.
            logger.warn("Capture socket bind failed (stale socket file?); deleting and retrying", e)
            try {
                Files.deleteIfExists(socketFile)
                openAndBind(socketFile)
            } catch (e2: Exception) {
                logger.error("Failed to bind capture socket after retry; socket listener inactive", e2)
                return
            }
        } catch (e: Exception) {
            logger.error("Failed to bind capture socket; socket listener inactive", e)
            return
        }

        serverChannel = channel
        tightenPermissions(socketFile)

        scope.launch { acceptLoop(channel) }
    }

    private suspend fun CoroutineScope.acceptLoop(channel: ServerSocketChannel) {
        while (isActive) {
            val connection = try {
                channel.accept()
            } catch (e: ClosedChannelException) {
                return // stop() closed the channel
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Failed to accept capture socket connection", e)
                continue
            }
            handleConnection(connection)
        }
    }

    /** Closes the server channel, cancels the accept loop, and best-effort deletes the socket file. */
    fun stop() {
        try {
            serverChannel?.close()
        } catch (e: Exception) {
            logger.warn("Failed to close capture socket server channel", e)
        }
        scope.cancel()
        try {
            Files.deleteIfExists(Path.of(socketPath))
        } catch (e: Exception) {
            logger.warn("Failed to delete capture socket file", e)
        }
    }

    private fun openAndBind(path: Path): ServerSocketChannel {
        val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        channel.bind(UnixDomainSocketAddress.of(path))
        return channel
    }

    /** Guarded for Windows, which has no POSIX file-attribute view. */
    private fun tightenPermissions(path: Path) {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        } catch (e: Exception) {
            logger.warn("Failed to tighten capture socket file permissions", e)
        }
    }

    /**
     * Handles one connection end-to-end: read → decode → write → respond → close. Wrapped in
     * its own try/catch so one malformed payload can't kill the accept loop.
     */
    private suspend fun handleConnection(channel: SocketChannel) {
        channel.use { conn ->
            try {
                val payload = readPayload(conn)
                if (payload == null) {
                    writeResponse(conn, ERR_RESPONSE)
                    return
                }

                val pending = Json.decodeFromString<PendingCaptureFile>(payload)
                val gm = graphManager
                val response = if (gm == null) {
                    ERR_RESPONSE
                } else {
                    val result = CaptureWriter.writeCaptureDirect(gm, fileSystem, pending.text, pending.captureId)
                    if (result is CaptureResult.Saved) OK_RESPONSE else ERR_RESPONSE
                }
                writeResponse(conn, response)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Failed to handle capture socket connection", e)
                try {
                    writeResponse(conn, ERR_RESPONSE)
                } catch (writeError: CancellationException) {
                    throw writeError
                } catch (writeError: Exception) {
                    logger.warn("Failed to write error response to capture socket client", writeError)
                }
            }
        }
    }

    /**
     * Reads until a newline or EOF, capping at [MAX_PAYLOAD_BYTES] — a payload that exceeds the
     * cap returns `null` (rejected) instead of buffering an unbounded amount of client data.
     */
    private fun readPayload(channel: SocketChannel): String? {
        val buffer = ByteBuffer.allocate(READ_CHUNK_BYTES)
        val output = ByteArrayOutputStream()
        while (true) {
            buffer.clear()
            val n = channel.read(buffer)
            if (n < 0) return null
            buffer.flip()
            while (buffer.hasRemaining()) {
                val byte = buffer.get()
                if (byte == NEWLINE) {
                    return output.toString(Charsets.UTF_8)
                }
                output.write(byte.toInt())
                if (output.size() > MAX_PAYLOAD_BYTES) return null
            }
        }
    }

    private fun writeResponse(channel: SocketChannel, response: ByteArray) {
        val buffer = ByteBuffer.wrap(response)
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    private companion object {
        const val MAX_PAYLOAD_BYTES = 64 * 1024
        const val READ_CHUNK_BYTES = 8192
        const val NEWLINE: Byte = '\n'.code.toByte()
        val OK_RESPONSE = "OK\n".toByteArray(Charsets.UTF_8)
        val ERR_RESPONSE = "ERR\n".toByteArray(Charsets.UTF_8)
    }
}
