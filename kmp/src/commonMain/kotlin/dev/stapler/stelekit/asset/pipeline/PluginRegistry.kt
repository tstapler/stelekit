package dev.stapler.stelekit.asset.pipeline

class PluginRegistry {
    @kotlin.concurrent.Volatile
    private var plugins: List<AssetPipelinePlugin> = emptyList()
    val all: List<AssetPipelinePlugin> get() = plugins

    fun register(plugin: AssetPipelinePlugin) {
        plugins = plugins + plugin
    }

    fun replaceAll(newPlugins: List<AssetPipelinePlugin>) {
        plugins = newPlugins.toList()
    }

    fun clear() {
        plugins = emptyList()
    }
}
