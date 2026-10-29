// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.download

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResumableDownloadTest {
    private val dir = createTempDirectory("stelekit_resumable_").toFile()
    private val payload = Random(7).nextBytes(500_000)
    private val ranges = mutableListOf<String?>()
    private var honourRange = true
    private var truncateAfter: Int? = null

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/m") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            ranges += range
            val start = if (honourRange && range != null) range.removePrefix("bytes=").removeSuffix("-").toInt() else 0
            val body = payload.copyOfRange(start, payload.size)
            if (start > 0) {
                ex.responseHeaders.add("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
                ex.sendResponseHeaders(206, body.size.toLong())
            } else {
                ex.sendResponseHeaders(200, body.size.toLong())
            }
            ex.responseBody.use { out ->
                val cut = truncateAfter
                if (cut != null) out.write(body, 0, cut) else out.write(body)
            }
        }
        start()
    }
    private val url get() = "http://127.0.0.1:${server.address.port}/m"

    @AfterTest
    fun cleanup() {
        server.stop(0)
        dir.deleteRecursively()
    }

    @Test
    fun `downloads the whole body and reports progress up to the total`() = runBlocking {
        val part = File(dir, "m.part")
        var last = 0L
        ResumableDownload.download(url, part) { done, total -> last = done; assertEquals(payload.size.toLong(), total) }
        assertContentEquals(payload, part.readBytes())
        assertEquals(payload.size.toLong(), last)
    }

    @Test
    fun `resumes from an existing partial file with a Range request`() = runBlocking {
        val part = File(dir, "m.part").also { it.writeBytes(payload.copyOf(123_456)) }
        ResumableDownload.download(url, part)
        assertEquals(listOf<String?>("bytes=123456-"), ranges)
        assertContentEquals(payload, part.readBytes())
    }

    @Test
    fun `restarts cleanly when the server ignores Range`() = runBlocking {
        honourRange = false
        val part = File(dir, "m.part").also { it.writeBytes(payload.copyOf(1_000)) }
        ResumableDownload.download(url, part)
        assertContentEquals(payload, part.readBytes())
    }

    @Test
    fun `a truncated body fails but keeps the partial file for the retry to resume`() {
        truncateAfter = 100_000
        val part = File(dir, "m.part")
        assertFailsWith<IOException> { runBlocking { ResumableDownload.download(url, part) } }
        assertTrue(part.length() in 1 until payload.size, "partial progress must be kept")
    }
}
