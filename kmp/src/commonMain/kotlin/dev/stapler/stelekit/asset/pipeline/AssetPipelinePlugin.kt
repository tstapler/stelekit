package dev.stapler.stelekit.asset.pipeline

import arrow.core.Either
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.error.DomainError

interface AssetPipelinePlugin {
    val id: String
    fun canProcess(asset: AssetEntry): Boolean
    suspend fun processAsset(asset: AssetEntry): Either<DomainError, AssetPipelineResult>
}

sealed class AssetPipelineResult {
    data class Labels(val labels: List<String>) : AssetPipelineResult()
    data class OcrText(val text: String) : AssetPipelineResult()
    data class CloudDescription(val description: String, val labelsAdded: List<String>) : AssetPipelineResult()
    /**
     * The plugin declined this asset (no consent, a cap, a deleted key): nothing was sent and the
     * asset stays unprocessed. [pause] names the cap that stops the drain, or null for a plain skip.
     */
    data class Skipped(val pause: PauseReason? = null) : AssetPipelineResult()
    data class Combined(
        val labels: List<String>,
        val ocrText: String?,
        val cloudDescription: String?,
    ) : AssetPipelineResult()
}
