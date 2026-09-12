// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Clock

/**
 * Client side of [CaptureSocketListener]'s Unix-domain-socket fast path: the headless CLI
 * capture path (`stelekit --capture-text "..."`) tries this first so a capture reaches the
 * running app's journal immediately instead of waiting on [PendingCapturePoller]'s poll
 * interval. [PendingCaptureFile] is the wire format shared with the file-based fallback so a
 * lost-ack retry (`delivered == false`) reuses the same `captureId` — [CaptureWriter]'s
 * idempotent-uuid write means a capture the listener actually applied before the ack was lost
 * resolves to a single block, not a duplicate, when [PendingCaptureWriter] replays it later.
 */
object CaptureSocketClient {

    data class CaptureSendResult(val delivered: Boolean, val captureId: String)

    private val logger = Logger("CaptureSocketClient")

    private const val CONNECT_TIMEOUT_MS = 200L
    private const val OK_RESPONSE = "OK\n"

    /**
     * Generates [CaptureSendResult.captureId] up front — before attempting the connection — so
     * callers get the same id back whether delivery succeeds or fails, and can safely write a
     * [PendingCaptureWriter] fallback with it on failure.
     */
    fun trySend(
        text: String,
        socketPath: String = CaptureSocketPath.path(),
    ): CaptureSendResult {
        val captureId = UuidGenerator.generateV7()
        val pending = PendingCaptureFile(
            captureId = captureId,
            text = text,
            capturedAt = Clock.System.now().toString(),
        )
        val payload = (Json.encodeToString(pending) + "\n").toByteArray(Charsets.UTF_8)

        val delivered = try {
            sendWithTimeout(socketPath, payload)
        } catch (e: Exception) {
            logger.warn("Capture socket delivery failed; caller should fall back to file", e)
            false
        }
        return CaptureSendResult(delivered, captureId)
    }

    /**
     * Blocking NIO channels have no built-in connect/read timeout, so the send runs on a
     * throwaway daemon thread and is bounded with [java.util.concurrent.Future.get] — on
     * timeout the thread is interrupted, which closes the (interruptible) [SocketChannel] per
     * its `InterruptibleChannel` contract.
     */
    private fun sendWithTimeout(socketPath: String, payload: ByteArray): Boolean {
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "CaptureSocketClient").apply { isDaemon = true }
        }
        return try {
            executor.submit<Boolean> { sendBlocking(socketPath, payload) }
                .get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            false
        } finally {
            executor.shutdownNow()
        }
    }

    private fun sendBlocking(socketPath: String, payload: ByteArray): Boolean {
        SocketChannel.open(UnixDomainSocketAddress.of(Path.of(socketPath))).use { channel ->
            channel.write(ByteBuffer.wrap(payload))
            channel.shutdownOutput()
            return readResponse(channel) == OK_RESPONSE
        }
    }

    private fun readResponse(channel: SocketChannel): String {
        val buffer = ByteBuffer.allocate(64)
        val response = StringBuilder()
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
