package dev.stapler.stelekit.asset.pipeline

import arrow.atomic.AtomicInt
import arrow.atomic.value
import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.error.DomainError

class CloudVisionPlugin(private val config: CloudEnrichmentConfig) : AssetPipelinePlugin {
    override val id: String = "cloud_vision"
    private val sessionCount = AtomicInt(0)

    override fun canProcess(asset: AssetEntry): Boolean {
        if (!config.enabled) return false
        if (config.provider != CloudProvider.GOOGLE_VISION) return false
        if (sessionCount.value >= config.sessionCap) return false
        if (asset.mlTagsSource == "CLOUD_VISION" || asset.mlTagsSource == "CLAUDE") return false
        return asset.mediaType == AssetMediaType.IMAGE
    }

    override suspend fun processAsset(asset: AssetEntry): Either<DomainError, AssetPipelineResult> {
        sessionCount.incrementAndGet()
        // Full implementation deferred: requires Ktor HTTP call to Vision API
        return AssetPipelineResult.Labels(emptyList()).right()
    }
}
