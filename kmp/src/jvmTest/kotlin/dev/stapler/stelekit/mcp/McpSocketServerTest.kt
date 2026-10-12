// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.model.GraphId
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.LATEST_PROTOCOL_VERSION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val GRAPH_ID = GraphId("g1")
private const val BUSY_ERROR_CODE = "\"code\":-32000"
private const val BUSY_ERROR_PHRASE = "Another MCP client session is already connected"

class McpSocketServerTest {

    @Test
    fun `McpSocketServer start binds a unix domain socket with a 0700-permission parent directory`() =
        withTempHome {
            runBlocking {
                withSocketServer { _, sockPath ->
                    assertTrue(Files.exists(sockPath), "socket file should exist after start()")
                    val perms = PosixFilePermissions.toString(Files.getPosixFilePermissions(sockPath.parent))
                    assertEquals("rwx------", perms)
                }
            }
        }

    @Test
    fun `McpSocketServer stop removes the socket file and is safe to call twice in a row`() = withTempHome {
        runBlocking {
            val server = testServer()
            val socketServer = McpSocketServer(GRAPH_ID, server, McpClientIdentity())
            try {
                socketServer.start()
                val sockPath = McpSocketPath.forGraph(GRAPH_ID)
                assertTrue(Files.exists(sockPath))

                socketServer.stop()
                assertFalse(Files.exists(sockPath), "socket file should be gone after stop()")

                socketServer.stop() // must not throw the second time
                assertFalse(Files.exists(sockPath))
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun `McpSocketServer rejects a second simultaneous connection with a JSON-RPC -32000 busy error and leaves the first session unaffected`() =
        withTempHome {
            runBlocking {
                withSocketServer { _, sockPath ->
                    val client1 = openClient(sockPath)
                    try {
                        val client2 = openClient(sockPath)
                        val rejection = withTimeout(2_000) { readUntilClosed(client2) }
                        client2.close()

                        assertTrue(rejection.contains(BUSY_ERROR_CODE), "expected -32000 in: $rejection")
                        assertTrue(rejection.contains(BUSY_ERROR_PHRASE), "expected busy message in: $rejection")

                        // The first session's own session loop must be completely unaffected.
                        assertFirstSessionStillWorks(client1)
                    } finally {
                        client1.close()
                    }
                }
            }
        }

    @Test
    fun `McpSocketServer start unlinks a stale socket file left from an unclean shutdown`() = withTempHome {
        runBlocking {
            val sockPath = McpSocketPath.forGraph(GRAPH_ID)
            // Simulate an unclean shutdown: bind, then close without unlinking — exactly what a
            // killed process leaves behind. Files.exists is true but nothing is listening.
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { stale ->
                stale.bind(UnixDomainSocketAddress.of(sockPath))
            }
            assertTrue(Files.exists(sockPath), "precondition: stale socket file left on disk")

            val server = testServer()
            val socketServer = McpSocketServer(GRAPH_ID, server, McpClientIdentity())
            try {
                socketServer.start() // must not throw "address already in use" on the stale file
                assertTrue(Files.exists(sockPath))

                // And it's a real, newly-bound listener — a client can actually connect.
                val client = withContext(Dispatchers.IO) { SocketChannel.open(UnixDomainSocketAddress.of(sockPath)) }
                client.close()
            } finally {
                socketServer.stop()
                server.close()
            }
        }
    }

    private suspend fun withSocketServer(block: suspend (Server, java.nio.file.Path) -> Unit) {
        val server = testServer()
        val socketServer = McpSocketServer(GRAPH_ID, server, McpClientIdentity())
        try {
            socketServer.start()
            block(server, McpSocketPath.forGraph(GRAPH_ID))
        } finally {
            socketServer.stop()
            server.close()
        }
    }
}

private fun testServer(): Server =
    McpAdapter(unopenedGraphManager(), RecordingSuggestionSink()) { "test-client" }.buildServer()

private suspend fun openClient(path: java.nio.file.Path): SocketChannel =
    withContext(Dispatchers.IO) { SocketChannel.open(UnixDomainSocketAddress.of(path)) }

/** Reads until the peer closes the connection (or [timeoutMs] elapses), returning all bytes read. */
private suspend fun readUntilClosed(channel: SocketChannel, timeoutMs: Long = 2_000): String =
    withTimeout(timeoutMs) {
        withContext(Dispatchers.IO) {
            val out = StringBuilder()
            val buf = ByteBuffer.allocate(4096)
            while (true) {
                buf.clear()
                val n = channel.read(buf)
                if (n == -1) break
                out.append(String(buf.array(), 0, n, Charsets.UTF_8))
            }
            out.toString()
        }
    }

private fun writeLine(channel: SocketChannel, json: String) {
    channel.write(ByteBuffer.wrap((json + "\n").toByteArray(Charsets.UTF_8)))
}

/** Reads one newline-delimited JSON-RPC message — matches the SDK's stdio framing convention. */
private suspend fun readLine(channel: SocketChannel, timeoutMs: Long = 5_000): String = withTimeout(timeoutMs) {
    withContext(Dispatchers.IO) {
        val out = StringBuilder()
        val buf = ByteBuffer.allocate(1)
        while (true) {
            buf.clear()
            val n = channel.read(buf)
            if (n == -1) break
            val byte = buf.get(0)
            if (byte == '\n'.code.toByte()) break
            out.append(byte.toInt().toChar())
        }
        out.toString()
    }
}

private suspend fun assertFirstSessionStillWorks(client: SocketChannel) {
    writeLine(
        client,
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$LATEST_PROTOCOL_VERSION","capabilities":{},"clientInfo":{"name":"test-client","version":"1.0"}}}""",
    )
    val initResponse = readLine(client)
    assertTrue(initResponse.contains("\"serverInfo\""), "expected a real InitializeResult, got: $initResponse")

    writeLine(client, """{"jsonrpc":"2.0","method":"notifications/initialized"}""")

    writeLine(client, """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")
    val toolsResponse = readLine(client)
    assertTrue(toolsResponse.contains("\"tools\""), "expected a real ListToolsResult, got: $toolsResponse")
}
