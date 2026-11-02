package dev.stapler.stelekit.settings

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tasks 5.5d and 10.4a: one enabled-state registry. The Logseq-style `PluginHost`/`Plugin` are gone; `PluginRegistry` feeds the status bar. */
class PluginHostRemovalAuditTest {
    private val sources get() = WiringInputs.productionSources

    @Test
    fun pluginHostAndPlugin_should_NotExistAndStatusBarReadPluginRegistryCount_And_JsPluginBridgeAndPluginSystemFileAreGone_When_Task10_4aMerged() {
        val declaring = sources.filter { (_, text) ->
            Regex("""\b(class|interface)\s+PluginHost\b""").containsMatchIn(text) ||
                Regex("""\binterface\s+Plugin\b""").containsMatchIn(text)
        }.keys
        assertEquals(emptySet(), declaring, "PluginHost and Plugin must stay deleted")

        val users = sources.filter { (_, text) -> Regex("""\bPluginHost\b""").containsMatchIn(text) }.keys
        assertEquals(emptySet(), users, "nothing may reference PluginHost")

        val statusBar = sources.entries.single { it.key.endsWith("ui/GraphContentStatusAndBottomBar.kt") }.value
        assertTrue("PluginRegistry" !in statusBar && "pluginCount" in statusBar, "the status bar takes the registry's count flow")
        val shell = sources.entries.single { it.key.endsWith("ui/GraphContentActiveShell.kt") }.value
        assertTrue("registry?.enabledCount" in shell, "the shell feeds the status bar from PluginRegistry.enabledCount")

        assertTrue(sources.keys.none { it.endsWith("platform/PluginSystem.kt") }, "PluginSystem.kt is deleted with JsPluginBridge (Task 10.4a)")
        assertTrue(sources.values.none { Regex("""\bJsPluginBridge\b""").containsMatchIn(it) }, "nothing may reference JsPluginBridge")
    }
}
