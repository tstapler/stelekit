@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.asset.pipeline

import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.SqlDelightAssetRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** IncludeExisting: already-processed images are described in bounded batches, only when the owner chose it. */
class CloudBackfillTest {
    private lateinit var driver: SqlDriver
    private lateinit var repo: SqlDelightAssetRepository

    @BeforeTest
    fun setup() {
        driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        repo = SqlDelightAssetRepository(SteleDatabase(driver))
    }

    @AfterTest
    fun teardown() = driver.close()

    private suspend fun seedProcessed(rig: CloudRig, count: Int): List<AssetEntry> = (1..count).map { n ->
        val asset = rig.putImage("p$n", importedAtMs = 10L * n)
        repo.saveAsset(asset)
        repo.markMlProcessed(asset.uuid, 1L)
        asset.copy(mlProcessed = true)
    }

    private fun service(rig: CloudRig) = AssetPipelineService(
        PluginRegistry().also { it.register(rig.plugin) }, repo,
        DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository()),
    )

    private suspend fun drain(service: AssetPipelineService): PipelineProgress {
        service.scheduleBackfill()
        return withTimeout(30_000) { service.progress.first { it is PipelineProgress.Finished || it is PipelineProgress.Paused } }
    }

    @Test
    fun query_should_ListOnlyProcessedUndescribedImagesInBoundedBatches_When_CloudBackfillReads() = runBlocking<Unit> {
        val rig = CloudRig()
        seedProcessed(rig, 25)
        repo.saveAsset(rig.putImage("fresh"))
        val done = rig.putImage("done").also { repo.saveAsset(it) }
        repo.markMlProcessed(done.uuid, 1L)
        repo.updateCloudDescription(done.uuid, "already", "CLOUD_CLAUDE")
        val bad = rig.putImage("bad").also { repo.saveAsset(it) }
        repo.markMlFailed(bad.uuid, 1L)

        assertEquals(25L, repo.countAssetsNeedingCloudDescription().getOrNull())
        val first = repo.getAssetsNeedingCloudDescription(limit = 10, offset = 0).first().getOrNull().orEmpty()
        val last = repo.getAssetsNeedingCloudDescription(limit = 10, offset = 20).first().getOrNull().orEmpty()
        assertEquals(10, first.size)
        assertEquals(5, last.size)
        assertEquals("p1", first.first().uuid.value, "oldest first")
    }

    @Test
    fun includeExisting_should_DescribeProcessedAssetsInBatches_And_NewOnly_ShouldNot_When_Drained() = runBlocking<Unit> {
        val includeExisting = CloudRig(sessionCap = 100)
        seedProcessed(includeExisting, 25)
        includeExisting.gate.grant(CloudRig.GRAPH_A, atMs = 5L, scope = CloudBackfillScope.IncludeExisting)

        val state = drain(service(includeExisting))

        assertEquals(PipelineProgress.Finished(25, 0), state)
        assertEquals(25, includeExisting.http.requests.size)
        assertEquals(0L, repo.countAssetsNeedingCloudDescription().getOrNull())
        val stored = repo.getAssetByUuid(dev.stapler.stelekit.asset.AssetUuid("p7")).first().getOrNull()!!
        assertEquals("A receipt from Acme", stored.cloudDescription)
        assertEquals("CLOUD_CLAUDE", stored.mlTagsSource)
    }

    @Test
    fun newOnly_should_MakeNoRequestsForProcessedAssets_When_Drained() = runBlocking<Unit> {
        val newOnly = CloudRig()
        seedProcessed(newOnly, 12)
        newOnly.gate.grant(CloudRig.GRAPH_A, atMs = 5L, scope = CloudBackfillScope.NewOnly)

        drain(service(newOnly))

        assertEquals(0, newOnly.http.requests.size)
        assertEquals(12L, repo.countAssetsNeedingCloudDescription().getOrNull())
    }

    @Test
    fun backfill_should_PauseAtDailyCapAndKeepFailedStateUntouched_When_CapOrErrorHits() = runBlocking<Unit> {
        val capped = CloudRig(dailyCap = 3)
        seedProcessed(capped, 8)
        capped.gate.grant(CloudRig.GRAPH_A, atMs = 5L, scope = CloudBackfillScope.IncludeExisting)

        assertEquals(PipelineProgress.Paused(PauseReason.DailyCap), drain(service(capped)))
        assertEquals(3, capped.http.requests.size)

        val failing = CloudRig(status = io.ktor.http.HttpStatusCode.ServiceUnavailable)
        val repo2Asset = failing.putImage("x1")
        repo.saveAsset(repo2Asset)
        repo.markMlProcessed(repo2Asset.uuid, 1L)
        failing.gate.grant(CloudRig.GRAPH_A, atMs = 5L, scope = CloudBackfillScope.IncludeExisting)
        drain(service(failing))
        val after = repo.getAssetByUuid(repo2Asset.uuid).first().getOrNull()!!
        assertEquals(false, after.mlFailed, "a backfill error never flips a good asset to failed")
        assertNull(after.cloudDescription)
    }
}
