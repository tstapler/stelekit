@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.asset

import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SqlDelightAssetRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Task 6.2d read side: the Assets search and global search match cloud descriptions and say which column matched. */
class AssetSearchCloudDescriptionTest {
    private lateinit var driver: SqlDriver
    private lateinit var repo: SqlDelightAssetRepository

    @BeforeTest
    fun setup() {
        driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        repo = SqlDelightAssetRepository(SteleDatabase(driver))
    }

    @AfterTest
    fun teardown() = driver.close()

    private fun entry(id: String, ocr: String? = null, cloud: String? = null) = AssetEntry(
        uuid = AssetUuid(id), filePath = "/g/assets/$id.png", relativePath = "../assets/$id.png",
        mediaType = AssetMediaType.IMAGE, subfolder = "", tags = emptyList(), autoLabels = emptyList(),
        ocrText = ocr, cloudDescription = cloud, pageUuids = emptyList(), sizeBytes = 1, importedAtMs = 1,
        mlProcessed = false, mlAttemptedAt = null, mlFailed = false, contentHash = null, isOrphan = false,
        mlTagsSource = "NONE",
    )

    @Test
    fun searchAssetsLightAndPreview_should_MatchCloudDescriptionAndExposeMatchedColumn_When_QueryAcme() = runBlocking<Unit> {
        repo.saveAsset(entry("cloud", cloud = "A receipt from Acme"))
        repo.saveAsset(entry("ocr", ocr = "Acme Corp invoice"))
        repo.saveAsset(entry("none", cloud = "A red bicycle"))

        val hits = AssetSearchService(repo).search("Acme", 50).getOrNull().orEmpty()

        assertEquals(setOf("cloud", "ocr"), hits.map { it.asset.uuid.value }.toSet())
        val byId = hits.associateBy { it.asset.uuid.value }
        assertEquals("cloud_description", byId.getValue("cloud").preview!!.matchedColumn)
        assertEquals("AI description", byId.getValue("cloud").preview!!.sourceLabel)
        assertEquals("ocr_text", byId.getValue("ocr").preview!!.matchedColumn)

        val keyset = repo.getAssetPage(null, "Acme", AssetSortOrder.BY_DATE_ADDED, null, null, null, null, 50)
            .first().getOrNull().orEmpty()
        assertEquals(setOf("cloud", "ocr"), keyset.map { it.uuid.value }.toSet(), "the Assets screen search matches the same columns")
    }
}
