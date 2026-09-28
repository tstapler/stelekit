// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

/**
 * Composites a packed-ARGB pixel buffer (as returned by
 * [androidx.compose.ui.graphics.ImageBitmap.toPixelMap]'s `buffer`, one `Int` per pixel,
 * `0xAARRGGBB`, straight alpha) onto an opaque black background, producing a flat RGBA
 * [ByteArray] (4 bytes/pixel, alpha always 255) suitable for a Canvas 2D `ImageData`.
 *
 * JPEG has no alpha channel — this flattening step must happen before encode, since relying on
 * the browser's own implicit alpha handling is inconsistent and undocumented across engines
 * (see `project_plans/wasm-jpeg-export/research/pitfalls.md` §4).
 *
 * Assumes [pixels] is tightly packed — one entry per pixel in row-major order, no stride or
 * buffer-offset gaps. True for [androidx.compose.ui.graphics.ImageBitmap.toPixelMap]'s
 * zero-arg default (its only caller today); a cropped/offset `PixelMap` would need re-deriving
 * the index math from its own `stride`/`bufferOffset` first.
 */
internal fun flattenToOpaqueRgba(pixels: IntArray, width: Int, height: Int): ByteArray {
    val out = ByteArray(width * height * 4)
    var outIndex = 0
    for (pixel in pixels) {
        val a = (pixel ushr 24) and 0xFF
        val r = (pixel ushr 16) and 0xFF
        val g = (pixel ushr 8) and 0xFF
        val b = pixel and 0xFF
        out[outIndex++] = ((r * a) / 255).toByte()
        out[outIndex++] = ((g * a) / 255).toByte()
        out[outIndex++] = ((b * a) / 255).toByte()
        out[outIndex++] = 255.toByte()
    }
    return out
}

/**
 * One `Char` per byte value (0–255) — the classic JS "binary string" idiom. Pure Kotlin so it's
 * testable from `commonTest`; wasmJs's `putImageDataFromBinaryString` (`ImageEncoderInterop.kt`)
 * is the only caller today, decoding it JS-side via `charCodeAt` as a single bulk interop call
 * rather than a per-byte Kotlin↔JS round trip.
 */
internal fun ByteArray.toCharPerByteJsString(): String {
    val chars = CharArray(size)
    for (i in indices) {
        chars[i] = (this[i].toInt() and 0xFF).toChar()
    }
    return chars.concatToString()
}
