package dev.stapler.stelekit.platform.ml

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError

data class Label(val text: String, val confidence: Float)

interface ImageLabeler {
    suspend fun labelImage(imageBytes: ByteArray): Either<DomainError, List<Label>>
}
