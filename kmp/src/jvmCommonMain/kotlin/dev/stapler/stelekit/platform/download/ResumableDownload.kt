// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Streams [url] into [part], resuming from whatever [part] already holds (HTTP `Range`). The caller owns the
 * `.part` file's lifetime: it survives failures so a retried background job continues instead of restarting,
 * and the caller moves it into place once [download] returns. Never buffers the body in memory.
 */
object ResumableDownload {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000 // a stalled socket fails the attempt; WorkManager's retry resumes it
    private const val BUFFER_BYTES = 64 * 1024

    /**
     * @param onProgress bytes on disk so far and the full size (-1 when the server did not say), throttled by the caller.
     * @throws IOException on HTTP errors, stalls and truncated bodies; [part] is kept (or reset when the server restarted the body).
     */
    suspend fun download(url: String, part: File, onProgress: suspend (done: Long, total: Long) -> Unit = { _, _ -> }) {
        part.parentFile?.mkdirs()
        val resumeFrom = if (part.isFile) part.length() else 0L
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        try {
            val status = connection.responseCode
            val append: Boolean
            val total: Long
            when (status) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    append = true
                    total = connection.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
                }
                HttpURLConnection.HTTP_OK -> { // no resume support (or a fresh start): the body is the whole file
                    append = false
                    total = connection.contentLengthLong
                }
                416 -> { // our partial no longer matches the remote file
                    part.delete()
                    throw IOException("Resume rejected (HTTP 416); restarting")
                }
                else -> throw IOException("HTTP $status")
            }
            var done = if (append) resumeFrom else 0L
            connection.inputStream.use { input ->
                java.io.FileOutputStream(part, append).use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (total > 0 && part.length() != total) throw IOException("Truncated download: ${part.length()} of $total bytes")
        } catch (e: CancellationException) {
            throw e
        } finally {
            connection.disconnect()
        }
    }
}
