@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.asset

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SqlDelightAssetRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Assets browser search reads the JSON-encoded `tags` column; tags containing JSON-escaped characters must still match. */
class AssetTagSearchSqlTest {
    private fun entry(id: String, tags: List<String>) = AssetEntry(
        uuid = AssetUuid(id), filePath = "/g/assets/$id.png", relativePath = "../assets/$id.png",
        mediaType = AssetMediaType.IMAGE, subfolder = "", tags = tags, autoLabels = emptyList(),
        ocrText = null, cloudDescription = null, pageUuids = emptyList(), sizeBytes = 1, importedAtMs = 1,
        mlProcessed = false, mlAttemptedAt = null, mlFailed = false, contentHash = null, isOrphan = false,
        mlTagsSource = "NONE",
    )

    private fun search(repo: SqlDelightAssetRepository, q: String) = runBlocking {
        repo.getAssetPage(null, q, AssetSortOrder.BY_DATE_ADDED, null, null, null, null, 50)
            .first().getOrNull().orEmpty().map { it.uuid.value }
    }

    @Test
    fun getAssetPage_should_MatchByTag_When_TagPlainOrContainsQuote() {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        try {
            val repo = SqlDelightAssetRepository(SteleDatabase(driver))
            runBlocking {
                repo.saveAsset(entry("plain", listOf("remodel")))
                repo.saveAsset(entry("quoted", listOf("5\" pipe")))
                repo.saveAsset(entry("none", emptyList()))
            }
            assertEquals(listOf("plain"), search(repo, "remodel"))
            assertEquals(listOf("quoted"), search(repo, "pipe"))
        } finally {
            driver.close()
        }
    }
}
