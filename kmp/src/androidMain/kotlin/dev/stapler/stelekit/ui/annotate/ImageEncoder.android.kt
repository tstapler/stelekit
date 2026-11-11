package dev.stapler.stelekit.ui.annotate

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream

/**
 * Android JPEG encoder using [Bitmap.compress].
 */
actual object ImageEncoder {
    actual fun encodeToJpeg(bitmap: ImageBitmap, quality: Int): ByteArray {
        return try {
            val androidBitmap: Bitmap = bitmap.asAndroidBitmap()
            val out = ByteArrayOutputStream()
            androidBitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(0, 100), out)
            out.toByteArray()
        } catch (e: Throwable) {
            // Throwable: an OutOfMemoryError must yield the empty-array failure contract, not crash.
            ByteArray(0)
        }
    }
}
