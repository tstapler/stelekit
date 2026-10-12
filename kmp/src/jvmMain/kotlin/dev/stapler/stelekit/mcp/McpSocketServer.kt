// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.GraphId
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JSON-RPC frame sent to a second simultaneous connection attempt, verbatim per Story 1.2.1's
 * acceptance criteria. Written raw (not via the SDK transport) and the channel closed immediately
 * — this must never queue or hang (research/pitfalls.md §3.4).
 */
private const val BUSY_ERROR_FRAME =
    "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32000," +
        "\"message\":\"Another MCP client session is already connected to this graph\"}}\n"

private val logger = Logger("McpSocketServer")

/**
 * Binds [server] to a Unix-domain socket at [McpSocketPath.forGraph], accepting one launcher-shim
 * connection at a time. A second simultaneous connection is rejected immediately with
 * [BUSY_ERROR_FRAME] and closed — never queued. [mcpClientIdentity] is updated from the accepted
 * session once the MCP `initialize` handshake completes (the only point a `ServerSession`, and
 * therefore the negotiated client name, is reachable — see `McpClientIdentity`'s doc).
 */
class McpSocketServer(
    private val graphId: GraphId,
    private val server: Server,
    private val mcpClientIdentity: McpClientIdentity,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, t -> logger.error("Uncaught error in MCP socket server", t) },
    )
    private val sessionActive = AtomicBoolean(false)

    @Volatile
    private var listenChannel: ServerSocketChannel? = null

    @Volatile
    private var activeConnection: SocketChannel? = null
    private var acceptJob: Job? = null

    @Volatile
    private var stopped = false

    /**
     * Binds the socket. Safe to call after a stale socket file was left by an unclean shutdown —
     * if nothing is actually listening at the existing path, it's unlinked before binding.
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        val path = McpSocketPath.forGraph(graphId)
        unlinkIfStale(path)

        val bound = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            bound.bind(UnixDomainSocketAddress.of(path))
            // stop() may have landed while binding; don't leave a listener nobody owns.
            if (stopped) throw CancellationException("MCP socket server stopped during start")
        } catch (t: Throwable) {
            runCatching { bound.close() }
            if (t is CancellationException || t is IOException && stopped) {
                runCatching { Files.deleteIfExists(path) }
            }
            throw t
        }
        listenChannel = bound
        acceptJob = scope.launch { acceptLoop(bound) }
    }

    /** Closes the listener and removes the socket file. Idempotent — safe to call repeatedly. */
    fun stop() {
        stopped = true
        runCatching { listenChannel?.close() }
        listenChannel = null
        acceptJob?.cancel()
        acceptJob = null
        runCatching { activeConnection?.close() }
        activeConnection = null
        sessionActive.set(false)
        runCatching { Files.deleteIfExists(McpSocketPath.forGraph(graphId)) }
    }

    private fun unlinkIfStale(path: Path) {
        if (!Files.exists(path)) return
        val somethingListening = try {
            SocketChannel.open(UnixDomainSocketAddress.of(path)).use { true }
        } catch (e: IOException) {
            false
        }
        if (!somethingListening) {
            Files.deleteIfExists(path)
        } else {
            logger.warn("MCP socket at $path is already accepting connections for graph ${graphId.value}")
        }
    }

    private suspend fun acceptLoop(serverChannel: ServerSocketChannel) {
        while (true) {
            val connection = try {
                withContext(Dispatchers.IO) { serverChannel.accept() }
            } catch (e: ClosedChannelException) {
                return // stop() closed the listener — normal shutdown, not an error
            } catch (e: IOException) {
                if (!serverChannel.isOpen) return
                logger.error("MCP socket accept failed for graph ${graphId.value}", e)
                continue
            }

            if (!sessionActive.compareAndSet(false, true)) {
                rejectBusy(connection)
                continue
            }

            activeConnection = connection
            startSession(connection)
        }
    }

    private suspend fun startSession(connection: SocketChannel) {
        try {
            val input = Channels.newInputStream(connection).asSource().buffered()
            val output = Channels.newOutputStream(connection).asSink().buffered()
            val transport = StdioServerTransport(input, output)
            val session = server.createSession(transport)
            session.onInitialized { mcpClientIdentity.onSessionInitialized(session) }
            session.onClose { onSessionEnded(connection) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to establish MCP session for graph ${graphId.value}", e)
            onSessionEnded(connection)
            runCatching { connection.close() }
        }
    }

    private fun onSessionEnded(connection: SocketChannel) {
        sessionActive.set(false)
        if (activeConnection === connection) activeConnection = null
    }

    private fun rejectBusy(connection: SocketChannel) {
        try {
            connection.write(ByteBuffer.wrap(BUSY_ERROR_FRAME.toByteArray(StandardCharsets.UTF_8)))
        } catch (e: IOException) {
            logger.warn("Failed to write MCP busy-rejection frame for graph ${graphId.value}", e)
        } finally {
            runCatching { connection.close() }
        }
    }
}
