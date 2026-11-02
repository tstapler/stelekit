package dev.stapler.stelekit.asset.pipeline

import dev.stapler.stelekit.llm.AnthropicMessagesClient
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.image.ImageDownscalerPort
import dev.stapler.stelekit.platform.image.PlatformImageDownscaler
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.ml.NoOpImageLabeler
import dev.stapler.stelekit.platform.ml.NoOpPdfTextExtractor
import dev.stapler.stelekit.platform.ml.NoOpTextRecognizer

/** What the cloud plugin needs; [graphId] is read at each drain so a graph switch is followed. */
class CloudPluginDeps(
    val client: AnthropicMessagesClient,
    val gate: CloudConsentGate,
    val graphId: () -> String,
    val config: CloudEnrichmentConfig = CloudEnrichmentConfig(CloudProvider.CLAUDE, enabled = true),
    val downscaler: ImageDownscalerPort = PlatformImageDownscaler,
    val network: () -> CloudNetwork = { CloudNetwork.Online },
)

/**
 * The on-device plugins to run for [caps], re-derived at each drain start so a settings change
 * takes effect without a restart. Empty unless a plugin's `asset_plugin_<id>_enabled` key is set
 * and the platform actually provides the capability, so an empty list means no drain starts.
 * The cloud plugin is built only through [cloud] and only while its own switch is on; it still sends
 * nothing without a consent token (see [CloudConsentGate]).
 */
fun defaultAssetPlugins(
    caps: PlatformCaps,
    settings: Settings,
    fileSystem: FileSystem,
    cloud: CloudPluginDeps? = null,
): List<AssetPipelinePlugin> = buildList {
    if (PluginEnableKeys.isEnabled(settings, PluginEnableKeys.IMAGE_LABELS) && caps.imageLabeler !is NoOpImageLabeler) {
        add(OnDeviceLabelingPlugin(caps.imageLabeler, fileSystem))
    }
    if (PluginEnableKeys.isEnabled(settings, PluginEnableKeys.PDF_TEXT) && caps.pdfText !is NoOpPdfTextExtractor) {
        add(PdfTextPlugin(caps.pdfText))
    }
    if (PluginEnableKeys.isEnabled(settings, PluginEnableKeys.TEXT_RECOGNITION) && caps.textRecognizer !is NoOpTextRecognizer) {
        add(OcrPlugin(caps.textRecognizer, fileSystem))
    }
    if (cloud != null && PluginEnableKeys.isEnabled(settings, PluginEnableKeys.CLOUD_CLAUDE)) {
        add(
            CloudClaudePlugin(
                config = cloud.config,
                client = cloud.client,
                gate = cloud.gate,
                graphId = cloud.graphId(),
                ledger = EgressLedger(settings, cloud.config.sessionCap, cloud.config.dailyCap, cloud.config.perAssetMaxBytes),
                fileSystem = fileSystem,
                downscaler = cloud.downscaler,
                network = cloud.network,
            ),
        )
    }
}
