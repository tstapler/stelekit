package dev.stapler.stelekit.asset.pipeline

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.ml.ImageLabeler

class OcrPlugin(
    private val imageLabeler: ImageLabeler,
    private val fileSystem: FileSystem,
) : AssetPipelinePlugin {
    override val id: String = "ocr"

    override fun canProcess(asset: AssetEntry): Boolean =
        asset.mediaType == AssetMediaType.IMAGE

    override suspend fun processAsset(asset: AssetEntry): Either<DomainError, AssetPipelineResult> {
        return try {
            val bytes = fileSystem.readFileBytes(asset.filePath)
                ?: return DomainError.DatabaseError.ReadFailed("Cannot read ${asset.filePath}").left()
            val result = imageLabeler.labelImage(bytes)
            result.map { labels -> AssetPipelineResult.OcrText(labels.joinToString(" ") { it.text }) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            DomainError.DatabaseError.ReadFailed("OcrPlugin failed: ${e.message}").left()
        }
    }
}
