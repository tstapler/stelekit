package dev.stapler.stelekit.asset.pipeline

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.ml.PdfTextExtractor

class PdfTextPlugin(private val pdfTextExtractor: PdfTextExtractor) : AssetPipelinePlugin {
    override val id: String = "pdf_text"

    override fun canProcess(asset: AssetEntry): Boolean =
        asset.mediaType == AssetMediaType.PDF

    override suspend fun processAsset(asset: AssetEntry): Either<DomainError, AssetPipelineResult> {
        return try {
            val result = pdfTextExtractor.extractText(asset.filePath)
            result.map { text -> AssetPipelineResult.OcrText(text) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            DomainError.DatabaseError.ReadFailed("PdfTextPlugin failed: ${e.message}").left()
        }
    }
}
