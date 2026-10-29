@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.asset.pipeline

import app.cash.sqldelight.db.SqlDriver
import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.SqlDelightAssetRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task 0.4a: the drain on the real SQLDelight backend. An in-memory repository hides the defect
 * because it does not shrink the "unprocessed" result set the way SQL does after each batch.
 */
class AssetPipelineDrainBugTest {
    private val driver: SqlDriver = DriverFactory().createDriver("jdbc:sqlite::memory:")
    private val repo = SqlDelightAssetRepository(SteleDatabase(driver))
    private val actor = DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository())

    @AfterTest
    fun teardown() {
        driver.close()
    }

    private class SucceedsOnEverything : AssetPipelinePlugin {
        override val id = "succeeds"
        override fun canProcess(asset: AssetEntry) = true
        override suspend fun processAsset(asset: AssetEntry): Either<DomainError, AssetPipelineResult> =
            AssetPipelineResult.Labels(listOf("label")).right()
    }

    private fun asset(i: Int) = AssetEntry(
        uuid = AssetUuid("a$i"), filePath = "/g/assets/a$i.png", relativePath = "../assets/a$i.png",
        mediaType = AssetMediaType.IMAGE, subfolder = "assets", tags = emptyList(), autoLabels = emptyList(),
        ocrText = null, cloudDescription = null, pageUuids = emptyList(), sizeBytes = 1L,
        importedAtMs = i.toLong(), mlProcessed = false, mlAttemptedAt = null, mlFailed = false,
        contentHash = null, isOrphan = false, mlTagsSource = "NONE",
    )

    @Test
    fun backfill_over25Assets_withBatchSize10_processesEveryAsset() = runBlocking<Unit> {
        repeat(25) { repo.saveAsset(asset(it)) }
        val registry = PluginRegistry().apply { register(SucceedsOnEverything()) }
        val service = AssetPipelineService(registry, repo, actor)

        service.scheduleBackfill()
        withTimeoutOrNull(5_000) {
            while ((repo.countUnprocessedAssets().getOrNull() ?: Long.MAX_VALUE) > 0L) delay(25)
        }

        val left = repo.countUnprocessedAssets().getOrNull()
        service.shutdown()
        assertEquals(0L, left, "every asset must end processed or failed; rows skipped when the offset moved past them stay unprocessed")
    }
}
