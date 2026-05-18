package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.graphics.ImageBitmap

/**
 * iOS JPEG encoder stub.
 *
 * Full implementation deferred to Epic 9 (uses UIImageJPEGRepresentation).
 * Returns an empty [ByteArray] for now so the expect/actual contract is satisfied.
 */
actual object ImageEncoder {
    actual fun encodeToJpeg(bitmap: ImageBitmap, quality: Int): ByteArray = ByteArray(0)
}
