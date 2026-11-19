// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform

/** The bounded prefix read of a cached String (D-126). */
object Utf8Prefix {
    /**
     * At most [maxBytes] bytes of [text] as UTF-8. Each character is at least one byte, so the first
     * `min(maxBytes, length)` characters always contain enough; only that slice is encoded, never the whole String.
     * The prefix may end inside a multi-byte character; callers treat it as bytes.
     */
    fun of(text: String, maxBytes: Int): ByteArray {
        if (maxBytes <= 0) return ByteArray(0)
        val encoded = text.substring(0, minOf(maxBytes, text.length)).encodeToByteArray()
        return if (encoded.size > maxBytes) encoded.copyOf(maxBytes) else encoded
    }
}
