package dev.stapler.stelekit.asset.pipeline

import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.CancellationException

/**
 * Per-plugin `asset_plugin_<id>_enabled` settings keys, default false. The master
 * `asset_pipeline_enabled` flag alone never starts a plugin; the Settings switches that write
 * these keys arrive with the full Settings > Plugins screen.
 */
object PluginEnableKeys {
    const val TEXT_RECOGNITION: String = "text_recognition"
    const val IMAGE_LABELS: String = "image_labels"
    const val PDF_TEXT: String = "pdf_text"

    fun keyFor(pluginId: String): String = "asset_plugin_${pluginId}_enabled"

    /** A read failure counts as off. */
    fun isEnabled(settings: Settings, pluginId: String): Boolean = try {
        settings.getBoolean(keyFor(pluginId), false)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        false
    }
}
