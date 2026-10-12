// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.model.GraphId
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * Standalone stdio↔socket proxy spawned by MCP hosts (Claude Desktop/Code) per their stdio
 * subprocess convention. Does **not** speak MCP JSON-RPC itself — it only forwards bytes between
 * its own stdin/stdout and the running desktop app's local socket for `--graph-id`. Deliberately
 * has no `GraphManager`/Compose/SQLDelight/`kotlin-sdk-server` dependency: this binary ships
 * separately from the full app and must stay minimal per ADR-001.
 */
fun main(args: Array<String>) {
    exitProcess(runLauncher(args))
}

/**
 * Core logic with injectable streams so tests don't have to hijack the real process stdio.
 * Returns an exit code instead of calling `exitProcess` directly.
 */
internal fun runLauncher(
    args: Array<String>,
    stdin: InputStream = System.`in`,
    stdout: OutputStream = System.out,
    stderr: PrintStream = System.err,
): Int {
    val graphId = parseGraphId(args)
    if (graphId == null) {
        stderr.println("McpLauncherMain: missing --graph-id (or STELEKIT_MCP_GRAPH_ID)")
        return 1
    }

    val path = McpSocketPath.forGraph(graphId)
    val channel = try {
        SocketChannel.open(UnixDomainSocketAddress.of(path))
    } catch (e: IOException) {
        stderr.println("SteleKit is not running or MCP is not enabled for graph ${graphId.value}")
        return 1
    }

    try {
        proxyUntilEitherSideCloses(channel, stdin, stdout)
    } finally {
        runCatching { channel.close() }
    }
    return 0
}

/**
 * Copies bytes in both directions until either side closes, then returns — closing [channel]
 * unblocks whichever direction is still copying. Two threads (not coroutines): this binary has
 * no coroutines-core dependency to keep its footprint minimal (Task 1.2.2e).
 */
private fun proxyUntilEitherSideCloses(channel: SocketChannel, stdin: InputStream, stdout: OutputStream) {
    val socketIn = Channels.newInputStream(channel)
    val socketOut = Channels.newOutputStream(channel)
    val oneSideDone = CountDownLatch(1)

    val stdinToSocket = Thread({
        copyUntilEof(stdin, socketOut)
        oneSideDone.countDown()
    }, "mcp-launcher-stdin-to-socket").apply { isDaemon = true }

    val socketToStdout = Thread({
        copyUntilEof(socketIn, stdout)
        oneSideDone.countDown()
    }, "mcp-launcher-socket-to-stdout").apply { isDaemon = true }

    stdinToSocket.start()
    socketToStdout.start()
    oneSideDone.await()
}

private fun copyUntilEof(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(8192)
    try {
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
            output.flush()
        }
    } catch (e: IOException) {
        // Peer closed mid-copy — normal end of proxying, not a launcher bug.
    }
}

private fun parseGraphId(args: Array<String>): GraphId? {
    val flagIndex = args.indexOf("--graph-id")
    val fromArgs = if (flagIndex >= 0 && flagIndex + 1 < args.size) args[flagIndex + 1] else null
    val value = fromArgs ?: System.getenv("STELEKIT_MCP_GRAPH_ID")
    return value?.takeIf { it.isNotBlank() }?.let { GraphId(it) }
}
