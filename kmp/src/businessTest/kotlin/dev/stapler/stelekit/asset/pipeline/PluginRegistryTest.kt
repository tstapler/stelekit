package dev.stapler.stelekit.asset.pipeline

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.error.DomainError
import kotlin.test.Test
import dev.stapler.stelekit.platform.ml.ImageLabeler
import dev.stapler.stelekit.platform.ml.Label
import dev.stapler.stelekit.platform.ml.PdfTextExtractor
import dev.stapler.stelekit.platform.ml.TextRecognizer
import dev.stapler.stelekit.wiring.FakeSettings
import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun makeAsset(mediaType: AssetMediaType) = AssetEntry(
    uuid = AssetUuid("test"),
    filePath = "/graph/assets/images/photo.jpg",
    relativePath = "../assets/images/photo.jpg",
    mediaType = mediaType,
    subfolder = "images",
    tags = emptyList(),
    autoLabels = emptyList(),
    ocrText = null,
    cloudDescription = null,
    pageUuids = emptyList(),
    sizeBytes = 0,
    importedAtMs = 0,
    mlProcessed = false,
    mlAttemptedAt = null,
    mlFailed = false,
    contentHash = null,
    isOrphan = false,
    mlTagsSource = "NONE",
)

private class StubPlugin(
    override val id: String,
    private val type: AssetMediaType,
) : AssetPipelinePlugin {
    var callCount = 0
    override fun canProcess(asset: AssetEntry): Boolean = asset.mediaType == type
    override suspend fun processAsset(asset: AssetEntry): Either<DomainError, AssetPipelineResult> {
        callCount++
        return AssetPipelineResult.Labels(listOf(id)).right()
    }
}

class PluginRegistryTest {
    @Test fun `only image plugin applicable for IMAGE asset`() {
        val registry = PluginRegistry()
        val imagePlugin = StubPlugin("img", AssetMediaType.IMAGE)
        val pdfPlugin = StubPlugin("pdf", AssetMediaType.PDF)
        registry.register(imagePlugin)
        registry.register(pdfPlugin)

        val asset = makeAsset(AssetMediaType.IMAGE)
        val applicable = registry.all.filter { it.canProcess(asset) }
        assertEquals(1, applicable.size)
        assertEquals("img", applicable[0].id)
    }

    @Test fun `only pdf plugin applicable for PDF asset`() {
        val registry = PluginRegistry()
        val imagePlugin = StubPlugin("img", AssetMediaType.IMAGE)
        val pdfPlugin = StubPlugin("pdf", AssetMediaType.PDF)
        registry.register(imagePlugin)
        registry.register(pdfPlugin)

        val asset = makeAsset(AssetMediaType.PDF)
        val applicable = registry.all.filter { it.canProcess(asset) }
        assertEquals(1, applicable.size)
        assertEquals("pdf", applicable[0].id)
    }

    private val fullCaps = PlatformCaps(
        object : ImageLabeler {
            override suspend fun labelImage(imageBytes: ByteArray): Either<DomainError, List<Label>> = emptyList<Label>().right()
        },
        object : PdfTextExtractor {
            override suspend fun extractText(absoluteFilePath: String): Either<DomainError, String> = "".right()
        },
        object : TextRecognizer {
            override suspend fun recognize(imageBytes: ByteArray): Either<DomainError, String> = "".right()
        },
    )

    @Test fun pluginRegistry_should_ExposeEnabledCountAsStateFlow_And_BeOnlyEnabledStateSource_When_AuditedInProduction() {
        val settings = FakeSettings().apply { putBoolean(AssetBuildFlags.PIPELINE_ENABLED_KEY, true) }
        val registry = PluginRegistry(settings, fullCaps)
        assertEquals(0, registry.enabledCount.value)

        registry.setEnabled(PluginEnableKeys.TEXT_RECOGNITION, true)
        registry.setEnabled(PluginEnableKeys.PDF_TEXT, true)
        assertEquals(2, registry.enabledCount.value)
        assertEquals(true, registry.enabledStates.value[PluginEnableKeys.PDF_TEXT])

        // Source audit: only the registry and the plugin factory read the per-plugin keys.
        val readers = WiringInputs.productionSources
            .filter { (_, text) -> "PluginEnableKeys.isEnabled(" in text || "PluginEnableKeys.keyFor(" in text }
            .keys.map { it.substringAfterLast('/') }.toSet()
        assertEquals(setOf("PluginRegistry.kt", "DefaultAssetPlugins.kt"), readers)
    }

    @Test fun enabledCount_should_DropToZeroWhenMasterOffAndIgnoreUnavailablePlugins_When_MasterOffOrCapabilityMissing() {
        val settings = FakeSettings().apply {
            putBoolean(AssetBuildFlags.PIPELINE_ENABLED_KEY, true)
            putBoolean(PluginEnableKeys.keyFor(PluginEnableKeys.IMAGE_LABELS), true)
        }
        val withLabeler = PluginRegistry(settings, fullCaps)
        assertEquals(1, withLabeler.enabledCount.value)
        withLabeler.setMasterEnabled(false)
        assertEquals(0, withLabeler.enabledCount.value)

        val none = PluginRegistry(settings, PlatformCaps.NONE)
        assertEquals(0, none.enabledCount.value, "a stored key for a plugin this platform cannot run is not counted")
        assertEquals(PluginRegistry.MODEL_NOT_INSTALLED, none.unavailableReason(PluginEnableKeys.IMAGE_LABELS))
        assertEquals(PluginRegistry.NOT_AVAILABLE_ON_DESKTOP, none.unavailableReason(PluginEnableKeys.TEXT_RECOGNITION))
        assertNull(withLabeler.unavailableReason(PluginEnableKeys.IMAGE_LABELS))
        assertTrue(withLabeler.enabledStates.value.keys == PluginRegistry.PLUGIN_IDS.toSet())
    }

    @Test fun enabledCount_should_IncludeCloudPluginOnlyWhileConsentGrantedAndSwitchOn_When_StatusBarReadsRegistry() {
        val settings = FakeSettings()
        settings.putBoolean(AssetBuildFlags.PIPELINE_ENABLED_KEY, true)
        var granted = false
        val registry = PluginRegistry(settings, PlatformCaps.NONE, cloudConsentGranted = { granted })
        assertEquals(0, registry.enabledCount.value)

        registry.setEnabled(PluginEnableKeys.CLOUD_CLAUDE, true)
        assertEquals(0, registry.enabledCount.value, "switch on without consent counts nothing")

        granted = true
        registry.onCloudStateChanged()
        assertEquals(1, registry.enabledCount.value, "consent granted and switch on counts the cloud plugin")

        granted = false
        registry.onCloudStateChanged()
        assertEquals(0, registry.enabledCount.value, "revoked consent drops it at once")

        granted = true
        registry.setEnabled(PluginEnableKeys.CLOUD_CLAUDE, false)
        assertEquals(0, registry.enabledCount.value, "consent without the switch counts nothing")
    }
}
