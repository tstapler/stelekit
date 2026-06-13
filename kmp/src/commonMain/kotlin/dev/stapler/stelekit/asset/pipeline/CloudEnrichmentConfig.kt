package dev.stapler.stelekit.asset.pipeline

enum class CloudProvider { GOOGLE_VISION, CLAUDE }

data class CloudEnrichmentConfig(
    val provider: CloudProvider,
    val apiKey: String,
    val sessionCap: Int = 20,
    val enabled: Boolean = false,
) {
    override fun toString(): String = "CloudEnrichmentConfig(apiKey=REDACTED)"
}
