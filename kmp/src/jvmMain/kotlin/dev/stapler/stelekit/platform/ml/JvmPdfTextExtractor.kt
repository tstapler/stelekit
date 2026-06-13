package dev.stapler.stelekit.platform.ml

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class JvmPdfTextExtractor : PdfTextExtractor {
    override suspend fun extractText(absoluteFilePath: String): Either<DomainError, String> =
        withContext(Dispatchers.IO) {
            try {
                val doc = org.apache.pdfbox.Loader.loadPDF(File(absoluteFilePath))
                val text = org.apache.pdfbox.text.PDFTextStripper().getText(doc)
                doc.close()
                text.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                DomainError.DatabaseError.ReadFailed("PDF text extraction failed: ${e.message}").left()
            }
        }
}
