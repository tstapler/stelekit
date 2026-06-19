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
    data class Combined(
        val labels: List<String>,
        val ocrText: String?,
        val cloudDescription: String?,
    ) : AssetPipelineResult()
}
