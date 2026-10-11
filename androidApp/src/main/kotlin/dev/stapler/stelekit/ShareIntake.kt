// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * Trust boundary for images arriving through the exported share target. Only `content://` streams
 * read through [ContentResolver] are accepted, and they are copied into app-private storage (not the
 * OS-evictable cache); a path is trusted only if it sits inside [imageDir].
 */
internal object ShareIntake {
    private const val IMAGE_DIR = "share-images"
    private const val MAX_IMAGE_BYTES = 25L * 1024 * 1024
    private const val STALE_AFTER_MS = 7L * 24 * 60 * 60 * 1000

    fun imageDir(context: Context): File = File(context.filesDir, IMAGE_DIR)

    /** Copies [uri] into [imageDir]; null for any non-`content` scheme, an unreadable stream, or an oversized image. */
    fun copyImage(context: Context, uri: Uri): String? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        val dir = imageDir(context).apply { mkdirs() }
        pruneStale(dir)
        val out = File.createTempFile("share_", ".jpg", dir)
        return try {
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { copyCapped(input, it) }
            }
            if (copied == true) out.absolutePath else null.also { out.delete() }
        } catch (_: IOException) {
            out.delete()
            null
        } catch (_: SecurityException) {
            out.delete()
            null
        }
    }

    /** True only for an existing path whose canonical form is directly inside [imageDir]. */
    fun isPrivateImage(context: Context, path: String?): Boolean {
        if (path == null) return false
        return try {
            val file = File(path).canonicalFile
            file.parentFile == imageDir(context).canonicalFile
        } catch (_: IOException) {
            false
        }
    }

    private fun copyCapped(input: java.io.InputStream, output: java.io.OutputStream): Boolean {
        val buffer = ByteArray(DEFAULT_BUFFER)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return true
            total += n
            if (total > MAX_IMAGE_BYTES) return false
            output.write(buffer, 0, n)
        }
    }

    private fun pruneStale(dir: File) {
        val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    private const val DEFAULT_BUFFER = 8 * 1024
}
