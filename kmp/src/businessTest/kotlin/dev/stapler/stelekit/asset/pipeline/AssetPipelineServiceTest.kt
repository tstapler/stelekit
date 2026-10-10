package dev.stapler.stelekit.asset.pipeline

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

private fun makeAssetEntry(
    uuid: String = "uuid-1",
    mlProcessed: Boolean = false,
    mlFailed: Boolean = false,
) = AssetEntry(
    uuid = AssetUuid(uuid),
    filePath = "/graph/assets/images/photo.jpg",
    relativePath = "../assets/images/photo.jpg",
    mediaType = AssetMediaType.IMAGE,
    subfolder = "images",
    tags = emptyList(),
    autoLabels = emptyList(),
    ocrText = null,
    cloudDescription = null,
    pageUuids = emptyList(),
    sizeBytes = 1024L,
    importedAtMs = 1000L,
    mlProcessed = mlProcessed,
    mlAttemptedAt = null,
    mlFailed = mlFailed,
    contentHash = null,
    isOrphan = false,
    mlTagsSource = "NONE",
)

private class LabelsPlugin(private val labels: List<String>) : AssetPipelinePlugin {
    override val id = "labels-plugin"
    override fun canProcess(asset: AssetEntry) = true
    override suspend fun processAsset(asset: AssetEntry): Either<DomainError, AssetPipelineResult> =
        AssetPipelineResult.Labels(labels).right()
}

private class FailingPlugin : AssetPipelinePlugin {
    override val id = "failing-plugin"
    override fun canProcess(asset: AssetEntry) = true
    override suspend fun processAsset(asset: AssetEntry): Either<DomainError, AssetPipelineResult> =
        DomainError.DatabaseError.WriteFailed("plugin failed").left()
}

class AssetPipelineServiceTest {

    @Test
    fun `processAsset with labels plugin saves labels and marks mlProcessed`() = runTest {
        val repo = InMemoryAssetRepository()
        val asset = makeAssetEntry()
        repo.saveAsset(asset)

        val registry = PluginRegistry()
        registry.register(LabelsPlugin(listOf("cat", "dog")))
        val service = AssetPipelineService(registry, repo)

        service.processAsset(asset, writeActor = null)

        val result = repo.getAssetByUuid(asset.uuid).first().getOrNull()!!
        assertTrue(result.mlProcessed)
        assertFalse(result.mlFailed)
        assertEquals(listOf("cat", "dog"), result.autoLabels)
    }

    @Test
    fun `processAsset with failing plugin marks mlFailed`() = runTest {
        val repo = InMemoryAssetRepository()
        val asset = makeAssetEntry()
        repo.saveAsset(asset)

        val registry = PluginRegistry()
        registry.register(FailingPlugin())
        val service = AssetPipelineService(registry, repo)

        service.processAsset(asset, writeActor = null)

        val result = repo.getAssetByUuid(asset.uuid).first().getOrNull()!!
        assertFalse(result.mlProcessed)
        assertTrue(result.mlFailed)
    }

    @Test
    fun `processAsset called twice for same UUID is no-op on second call`() = runTest {
        val repo = InMemoryAssetRepository()
        val asset = makeAssetEntry()
        repo.saveAsset(asset)

        var callCount = 0
        val countingPlugin = object : AssetPipelinePlugin {
            override val id = "counting"
            override fun canProcess(asset: AssetEntry) = true
            override suspend fun processAsset(asset: AssetEntry): Either<DomainError, AssetPipelineResult> {
                callCount++
                return AssetPipelineResult.Labels(emptyList()).right()
            }
        }

        val registry = PluginRegistry()
        registry.register(countingPlugin)
        val service = AssetPipelineService(registry, repo)

        service.processAsset(asset, writeActor = null)
        service.processAsset(asset, writeActor = null)

        assertEquals(1, callCount, "Plugin should only be called once for the same asset UUID")
    }

    @Test
    fun `processAsset processes all assets across batches without duplicates`() = runTest {
        val repo = InMemoryAssetRepository()
        // Insert 25 unprocessed assets (more than one batch of 10)
        val assets = (0 until 25).map { i ->
            makeAssetEntry(uuid = "uuid-$i").copy(importedAtMs = i.toLong()).also { repo.saveAsset(it) }
        }

        val registry = PluginRegistry()
        registry.register(LabelsPlugin(listOf("label")))
        val service = AssetPipelineService(registry, repo)

        // Process all assets directly (synchronously)
        for (asset in assets) {
            service.processAsset(asset, writeActor = null)
        }

        // All should now be processed
        val remaining = repo.countUnprocessedAssets().getOrNull() ?: Long.MAX_VALUE
        assertEquals(0L, remaining, "All 25 assets should be processed")

        service.shutdown()
    }
}
