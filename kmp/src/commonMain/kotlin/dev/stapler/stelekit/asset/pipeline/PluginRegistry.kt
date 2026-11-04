package dev.stapler.stelekit.asset.pipeline

import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.ml.NoOpImageLabeler
import dev.stapler.stelekit.platform.ml.NoOpPdfTextExtractor
import dev.stapler.stelekit.platform.ml.NoOpTextRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class PluginSettingsLoad { Loading, Loaded, Failed }

/**
 * The single source of plugin enabled state: the per-plugin `asset_plugin_<id>_enabled` keys
 * (via [PluginEnableKeys]) gated by the master `asset_pipeline_enabled` flag and by what
 * `caps` can actually do on this platform. [enabledCount] and [enabledStates] are what the
 * status bar and Settings > Plugins read; nothing else may read the keys for display.
 */
class PluginRegistry(
    private val settings: Settings? = null,
    private val caps: PlatformCaps = PlatformCaps.NONE,
    /** Whether cloud consent is granted for the open graph right now; the cloud plugin counts only while it is. */
    private val cloudConsentGranted: () -> Boolean = { false },
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

    private val _loadState = MutableStateFlow(if (settings == null) PluginSettingsLoad.Loaded else PluginSettingsLoad.Loading)

    /** Whether the stored switches have been read; the Plugins page shows "Loading..." until [load] completes. */
    val loadState: StateFlow<PluginSettingsLoad> = _loadState.asStateFlow()

    /** Reads the stored switches off the main thread; a read failure shows every row off with a retry. */
    suspend fun load() {
        val store = settings ?: return
        _loadState.value = PluginSettingsLoad.Loading
        _loadState.value = withContext(Dispatchers.Default) {
            try {
                _enabledStates.value = PLUGIN_IDS.associateWith { store.getBoolean(PluginEnableKeys.keyFor(it), false) }
                _enabledCount.value = countEnabled()
                PluginSettingsLoad.Loaded
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                _enabledStates.value = PLUGIN_IDS.associateWith { false }
                _enabledCount.value = countEnabled()
                PluginSettingsLoad.Failed
            }
        }
    }

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

    /** The stored cloud switch (consent is separate). */
    fun cloudSwitchOn(): Boolean = settings?.let { PluginEnableKeys.isEnabled(it, PluginEnableKeys.CLOUD_CLAUDE) } ?: false

    /** Re-evaluates the count after cloud consent or the cloud switch changed. */
    fun onCloudStateChanged() {
        _enabledCount.value = countEnabled()
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
        if (!_masterEnabled.value) 0 else _enabledStates.value.count { (id, on) -> on && unavailableReason(id) == null } + cloudCount()

    private fun cloudCount(): Int {
        val store = settings ?: return 0
        return if (PluginEnableKeys.isEnabled(store, PluginEnableKeys.CLOUD_CLAUDE) && cloudConsentGranted()) 1 else 0
    }

    companion object {
        val PLUGIN_IDS: List<String> = listOf(
            PluginEnableKeys.TEXT_RECOGNITION,
            PluginEnableKeys.IMAGE_LABELS,
            PluginEnableKeys.PDF_TEXT,
        )
        /** The Settings id for a pipeline plugin id (`ocr` is the `text_recognition` switch); null for cloud plugins. */
        fun settingsIdFor(pluginId: String): String? = when (pluginId) {
            "ocr" -> PluginEnableKeys.TEXT_RECOGNITION
            "on_device_labeling" -> PluginEnableKeys.IMAGE_LABELS
            "pdf_text" -> PluginEnableKeys.PDF_TEXT
            else -> null
        }

        fun displayName(settingsId: String): String = when (settingsId) {
            PluginEnableKeys.TEXT_RECOGNITION -> "text recognition"
            PluginEnableKeys.IMAGE_LABELS -> "image labels"
            else -> "PDF text"
        }

        const val MODEL_NOT_INSTALLED: String = "model not installed"
        const val NOT_AVAILABLE_ON_DESKTOP: String = "not available on desktop"
    }
}
