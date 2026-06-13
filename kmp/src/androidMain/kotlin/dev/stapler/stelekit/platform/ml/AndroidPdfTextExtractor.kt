package dev.stapler.stelekit.platform.ml

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AndroidPdfTextExtractor : PdfTextExtractor {
    override suspend fun extractText(absoluteFilePath: String): Either<DomainError, String> =
        withContext(Dispatchers.IO.limitedParallelism(1)) {
            try {
                val doc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(File(absoluteFilePath))
                val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                val text = stripper.getText(doc)
                doc.close()
                text.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                DomainError.DatabaseError.ReadFailed("Android PDF extraction failed: ${e.message}").left()
            }
        }
}
