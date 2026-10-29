package dev.stapler.stelekit.asset.pipeline

import dev.stapler.stelekit.platform.ml.ImageLabeler
import dev.stapler.stelekit.platform.ml.NoOpImageLabeler
import dev.stapler.stelekit.platform.ml.NoOpPdfTextExtractor
import dev.stapler.stelekit.platform.ml.PdfTextExtractor

/**
 * What this platform can do on-device. Every host names a value explicitly (no default on
 * `StelekitAppPlatformIntegrations.platformCaps`); a host with nothing to offer passes [NONE].
 * A `NoOp*` capability means "not available here": [defaultAssetPlugins] builds no plugin for it.
 */
data class PlatformCaps(
    val imageLabeler: ImageLabeler,
    val pdfText: PdfTextExtractor,
) {
    companion object {
        val NONE = PlatformCaps(NoOpImageLabeler(), NoOpPdfTextExtractor())
    }
}
