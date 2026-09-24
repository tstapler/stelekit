// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap

/**
 * Wasm/JS JPEG encoder using a hidden `<canvas>` element's `toDataURL('image/jpeg', quality)`.
 *
 * Pipeline: [ImageBitmap.toPixelMap] (no JS boundary — Skia backs `ImageBitmap` on wasmJs too) →
 * [flattenToOpaqueRgba] (alpha-flatten onto black, since JPEG has no alpha channel) → a single
 * bulk [putImageDataFromBinaryString] call → [canvasToDataUrl] → [parseJpegDataUrl]. See
 * `ImageEncoderInterop.kt`'s KDoc for why the pixel buffer crosses the JS boundary as one string
 * rather than a per-pixel loop.
 */
actual object ImageEncoder {
    actual fun encodeToJpeg(bitmap: ImageBitmap, quality: Int): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        if (!isValidBitmapSize(width, height)) {
            logEncodeFailure("invalid bitmap size ${width}x$height")
            return ByteArray(0)
        }
        if (exceedsCanvasAreaCeiling(width, height)) {
            logEncodeFailure(
                "${width}x$height exceeds MAX_CANVAS_AREA_PX ($MAX_CANVAS_AREA_PX)",
            )
            return ByteArray(0)
        }

        return try {
            encodeViaCanvas(bitmap, width, height, quality)
        } catch (e: Throwable) {
            logEncodeFailure(e.message ?: "unknown error")
            ByteArray(0)
        }
    }

    private fun encodeViaCanvas(bitmap: ImageBitmap, width: Int, height: Int, quality: Int): ByteArray {
        val pixelMap = bitmap.toPixelMap()
        val rgba = flattenToOpaqueRgba(pixelMap.buffer, width, height)
        val rgbaBinaryString = rgba.toCharPerByteJsString()

        val canvas = createCanvasElement(width, height)
        val ctx = get2dContextOrNull(canvas) ?: run {
            logEncodeFailure("2D canvas context unavailable")
            return ByteArray(0)
        }
        putImageDataFromBinaryString(ctx, rgbaBinaryString, width, height)

        val clampedQuality = quality.coerceIn(0, 100)
        val startMs = performanceNowMs()
        val dataUrl = canvasToDataUrl(canvas, "image/jpeg", clampedQuality / 100.0)
        val elapsedMs = performanceNowMs() - startMs
        println("[SteleKit] wasmJs JPEG encode took ${elapsedMs}ms for ${width}x$height")

        return parseJpegDataUrl(dataUrl) ?: run {
            logEncodeFailure("canvas returned a non-JPEG data URL")
            ByteArray(0)
        }
    }

    private fun logEncodeFailure(reason: String) {
        println("[SteleKit] wasmJs JPEG encode failed: $reason")
    }
}
