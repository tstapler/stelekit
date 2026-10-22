// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.image

/** Decodes an image once, within the [MAX_DECODE_PIXELS] budget, and produces smaller JPEG copies. */
interface ImageDownscalerPort {
    /**
     * For each of [maxEdges] returns a JPEG whose longer edge is at most that value (EXIF
     * orientation applied), or null when the source is already that small or that copy failed.
     * Returns null overall when this platform cannot decode. Must not throw for OOM or bad input.
     */
    fun downscale(source: ByteArray, maxEdges: List<Int>, quality: Int = DERIVATIVE_JPEG_QUALITY): List<ByteArray?>?
}

/** Android and JVM decode; iOS and wasmJs return null (callers fall back to the original). */
expect object PlatformImageDownscaler : ImageDownscalerPort
