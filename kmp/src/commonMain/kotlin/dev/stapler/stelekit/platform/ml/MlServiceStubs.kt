package dev.stapler.stelekit.platform.ml

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

class NoOpImageLabeler : ImageLabeler {
    override suspend fun labelImage(imageBytes: ByteArray): Either<DomainError, List<Label>> =
        emptyList<Label>().right()
}

class NoOpPdfTextExtractor : PdfTextExtractor {
    override suspend fun extractText(absoluteFilePath: String): Either<DomainError, String> =
        "".right()
}

/** The desktop default (OD-3: no Tesseract). [defaultAssetPlugins] builds no OCR plugin for it. */
class NoOpTextRecognizer : TextRecognizer {
    override suspend fun recognize(imageBytes: ByteArray): Either<DomainError, String> =
        "".right()
}
