@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.asset

import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SqlDelightAssetRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Task 5.6a over real SQLDelight: projected search rows, bounded snippets, and the failed-flag reset. */
class AssetSearchSqlTest {
    private lateinit var driver: SqlDriver
    private lateinit var repo: SqlDelightAssetRepository

    @BeforeTest
    fun setup() {
        driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        repo = SqlDelightAssetRepository(SteleDatabase(driver))
    }

    @AfterTest
    fun teardown() = driver.close()

    private fun entry(id: String, ocr: String? = null, cloud: String? = null, labels: List<String> = emptyList(), failed: Boolean = false) =
        AssetEntry(
            uuid = AssetUuid(id), filePath = "/g/assets/$id.png", relativePath = "../assets/$id.png",
            mediaType = AssetMediaType.IMAGE, subfolder = "", tags = emptyList(), autoLabels = labels,
            ocrText = ocr, cloudDescription = cloud, pageUuids = emptyList(), sizeBytes = 1, importedAtMs = 1,
            mlProcessed = false, mlAttemptedAt = null, mlFailed = failed, contentHash = null, isOrphan = false,
            mlTagsSource = "NONE",
        )

    @Test
    fun search_should_ReturnProjectedRowsWithBoundedSnippetAroundTheMatch_When_OcrTextIsLong() = runBlocking<Unit> {
        val long = "x".repeat(1_500) + " Invoice 4821 due Friday " + "y".repeat(1_500)
        repo.saveAsset(entry("inv", ocr = long))
        repo.saveAsset(entry("other", ocr = "nothing here"))

        val hits = AssetSearchService(repo).search("4821", 50).getOrNull().orEmpty()

        assertEquals(listOf("inv"), hits.map { it.asset.uuid.value })
        assertEquals(null, hits.single().asset.ocrText, "list rows never carry the ocr_text blob")
        val preview = hits.single().preview!!
        assertEquals("ocr_text", preview.matchedColumn)
        assertTrue(preview.excerpt.length <= 240, "excerpt is ${preview.excerpt.length} characters")
        assertTrue(preview.excerpt.contains("4821"))
    }

    @Test
    fun snippets_should_NameTheMatchedColumn_AndTreatLikeWildcardsAsText() = runBlocking<Unit> {
        repo.saveAsset(entry("c", cloud = "A red bicycle leaning on a wall"))
        repo.saveAsset(entry("l", labels = listOf("bicycle", "outdoor")))
        repo.saveAsset(entry("pct", ocr = "100% done"))

        val snippets = repo.getSearchSnippets("bicycle", listOf(AssetUuid("c"), AssetUuid("l"))).getOrNull().orEmpty()
        assertEquals("cloud_description", snippets.getValue(AssetUuid("c")).matchedColumn)
        assertEquals("AI description", snippets.getValue(AssetUuid("c")).sourceLabel)
        assertEquals("auto_labels", snippets.getValue(AssetUuid("l")).matchedColumn)

        val hits = AssetSearchService(repo).search("100%", 5).getOrNull().orEmpty()
        assertEquals(listOf("pct"), hits.map { it.asset.uuid.value }, "% in the query is literal")
        assertEquals(emptyMap(), repo.getSearchSnippets("x", emptyList()).getOrNull())
    }

    @Test
    fun search_should_CapResultsAtLimit_And_IgnoreBlankQueries() = runBlocking<Unit> {
        repeat(8) { repo.saveAsset(entry("a$it", ocr = "Invoice 4821 copy $it")) }

        assertEquals(5, AssetSearchService(repo).search("4821", AssetSearchService.GLOBAL_SECTION_LIMIT).getOrNull()?.size)
        assertEquals(8, AssetSearchService(repo).search("4821", 500).getOrNull()?.size)
        assertEquals(emptyList(), AssetSearchService(repo).search("  ", 5).getOrNull())
        assertEquals(false, AssetSearchService.shouldQueryGlobal("48"))
        assertEquals(true, AssetSearchService.shouldQueryGlobal("481"))
    }

    @Test
    fun resetFailedAssets_should_ClearOnlyTheFailedFlag() = runBlocking<Unit> {
        repo.saveAsset(entry("bad"))
        repo.saveAsset(entry("ok"))
        repo.markMlFailed(AssetUuid("bad"), 1L)
        assertEquals(1L, repo.countUnprocessedAssets().getOrNull(), "a failed row is not pending")

        repo.resetFailedAssets()

        assertEquals(2L, repo.countUnprocessedAssets().getOrNull(), "the failed row is eligible for processing again")
    }
}
