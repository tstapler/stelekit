package dev.stapler.stelekit.platform.ml

import android.graphics.Bitmap
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

/**
 * On-device Latin-script OCR through ML Kit; the bundled model is loaded on first use only.
 * [decode] and [readText] are seams: the defaults are the real decoder and ML Kit client, tests
 * inject fakes to drive the failure and cancellation branches without a model.
 */
class MlKitTextRecognizer internal constructor(
    private val decode: (ByteArray) -> Bitmap? = { decodeAndResize(it, maxSize = MAX_DECODE_EDGE_PX) },
    private val readText: suspend (Bitmap) -> String = MlKitTextClient()::read,
) : TextRecognizer {

    override suspend fun recognize(imageBytes: ByteArray): Either<DomainError, String> {
        return try {
            val bitmap = decode(imageBytes) ?: return "".right()
            try {
                readText(bitmap).right()
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

/** The real ML Kit call; the client (and its model) is created on first [read]. */
internal class MlKitTextClient {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun read(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { cont.resume(it.text) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}
