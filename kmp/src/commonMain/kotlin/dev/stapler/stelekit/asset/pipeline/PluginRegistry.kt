package dev.stapler.stelekit.asset.pipeline

class PluginRegistry {
    private val _plugins = mutableListOf<AssetPipelinePlugin>()
    val all: List<AssetPipelinePlugin> get() = _plugins.toList()

    fun register(plugin: AssetPipelinePlugin) {
        _plugins.add(plugin)
    }

    fun clear() {
        _plugins.clear()
    }
}
