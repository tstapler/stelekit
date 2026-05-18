package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Platform-specific JPEG encoder.
 *
 * Each platform provides its own `actual` implementation:
 * - androidMain: [android.graphics.Bitmap.compress]
 * - jvmMain: [javax.imageio.ImageIO]
 * - iosMain: stub returning empty [ByteArray] (full impl deferred to Epic 9)
 */
expect object ImageEncoder {
    /**
     * Encode [bitmap] to a JPEG [ByteArray] at [quality] (0–100, default 90).
     *
     * @return encoded bytes, or an empty array on failure.
     */
    fun encodeToJpeg(bitmap: ImageBitmap, quality: Int = 90): ByteArray
}
