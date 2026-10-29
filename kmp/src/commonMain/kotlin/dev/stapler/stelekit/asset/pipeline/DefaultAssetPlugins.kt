package dev.stapler.stelekit.asset.pipeline

import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.ml.NoOpImageLabeler
import dev.stapler.stelekit.platform.ml.NoOpPdfTextExtractor

/**
 * The on-device plugins to run for [caps], re-derived at each drain start so a settings change
 * takes effect without a restart. Empty unless a plugin's `asset_plugin_<id>_enabled` key is set
 * and the platform actually provides the capability, so an empty list means no drain starts.
 * Never constructs a cloud plugin.
 */
fun defaultAssetPlugins(
    caps: PlatformCaps,
    settings: Settings,
    fileSystem: FileSystem,
): List<AssetPipelinePlugin> = buildList {
    if (PluginEnableKeys.isEnabled(settings, PluginEnableKeys.IMAGE_LABELS) && caps.imageLabeler !is NoOpImageLabeler) {
        add(OnDeviceLabelingPlugin(caps.imageLabeler, fileSystem))
    }
    if (PluginEnableKeys.isEnabled(settings, PluginEnableKeys.PDF_TEXT) && caps.pdfText !is NoOpPdfTextExtractor) {
        add(PdfTextPlugin(caps.pdfText))
    }
}
