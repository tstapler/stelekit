package dev.stapler.stelekit.platform.ml

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

/** iOS PDFKit text extractor — stub until PDFDocument Kotlin/Native bindings are wired. */
class IosPdfTextExtractor : PdfTextExtractor {
    override suspend fun extractText(absoluteFilePath: String): Either<DomainError, String> =
        "".right()
}
