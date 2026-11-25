// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.render

import dev.stapler.stelekit.sketch.v2.scene.ImageCrop
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A source rectangle in the pixels of a decoded bitmap. */
data class BitmapSourceRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Maps an [ImageCrop], stored in the pixels of the original photo, onto the pixels of the bitmap actually held: a photo larger than
 * the display cap is decoded smaller, so the crop is scaled by `bitmap / natural` on each axis. Without a crop the whole bitmap.
 * The result is clamped inside the bitmap and is at least one pixel wide and high.
 */
object CropSourceRect {
    fun map(crop: ImageCrop?, bitmapWidth: Int, bitmapHeight: Int): BitmapSourceRect {
        if (crop == null) return BitmapSourceRect(0, 0, bitmapWidth, bitmapHeight)
        val sx = if (crop.naturalWidth > 0.0) bitmapWidth / crop.naturalWidth else 1.0
        val sy = if (crop.naturalHeight > 0.0) bitmapHeight / crop.naturalHeight else 1.0
        val left = floor(crop.x * sx).toInt().coerceIn(0, max(bitmapWidth - 1, 0))
        val top = floor(crop.y * sy).toInt().coerceIn(0, max(bitmapHeight - 1, 0))
        val right = min(bitmapWidth, max(left + 1, ((crop.x + crop.width) * sx).roundToInt()))
        val bottom = min(bitmapHeight, max(top + 1, ((crop.y + crop.height) * sy).roundToInt()))
        return BitmapSourceRect(left, top, right - left, bottom - top)
    }
}
