package dev.stapler.stelekit.platform.ml

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError

interface PdfTextExtractor {
    suspend fun extractText(absoluteFilePath: String): Either<DomainError, String>
}
