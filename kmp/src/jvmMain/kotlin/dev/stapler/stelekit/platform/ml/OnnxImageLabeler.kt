package dev.stapler.stelekit.platform.ml

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.CancellationException

/**
 * ONNX Runtime-based image labeler for JVM desktop.
 * Requires a model file path; falls back to NoOp if no model is available.
 * Model loading is deferred to first use.
 */
class OnnxImageLabeler(private val modelFilePath: String?) : ImageLabeler {
    private val noOp = NoOpImageLabeler()

    override suspend fun labelImage(imageBytes: ByteArray): Either<DomainError, List<Label>> {
        if (modelFilePath == null) return noOp.labelImage(imageBytes)
        return try {
            // Full inference implementation deferred until model is bundled.
            noOp.labelImage(imageBytes)
        } catch (e: CancellationException) { throw e }
        catch (e: Throwable) {
            DomainError.DatabaseError.ReadFailed("OnnxImageLabeler failed: ${e.message}").left()
        }
    }

    companion object {
        fun create(modelPath: String?): ImageLabeler = OnnxImageLabeler(modelPath)
    }
}
