// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.image

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint

/**
 * Turns a decoded bitmap upright for a platform whose decoder ignores EXIF orientation and has no downscaler (iOS and the web).
 * It draws the bitmap through a rotate-then-mirror transform into a new one, so it needs no platform API and no dependency.
 */
object BitmapOrienter {
    private const val HALF = 2f
    private const val QUARTER = 90
    private const val HALF_TURN = 180
    private const val THREE_QUARTER = 270

    /** [bitmap] itself when [transform] is the identity, else a new upright bitmap (width and height swap on a quarter turn). */
    fun orient(bitmap: ImageBitmap, transform: OrientationTransform): ImageBitmap {
        if (transform.isIdentity) return bitmap
        val quarter = transform.rotateDegrees == QUARTER || transform.rotateDegrees == THREE_QUARTER
        val w = if (quarter) bitmap.height else bitmap.width
        val h = if (quarter) bitmap.width else bitmap.height
        val out = ImageBitmap(w, h)
        val canvas = Canvas(out)
        canvas.translate(w / HALF, h / HALF)
        if (transform.flipHorizontal) canvas.scale(-1f, 1f)
        canvas.rotate(transform.rotateDegrees.toFloat())
        canvas.translate(-bitmap.width / HALF, -bitmap.height / HALF)
        canvas.drawImage(bitmap, androidx.compose.ui.geometry.Offset.Zero, Paint())
        return out
    }

    /** Where source pixel ([x], [y]) of a [width] by [height] image lands after [transform]; the pure model of [orient]. */
    fun destinationOf(transform: OrientationTransform, width: Int, height: Int, x: Int, y: Int): Pair<Int, Int> {
        val (rx, ry) = when (transform.rotateDegrees) {
            QUARTER -> (height - 1 - y) to x
            HALF_TURN -> (width - 1 - x) to (height - 1 - y)
            THREE_QUARTER -> y to (width - 1 - x)
            else -> x to y
        }
        val outW = if (transform.rotateDegrees == QUARTER || transform.rotateDegrees == THREE_QUARTER) height else width
        return if (transform.flipHorizontal) (outW - 1 - rx) to ry else rx to ry
    }
}
