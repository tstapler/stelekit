// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.excalidraw

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits

/**
 * The one input type of the importer for file pick, drag-and-drop and clipboard paste (Story 5.1.1, E5): a byte
 * source with a known `size` and random-access `read`. The scanner reads it in 64 KB chunks, so a 20 MB file is
 * never one `String`.
 */
interface ImportSource {
    val size: Long

    /** Up to [length] bytes starting at [offset]; fewer only at the end of the source. */
    fun read(offset: Long, length: Int): ByteArray

    companion object {
        /** File picker and drag-and-drop: the platform seam hands over bytes (on wasm via `File.arrayBuffer()`). */
        fun ofBytes(bytes: ByteArray): ImportSource = object : ImportSource {
            override val size: Long = bytes.size.toLong()
            override fun read(offset: Long, length: Int): ByteArray {
                val from = offset.toInt().coerceAtMost(bytes.size)
                return bytes.copyOfRange(from, (from + length).coerceAtMost(bytes.size))
            }
        }

        /**
         * Clipboard paste. A cheap first-character check comes first so ordinary text costs nothing: the first
         * non-whitespace character after an optional BOM must be `{`. The string is refused before any copy when
         * its length exceeds `maxImportBytes` (N chars encode to at least N bytes), then UTF-8 encoded once, and
         * the encoded size is checked again because a char can encode to three bytes.
         */
        fun ofText(text: String, limits: SceneLimits = SceneLimits.Default): Either<ImportError, ImportSource> {
            if (!startsLikeJsonObject(text)) return ImportError(ImportReason.NotExcalidraw, "clipboard text is not a JSON object").left()
            if (text.length > limits.maxImportBytes) return tooLarge(limits).left()
            val bytes = text.encodeToByteArray()
            if (bytes.size > limits.maxImportBytes) return tooLarge(limits).left()
            return ofBytes(bytes).right()
        }

        private fun startsLikeJsonObject(text: String): Boolean {
            var i = 0
            if (text.isNotEmpty() && text[0] == BOM_CHAR) i++
            while (i < text.length && text[i] in WHITESPACE) i++
            return i < text.length && text[i] == '{'
        }

        internal fun tooLarge(limits: SceneLimits) =
            ImportError(ImportReason.FileTooLarge, "file is larger than the ${limits.maxImportBytes / BYTES_PER_MB} MB import limit", limits.maxImportBytes)

        private const val BOM_CHAR = '﻿'
        private const val WHITESPACE = " \t\r\n"
        private const val BYTES_PER_MB = 1_048_576L
    }
}
