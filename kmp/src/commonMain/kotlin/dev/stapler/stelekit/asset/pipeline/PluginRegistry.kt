package dev.stapler.stelekit.asset.pipeline

import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.ml.NoOpImageLabeler
import dev.stapler.stelekit.platform.ml.NoOpPdfTextExtractor
import dev.stapler.stelekit.platform.ml.NoOpTextRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single source of plugin enabled state: the per-plugin `asset_plugin_<id>_enabled` keys
 * (via [PluginEnableKeys]) gated by the master `asset_pipeline_enabled` flag and by what
 * `caps` can actually do on this platform. [enabledCount] and [enabledStates] are what the
 * status bar and Settings > Plugins read; nothing else may read the keys for display.
 */
class PluginRegistry(
    private val settings: Settings? = null,
    private val caps: PlatformCaps = PlatformCaps.NONE,
) {
    @kotlin.concurrent.Volatile
    private var plugins: List<AssetPipelinePlugin> = emptyList()
    val all: List<AssetPipelinePlugin> get() = plugins

    private val _enabledStates = MutableStateFlow(readStates())
    private val _masterEnabled = MutableStateFlow(
        settings?.let { runCatching { it.getBoolean(AssetBuildFlags.PIPELINE_ENABLED_KEY, false) }.getOrDefault(false) } ?: true,
    )
    private val _enabledCount = MutableStateFlow(countEnabled())

    /** Stored switch value per plugin id (text_recognition, image_labels, pdf_text); a read failure counts as off. */
    val enabledStates: StateFlow<Map<String, Boolean>> = _enabledStates.asStateFlow()

    /** Plugins that would actually run now: switch on, capability present, master flag on. */
    val enabledCount: StateFlow<Int> = _enabledCount.asStateFlow()

    /** Null when the platform can run the plugin; otherwise the text the Plugins row shows. */
    fun unavailableReason(pluginId: String): String? = when (pluginId) {
        PluginEnableKeys.TEXT_RECOGNITION -> if (caps.textRecognizer is NoOpTextRecognizer) NOT_AVAILABLE_ON_DESKTOP else null
        PluginEnableKeys.IMAGE_LABELS -> if (caps.imageLabeler is NoOpImageLabeler) MODEL_NOT_INSTALLED else null
        PluginEnableKeys.PDF_TEXT -> if (caps.pdfText is NoOpPdfTextExtractor) NOT_AVAILABLE_ON_DESKTOP else null
        else -> null
    }

    /** Persists the switch; a failing settings write propagates so the UI can snap the switch back. */
    fun setEnabled(pluginId: String, value: Boolean) {
        val store = settings ?: return
        store.putBoolean(PluginEnableKeys.keyFor(pluginId), value)
        refresh()
    }

    /** Mirrors the master `asset_pipeline_enabled` flag so a master-off state reads as zero plugins. */
    fun setMasterEnabled(value: Boolean) {
        _masterEnabled.value = value
        _enabledCount.value = countEnabled()
    }

    /** Re-reads the stored keys; call after anything else may have written them. */
    fun refresh() {
        _enabledStates.value = readStates()
        _enabledCount.value = countEnabled()
    }

    fun register(plugin: AssetPipelinePlugin) {
        plugins = plugins + plugin
    }

    fun replaceAll(newPlugins: List<AssetPipelinePlugin>) {
        plugins = newPlugins.toList()
    }

    fun clear() {
        plugins = emptyList()
    }

    private fun readStates(): Map<String, Boolean> {
        val store = settings ?: return PLUGIN_IDS.associateWith { false }
        return PLUGIN_IDS.associateWith { PluginEnableKeys.isEnabled(store, it) }
    }

    private fun countEnabled(): Int =
        if (!_masterEnabled.value) 0 else _enabledStates.value.count { (id, on) -> on && unavailableReason(id) == null }

    companion object {
        val PLUGIN_IDS: List<String> = listOf(
            PluginEnableKeys.TEXT_RECOGNITION,
            PluginEnableKeys.IMAGE_LABELS,
            PluginEnableKeys.PDF_TEXT,
        )
        const val MODEL_NOT_INSTALLED: String = "model not installed"
        const val NOT_AVAILABLE_ON_DESKTOP: String = "not available on desktop"
    }
}
