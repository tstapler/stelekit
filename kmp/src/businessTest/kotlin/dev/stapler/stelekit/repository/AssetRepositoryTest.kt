package dev.stapler.stelekit.repository

import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class AssetRepositoryTest {
    private fun repo() = InMemoryAssetRepository()

    private fun makeEntry(
        uuid: String = "uuid-1",
        mediaType: AssetMediaType = AssetMediaType.IMAGE,
        mlProcessed: Boolean = false,
        mlFailed: Boolean = false,
    ) = AssetEntry(
        uuid = AssetUuid(uuid),
        filePath = "/graph/assets/images/photo.jpg",
        relativePath = "../assets/images/photo.jpg",
        mediaType = mediaType,
        subfolder = "images",
        tags = listOf("nature"),
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

    @Test fun `CRUD round-trip`() = runTest {
        val r = repo()
        val entry = makeEntry()
        r.saveAsset(entry)
        val fetched = r.getAssetByUuid(entry.uuid).first().getOrNull()
        assertNotNull(fetched)
        assertEquals(entry.filePath, fetched.filePath)
    }

    @Test fun `pagination returns correct pages`() = runTest {
        val r = repo()
        repeat(15) { i ->
            r.saveAsset(
                makeEntry(uuid = "uuid-$i", mediaType = AssetMediaType.FILE)
                    .copy(importedAtMs = i.toLong())
            )
        }
        val page1 = r.getAssets(limit = 10, offset = 0).first().getOrNull()!!
        val page2 = r.getAssets(limit = 10, offset = 10).first().getOrNull()!!
        assertEquals(10, page1.size)
        assertEquals(5, page2.size)
    }

    @Test fun `getAssetsByMediaType returns only matching type`() = runTest {
        val r = repo()
        r.saveAsset(makeEntry(uuid = "img", mediaType = AssetMediaType.IMAGE))
        r.saveAsset(makeEntry(uuid = "pdf", mediaType = AssetMediaType.PDF))
        val images = r.getAssetsByMediaType(AssetMediaType.IMAGE, 10, 0).first().getOrNull()!!
        assertEquals(1, images.size)
        assertEquals(AssetMediaType.IMAGE, images[0].mediaType)
    }

    @Test fun `updateTags persists tags`() = runTest {
        val r = repo()
        val entry = makeEntry()
        r.saveAsset(entry)
        r.updateTags(entry.uuid, listOf("tag1", "tag2"))
        val fetched = r.getAssetByUuid(entry.uuid).first().getOrNull()!!
        assertEquals(listOf("tag1", "tag2"), fetched.tags)
    }

    @Test fun `markMlProcessed flags asset`() = runTest {
        val r = repo()
        val entry = makeEntry()
        r.saveAsset(entry)
        r.markMlProcessed(entry.uuid, 9999L)
        val fetched = r.getAssetByUuid(entry.uuid).first().getOrNull()!!
        assertTrue(fetched.mlProcessed)
        assertEquals(9999L, fetched.mlAttemptedAt)
    }

    @Test fun `getUnprocessedAssets excludes mlFailed entries`() = runTest {
        val r = repo()
        r.saveAsset(makeEntry(uuid = "ok"))
        r.saveAsset(makeEntry(uuid = "failed", mlFailed = true))
        r.saveAsset(makeEntry(uuid = "done", mlProcessed = true))
        val unprocessed = r.getUnprocessedAssets(10, 0).first().getOrNull()!!
        assertEquals(1, unprocessed.size)
        assertEquals(AssetUuid("ok"), unprocessed[0].uuid)
    }

    @Test fun `deleteAsset removes entry`() = runTest {
        val r = repo()
        val entry = makeEntry()
        r.saveAsset(entry)
        r.deleteAsset(entry.uuid)
        val fetched = r.getAssetByUuid(entry.uuid).first().getOrNull()
        assertNull(fetched)
    }
}
