// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream

actual object PlatformImageDownscaler : ImageDownscalerPort {
    private const val MAX_OOM_RETRIES = 3

    override fun downscale(source: ByteArray, maxEdges: List<Int>, quality: Int): List<ByteArray?>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        val targets = maxEdges.map { edge -> if (maxOf(width, height) > edge) edge else null }
        val largest = targets.filterNotNull().maxOrNull() ?: return targets.map { null }

        val decoded = decodeSampled(source, inSampleSizeForEdge(width, height, largest)) ?: return null
        try {
            val transform = ExifOrientationReader.transformFor(ExifOrientationReader.read(source))
            return targets.map { edge -> edge?.let { encodeScaled(decoded, width, height, it, transform, quality) } }
        } finally {
            decoded.recycle()
        }
    }

    /** Retries with a doubled sample size on OOM; null when even that fails. */
    private fun decodeSampled(source: ByteArray, initialSample: Int): Bitmap? {
        var sample = initialSample
        repeat(MAX_OOM_RETRIES + 1) {
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                return BitmapFactory.decodeByteArray(source, 0, source.size, opts)
            } catch (_: OutOfMemoryError) {
                sample *= 2
            }
        }
        return null
    }

    private fun encodeScaled(
        decoded: Bitmap,
        fullWidth: Int,
        fullHeight: Int,
        maxEdge: Int,
        transform: OrientationTransform,
        quality: Int,
    ): ByteArray? = try {
        val (tw, th) = capLongestEdge(fullWidth, fullHeight, maxEdge)
        val scaled = if (decoded.width == tw && decoded.height == th) decoded
        else Bitmap.createScaledBitmap(decoded, tw, th, true)
        val oriented = if (transform.isIdentity) scaled else {
            val m = Matrix().apply {
                postRotate(transform.rotateDegrees.toFloat())
                if (transform.flipHorizontal) postScale(-1f, 1f)
            }
            Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, m, true)
        }
        val out = ByteArrayOutputStream()
        oriented.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(0, 100), out)
        if (oriented !== scaled) oriented.recycle()
        if (scaled !== decoded) scaled.recycle()
        out.toByteArray()
    } catch (_: Throwable) {
        null
    }
}
