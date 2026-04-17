package dev.stapler.stelekit.platform

import dev.stapler.stelekit.logging.Logger

/**
 * Core interface for Logseq plugins.
 * Defines the basic lifecycle and metadata for a plugin.
 */
interface Plugin {
    val id: String
    val name: String
    val version: String

    /**
     * Called when the plugin is enabled.
     * Use this to initialize resources, register commands, and setup UI.
     */
    suspend fun onEnable()

    /**
     * Called when the plugin is disabled or uninstalled.
     * Use this to cleanup resources, unregister commands, and remove UI elements.
     */
    suspend fun onDisable()
}

/**
 * Manages the lifecycle of plugins and provides access to Logseq APIs.
 */
class PluginHost(
    private val jsBridge: JsPluginBridge = JsPluginBridge()
) {
    private val plugins = mutableMapOf<String, Plugin>()

    /**
     * Registers and enables a plugin.
     */
    suspend fun registerPlugin(plugin: Plugin) {
        plugins[plugin.id] = plugin
        plugin.onEnable()
    }

    /**
     * Disables and unregisters a plugin.
     */
    suspend fun unregisterPlugin(pluginId: String) {
        plugins[pluginId]?.onDisable()
        plugins.remove(pluginId)
    }

    /**
     * Gets a registered plugin by its ID.
     */
    fun getPlugin(pluginId: String): Plugin? = plugins[pluginId]

    /**
     * Returns all registered plugins.
     */
    fun getAllPlugins(): List<Plugin> = plugins.values.toList()

    /**
     * Placeholder for JS-interop to support existing CLJS plugins.
     * This will be expanded to handle the messaging bridge between KMP and JS.
     */
    fun getJsBridge(): JsPluginBridge = jsBridge
}

/**
 * Bridge for interoperability with JavaScript-based plugins.
 * Handles communication between the Kotlin core and the JS environment.
 */
class JsPluginBridge {
    private val logger = Logger("JsPluginBridge")

    /**
     * Placeholder for invoking a JS function from Kotlin.
     */
    fun invokeJs(pluginId: String, method: String, args: List<Any>): Any? {
        // TODO: Implement actual JS-interop using platform-specific mechanisms
        // (e.g., JNI for Android, JavaScriptCore for iOS, or direct JS calls for Web)
        logger.debug("Invoking JS method $method for plugin $pluginId with args $args")
        return null
    }

    /**
     * Placeholder for receiving a call from JS to Kotlin.
     */
    fun onJsCall(pluginId: String, method: String, args: List<Any>): Any? {
        // TODO: Route JS calls to the appropriate Kotlin APIs
        logger.debug("Received JS call $method from plugin $pluginId with args $args")
        return null
    }
}
