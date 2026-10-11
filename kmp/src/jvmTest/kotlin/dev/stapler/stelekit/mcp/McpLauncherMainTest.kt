// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.model.GraphId
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val GRAPH_ID = GraphId("g1")

/**
 * Story 1.2.2: exercises the real `McpLauncherMainKt` entry point as an actual subprocess (not an
 * in-process function call) — spawned on the test JVM's own classpath via `java.class.path`, with
 * `-Duser.home` pointed at a temp dir so it resolves the same socket path the test binds.
 *
 * The byte-proxy test binds a plain raw-echo listener at that path rather than a full
 * `McpSocketServer` — asserting *byte-for-byte* proxying through a live MCP JSON-RPC session
 * isn't meaningful without decoding protocol framing, and byte-proxying is the launcher's only
 * job; `McpSocketServerTest` already covers the MCP-session layer on the other end of this same
 * socket path.
 */
class McpLauncherMainTest {

    @Test
    fun `McpLauncherMain proxies bytes bidirectionally between stdio and the connected socket`() = withTempHome {
        val sockPath = McpSocketPath.forGraph(GRAPH_ID)
        val echoServer = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
            bind(UnixDomainSocketAddress.of(sockPath))
        }
        val serverThread = startEchoOnceThread(echoServer)

        val process = launcherProcess(listOf("--graph-id", GRAPH_ID.value))
        try {
            val sentBytes = "ping-from-stdin".toByteArray(Charsets.UTF_8)
            process.outputStream.write(sentBytes)
            process.outputStream.flush()

            val echoed = readExactly(process.inputStream, sentBytes.size, timeoutMs = 5_000)

            assertEquals(sentBytes.size, echoed.size, "expected the full echoed payload back on stdout")
            assertEquals("ping-from-stdin", String(echoed, Charsets.UTF_8))
        } finally {
            process.destroy()
            process.waitFor(5, TimeUnit.SECONDS)
            runCatching { echoServer.close() }
            serverThread.join(2_000)
        }
    }

    /** Accepts exactly one connection and echoes back whatever single read it receives. */
    private fun startEchoOnceThread(echoServer: ServerSocketChannel): Thread = Thread {
        runCatching {
            echoServer.accept().use { connection -> echoOneRead(connection) }
        }
    }.apply { isDaemon = true; start() }

    private fun echoOneRead(connection: SocketChannel) {
        val buf = ByteBuffer.allocate(4096)
        val n = connection.read(buf)
        if (n <= 0) return
        buf.flip()
        connection.write(buf)
    }

    /** Reads up to [expected] bytes (short of that only if [timeoutMs] elapses or EOF arrives). */
    private fun readExactly(input: java.io.InputStream, expected: Int, timeoutMs: Long): ByteArray {
        val out = ByteArray(expected)
        var read = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (read < expected && System.currentTimeMillis() < deadline) {
            val n = input.read(out, read, expected - read)
            if (n == -1) break
            read += n
        }
        return out.copyOf(read)
    }

    @Test
    fun `McpLauncherMain exits 1 with the graph id named in stderr within 2 seconds when no socket is listening`() =
        withTempHome {
            // No McpSocketPath.forGraph(...) call here on purpose — the directory may exist (it's
            // created as a side effect of path resolution) but nothing binds g1.sock.
            val process = launcherProcess(listOf("--graph-id", GRAPH_ID.value))
            try {
                val exited = process.waitFor(2, TimeUnit.SECONDS)
                assertTrue(exited, "launcher must exit within 2 seconds when the socket doesn't exist")
                assertEquals(1, process.exitValue())

                val stderr = process.errorStream.readBytes().toString(Charsets.UTF_8)
                assertTrue(
                    stderr.contains(GRAPH_ID.value),
                    "stderr should name the graph id '${GRAPH_ID.value}', got: $stderr",
                )
            } finally {
                process.destroy()
            }
        }

    /** Spawns the real `McpLauncherMainKt.main` as a subprocess on this test JVM's own classpath. */
    private fun launcherProcess(args: List<String>): Process {
        val javaHome = java.nio.file.Paths.get(System.getProperty("java.home"))
        val javaExeName = if (Files.exists(javaHome.resolve("bin/java.exe"))) "java.exe" else "java"
        val javaBin = javaHome.resolve("bin").resolve(javaExeName).toString()
        val classpath = System.getProperty("java.class.path")
        val userHome = System.getProperty("user.home")

        val command = buildList {
            add(javaBin)
            add("-Duser.home=$userHome")
            add("-cp")
            add(classpath)
            add("dev.stapler.stelekit.mcp.McpLauncherMainKt")
            addAll(args)
        }
        return ProcessBuilder(command).start()
    }
}
