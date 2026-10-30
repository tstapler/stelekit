package dev.stapler.stelekit.asset.pipeline

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.image.ImageDownscalerPort
import dev.stapler.stelekit.platform.image.PlatformImageDownscaler
import dev.stapler.stelekit.platform.ml.TextRecognizer

/** Reads printed text out of image assets with the platform [TextRecognizer]. */
class OcrPlugin(
    private val textRecognizer: TextRecognizer,
    private val fileSystem: FileSystem,
    private val downscaler: ImageDownscalerPort = PlatformImageDownscaler,
) : AssetPipelinePlugin {
    override val id: String = "ocr"

    override fun canProcess(asset: AssetEntry): Boolean =
        asset.mediaType == AssetMediaType.IMAGE && !asset.mlProcessed

    override suspend fun processAsset(asset: AssetEntry): Either<DomainError, AssetPipelineResult> {
        return try {
            val bytes = fileSystem.readFileBytes(asset.filePath)
                ?: return DomainError.DatabaseError.ReadFailed("Cannot read ${asset.filePath}").left()
            prepareForModel(bytes, OCR_MAX_EDGE_PX, downscaler)
                .flatMap { textRecognizer.recognize(it) }
                .map { text -> AssetPipelineResult.OcrText(text) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            DomainError.DatabaseError.ReadFailed("OcrPlugin failed: ${e.message}").left()
        }
    }
}
