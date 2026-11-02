package dev.stapler.stelekit.settings

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Task 5.5d: one enabled-state registry. The Logseq-style `PluginHost`/`Plugin` are gone; `PluginRegistry` feeds the status bar. */
class PluginHostRemovalAuditTest {
    private val sources get() = WiringInputs.productionSources

    @Test
    fun pluginHostAndPlugin_should_NotExistAndStatusBarReadPluginRegistryCount_And_JsPluginBridgeAndPluginSystemFileRemain_When_Task5_5dMergedWithOD2Pending() {
        val declaring = sources.filter { (_, text) ->
            Regex("""\b(class|interface)\s+PluginHost\b""").containsMatchIn(text) ||
                Regex("""\binterface\s+Plugin\b""").containsMatchIn(text)
        }.keys
        assertEquals(emptySet(), declaring, "PluginHost and Plugin must stay deleted")

        val users = sources.filter { (key, text) -> !key.endsWith("PluginSystem.kt") && Regex("""\bPluginHost\b""").containsMatchIn(text) }.keys
        assertEquals(emptySet(), users, "nothing may reference PluginHost")

        val statusBar = sources.entries.single { it.key.endsWith("ui/GraphContentStatusAndBottomBar.kt") }.value
        assertTrue("PluginRegistry" !in statusBar && "pluginCount" in statusBar, "the status bar takes the registry's count flow")
        val shell = sources.entries.single { it.key.endsWith("ui/GraphContentActiveShell.kt") }.value
        assertTrue("registry?.enabledCount" in shell, "the shell feeds the status bar from PluginRegistry.enabledCount")

        val pluginSystem = sources.entries.single { it.key.endsWith("platform/PluginSystem.kt") }.value
        assertTrue("class JsPluginBridge" in pluginSystem, "JsPluginBridge is removed only by Task 10.4a")
    }
}
