// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

/**
 * Raw Canvas 2D `js("...")` bindings for [ImageEncoder]'s wasmJs actual, mirroring
 * `platform/OpfsInterop.kt`'s split between interop primitives and orchestration.
 *
 * Kotlin/Wasm's `js()` boundary has no direct `ByteArray`/`IntArray` marshaling (only
 * primitives, `String`, and `JsAny` cross it) and no zero-copy view into linear memory, so a
 * naive per-pixel or per-byte Kotlin loop calling into JS would cost millions of boundary
 * crossings for a real photo. [putImageDataFromBinaryString] avoids that: the RGBA pixel buffer
 * crosses the boundary as a single `String` (one `Char` per byte value, built by
 * [toBinaryString]) and is unpacked by one native JS loop over `charCodeAt` — a single interop
 * call total. This is *not* base64: an earlier version base64-encoded the buffer on the Kotlin
 * side first, but that cost ~3.3s of Kotlin/Wasm stdlib `Base64.encode` time alone for a
 * 3000×2000 image (measured via `ImageEncoderWasmJsTest`) — a one-to-one char mapping needs no
 * bit-repacking/lookup-table work and is dramatically cheaper for a buffer this large.
 */
internal fun createCanvasElement(width: Int, height: Int): JsAny = js(
    "(function() { const c = document.createElement('canvas'); c.width = width; c.height = height; return c; })()",
)

internal fun get2dContextOrNull(canvas: JsAny): JsAny? = js("canvas.getContext('2d')")

/** One `Char` per byte value (0–255) — see [putImageDataFromBinaryString]'s KDoc for why. */
internal fun ByteArray.toBinaryString(): String {
    val chars = CharArray(size)
    for (i in indices) {
        chars[i] = (this[i].toInt() and 0xFF).toChar()
    }
    return chars.concatToString()
}

internal fun putImageDataFromBinaryString(ctx: JsAny, binary: String, width: Int, height: Int): Unit = js(
    """
    (function() {
        const len = binary.length;
        const arr = new Uint8ClampedArray(len);
        for (let i = 0; i < len; i++) {
            arr[i] = binary.charCodeAt(i);
        }
        const imageData = new ImageData(arr, width, height);
        ctx.putImageData(imageData, 0, 0);
    })()
    """,
)

internal fun canvasToDataUrl(canvas: JsAny, mimeType: String, quality: Double): String =
    js("canvas.toDataURL(mimeType, quality)")

internal fun performanceNowMs(): Double = js("performance.now()")
