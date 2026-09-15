package dev.stapler.stelekit.asset.pipeline

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.error.DomainError
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
