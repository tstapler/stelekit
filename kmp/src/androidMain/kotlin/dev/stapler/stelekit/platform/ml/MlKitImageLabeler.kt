package dev.stapler.stelekit.platform.ml

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    private val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)

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

    private fun decodeAndResize(bytes: ByteArray, maxSize: Int): Bitmap? {
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
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
}
