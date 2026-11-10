package dev.stapler.stelekit.asset.pipeline

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The asset pipeline reaches the cloud only through one plugin and the one factory that builds it. */
class CloudStructuralAuditTest {
    private val assetPackage = "commonMain/dev/stapler/stelekit/asset/"

    @Test
    fun cloudClient_should_BeReferencedOnlyFromCloudClaudePlugin_When_AssetPackageAudited() {
        val referencing = WiringInputs.productionSources
            .filter { (path, text) -> path.startsWith(assetPackage) && "AnthropicMessagesClient" in text }
            .keys.map { it.substringAfterLast('/') }.toSet()

        // DefaultAssetPlugins.kt is the factory that hands the client to the plugin; nothing else may touch it.
        assertEquals(setOf("CloudClaudePlugin.kt", "DefaultAssetPlugins.kt"), referencing)
    }

    @Test
    fun plugins_should_AdmitIMAGEByEqualityOnly_And_NeverMentionDRAWING_When_PipelineSourcesAudited() {
        val pluginSources = WiringInputs.productionSources
            .filter { (path, text) -> path.startsWith("${assetPackage}pipeline/") && "AssetPipelinePlugin" in text && "canProcess" in text }
        assertTrue(pluginSources.size >= 3, "expected the OCR, labeling and cloud plugins, found ${pluginSources.keys}")
        for ((path, text) in pluginSources) {
            assertTrue("AssetMediaType.DRAWING" !in text, "$path must not admit DRAWING")
            // A negated or open-ended IMAGE test (`in`, `else`, `!=` on the admit side) could swallow DRAWING.
            val imageMentions = Regex("""AssetMediaType\.IMAGE""").findAll(text).count()
            val equalityMentions = Regex("""mediaType\s*(==|!=)\s*AssetMediaType\.IMAGE""").findAll(text).count()
            assertEquals(imageMentions, equalityMentions, "$path must test IMAGE by direct equality only")
        }
    }

    @Test
    fun cloudPlugin_should_NotAdmitDRAWING_When_ImageWithSameFieldsIsAdmitted() {
        val rig = CloudRig().also { it.gate.grant(CloudRig.GRAPH_A, atMs = 5L, scope = CloudBackfillScope.IncludeExisting) }
        val image = rig.putImage("a")
        assertTrue(rig.plugin.canProcess(image), "control: the same row as IMAGE is admitted")
        assertEquals(false, rig.plugin.canProcess(image.copy(mediaType = dev.stapler.stelekit.asset.AssetMediaType.DRAWING)))
    }

    @Test
    fun cloudVisionPlugin_should_NotExist_When_OwnerGate_OD5Confirmed() {
        val mentions = WiringInputs.productionSources.filterValues { "CloudVision" + "Plugin" in it }.keys
        assertEquals(emptySet(), mentions)
        assertTrue(WiringInputs.productionSources.keys.none { it.endsWith("CloudVision" + "Plugin.kt") })
    }

    @Test
    fun modelId_should_ComeFromConfigNotLiteralInCloudClaudePlugin_When_SourceAudited() {
        val plugin = WiringInputs.productionSources.getValue("${assetPackage}pipeline/CloudClaudePlugin.kt")
        val config = WiringInputs.productionSources.getValue("${assetPackage}pipeline/CloudEnrichmentConfig.kt")

        assertEquals(emptyList(), Regex("claude-(haiku|sonnet|opus|\\d)").findAll(plugin).map { it.value }.toList())
        assertTrue("config.model" in plugin, "the plugin must send config.model")
        assertTrue("val model: String" in config, "the model is a config field")
    }
}
