// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.image

/**
 * Pixel budget for any single full-resolution bitmap decode (16 MP = 64 MB as ARGB_8888; a 12 MP 4:3 camera frame is 12.2 MP).
 * A 50 MP phone photo decoded at full size is 200 MB, and rotating it allocates a second copy —
 * together more than a 512 MB Android heap can spare.
 */
const val MAX_DECODE_PIXELS: Long = 16_000_000L

/** Longest edge for views that show a magnified crop (annotation loupe) rather than the whole image. */
const val MAX_LOUPE_EDGE_PX: Int = 2048

private const val MAX_SAMPLE = 1 shl 10

/** Bytes an ARGB_8888 bitmap of [width] x [height] occupies after sub-sampling by [sampleSize]. */
fun decodedBitmapBytes(width: Int, height: Int, sampleSize: Int = 1): Long {
    val s = sampleSize.coerceAtLeast(1)
    return (width / s).toLong().coerceAtLeast(1L) * (height / s).toLong().coerceAtLeast(1L) * 4L
}

/**
 * Smallest power-of-two `inSampleSize` that brings a [width] x [height] image within [maxPixels].
 * Returns 1 when the image already fits or the dimensions are unknown (<= 0).
 */
fun inSampleSizeFor(width: Int, height: Int, maxPixels: Long = MAX_DECODE_PIXELS): Int {
    if (width <= 0 || height <= 0 || maxPixels <= 0L) return 1
    var sample = 1
    while ((width / sample).toLong() * (height / sample).toLong() > maxPixels && sample < MAX_SAMPLE) {
        sample *= 2
    }
    return sample
}

/** Scales [width] x [height] down (never up) so the longer edge is at most [maxEdge], keeping aspect ratio. */
fun capLongestEdge(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (minOf(width, height, maxEdge) <= 0 || longest <= maxEdge) return width to height
    val scale = maxEdge.toDouble() / longest
    return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
}

/** Decode size for the annotation loupe: the canvas magnified by [zoomFactor], capped at [MAX_LOUPE_EDGE_PX]. */
fun loupeDecodeSize(canvasWidth: Int, canvasHeight: Int, zoomFactor: Float): Pair<Int, Int> {
    val (w, h) = capLongestEdge((canvasWidth * zoomFactor).toInt(), (canvasHeight * zoomFactor).toInt(), MAX_LOUPE_EDGE_PX)
    return w.coerceAtLeast(1) to h.coerceAtLeast(1)
}
