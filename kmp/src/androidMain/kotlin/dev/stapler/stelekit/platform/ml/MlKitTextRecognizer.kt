package dev.stapler.stelekit.platform.ml

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-device Latin-script OCR through ML Kit; the bundled model is loaded on first use only. */
class MlKitTextRecognizer : TextRecognizer {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(imageBytes: ByteArray): Either<DomainError, String> {
        return try {
            val bitmap = decodeAndResize(imageBytes, maxSize = MAX_DECODE_EDGE_PX)
                ?: return "".right()
            try {
                val result = suspendCancellableCoroutine { cont ->
                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resumeWithException(it) }
                }
                result.text.right()
            } finally {
                bitmap.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DomainError.DatabaseError.ReadFailed("MlKit text recognition failed: ${e.message}").left()
        }
    }

    private companion object {
        /** Matches the OCR tier the plugin hands over, so the recognizer does not shrink it further. */
        const val MAX_DECODE_EDGE_PX = 1600
    }
}
