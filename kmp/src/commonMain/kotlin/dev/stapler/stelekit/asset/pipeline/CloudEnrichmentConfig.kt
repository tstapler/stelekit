package dev.stapler.stelekit.asset.pipeline

import dev.stapler.stelekit.llm.AnthropicModels

enum class CloudProvider { GOOGLE_VISION, CLAUDE }

/** Non-secret cloud plugin settings. The API key is never here: it is read from the credential store per call. */
data class CloudEnrichmentConfig(
    val provider: CloudProvider,
    val sessionCap: Int = EgressLedger.DEFAULT_SESSION_CAP,
    val dailyCap: Int = EgressLedger.DEFAULT_DAILY_CAP,
    val perAssetMaxBytes: Long = EgressLedger.DEFAULT_PER_ASSET_MAX_BYTES,
    /** Longest image edge uploaded, in pixels. */
    val maxEdgePx: Int = CLOUD_MAX_EDGE_PX,
    val model: String = AnthropicModels.DEFAULT,
    val maxTokens: Int = CLOUD_MAX_TOKENS,
    val enabled: Boolean = false,
) {
    companion object {
        const val CLOUD_MAX_EDGE_PX = 1568
        const val CLOUD_MAX_TOKENS = 300
    }
}
