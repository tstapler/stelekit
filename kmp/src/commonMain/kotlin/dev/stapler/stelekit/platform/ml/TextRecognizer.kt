package dev.stapler.stelekit.platform.ml

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError

/** Reads printed text out of an image. Callers pass an already-downscaled decode, never the original. */
interface TextRecognizer {
    suspend fun recognize(imageBytes: ByteArray): Either<DomainError, String>
}
