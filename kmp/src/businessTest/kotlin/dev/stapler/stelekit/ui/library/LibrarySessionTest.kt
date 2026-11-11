@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.library

import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import dev.stapler.stelekit.ui.assets.assetIcon
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class LibrarySessionTest {
    private fun entry(id: String) = AssetEntry(
        uuid = AssetUuid(id), filePath = "/g/$id.png", relativePath = "$id.png", mediaType = AssetMediaType.IMAGE,
        subfolder = "files", tags = emptyList(), autoLabels = emptyList(), ocrText = null, cloudDescription = null,
        pageUuids = emptyList(), sizeBytes = 1, importedAtMs = 1, mlProcessed = false, mlAttemptedAt = null,
        mlFailed = false, contentHash = null, isOrphan = false, mlTagsSource = "NONE",
    )

    @Test
    fun theSessionKeepsOneViewModel_untilClosed() {
        val repos = dev.stapler.stelekit.repository.InMemoryLibraryRepository(
            InMemoryAssetRepository(),
            dev.stapler.stelekit.repository.InMemoryImageAnnotationRepository(),
            dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository(),
        )
        val session = LibrarySession(repos, null)
        val first = session.viewModel()
        assertSame(first, session.viewModel())
        session.close()
        assertNotNull(session.viewModel().also { it.close() })
        session.close()
    }

    @Test
    fun deleterRemovesTheAssetWithoutAWriteActor() = runBlocking {
        val assets = InMemoryAssetRepository()
        assets.saveAsset(entry("a1"))
        libraryAssetDeleter(assets, null)("a1")
        assertEquals(null, assets.getAssetByUuid(AssetUuid("a1")).first().getOrNull())
    }

    @Test
    fun everyMediaTypeHasAnIcon() {
        AssetMediaType.entries.forEach { assertNotNull(assetIcon(it)) }
    }
}
