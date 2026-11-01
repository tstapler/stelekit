package dev.stapler.stelekit.platform.ml

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.stapler.stelekit.platform.image.inSampleSizeFor
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MlKitImageLabeler : ImageLabeler {
    // Lazy: building the client loads the ML Kit model, which must not happen until first use.
    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS) }

    override suspend fun labelImage(imageBytes: ByteArray): Either<DomainError, List<Label>> {
        return try {
            val bitmap = decodeAndResize(imageBytes, maxSize = 512)
                ?: return emptyList<Label>().right()
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val results = suspendCancellableCoroutine { cont ->
                labeler.process(inputImage)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            bitmap.recycle()
            results.map { Label(it.text, it.confidence) }.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DomainError.DatabaseError.ReadFailed("MlKit labeling failed: ${e.message}").left()
        }
    }
}

internal fun decodeAndResize(bytes: ByteArray, maxSize: Int): Bitmap? {
    // Sub-sample at decode: ML Kit only needs maxSize px, and a full-res decode of a 50 MP
    // photo is ~200 MB. Target 2x maxSize so the final scale step keeps quality.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val opts = BitmapFactory.Options().apply {
        inSampleSize = inSampleSizeFor(bounds.outWidth, bounds.outHeight, 4L * maxSize * maxSize)
    }
    val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
    val scale = minOf(maxSize.toFloat() / original.width, maxSize.toFloat() / original.height, 1f)
    return if (scale >= 1f) original
    else {
        val scaled = Bitmap.createScaledBitmap(
            original,
            (original.width * scale).toInt(),
            (original.height * scale).toInt(),
            true,
        )
        original.recycle()
        scaled
    }
}
