@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.asset

import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.asset.pipeline.AssetPipelineService
import dev.stapler.stelekit.asset.pipeline.CloudBackfillScope
import dev.stapler.stelekit.asset.pipeline.CloudRig
import dev.stapler.stelekit.asset.pipeline.PluginRegistry
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.SqlDelightAssetRepository
import dev.stapler.stelekit.wiring.WiringInputs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Cloud text lands in `cloud_description` with `ml_tags_source = CLOUD_CLAUDE` and never touches OCR text or labels. */
class CloudDescriptionStorageTest {
    private lateinit var driver: SqlDriver
    private lateinit var repo: SqlDelightAssetRepository

    @BeforeTest
    fun setup() {
        driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        repo = SqlDelightAssetRepository(SteleDatabase(driver))
    }

    @AfterTest
    fun teardown() = driver.close()

    @Test
    fun cloudText_should_BeStoredViaUpdateCloudDescriptionWithMlTagsSourceCloudClaudeInOneActorTransaction_And_OcrTextAndAutoLabelsUntouched_When_MockDescriptionPersisted() = runBlocking<Unit> {
        val rig = CloudRig()
        rig.gate.grant(CloudRig.GRAPH_A, 1L, CloudBackfillScope.NewOnly)
        val seeded = rig.putImage("a1").copy(ocrText = "printed words", autoLabels = listOf("receipt"))
        repo.saveAsset(seeded)
        val service = AssetPipelineService(
            PluginRegistry().also { it.register(rig.plugin) }, repo,
            DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository()),
        )

        assertEquals(AssetPipelineService.Outcome.Processed, service.processAsset(seeded))

        val stored = repo.getAssetByUuid(seeded.uuid).first().getOrNull()!!
        assertEquals("A receipt from Acme", stored.cloudDescription)
        assertEquals("CLOUD_CLAUDE", stored.mlTagsSource)
        assertEquals("printed words", stored.ocrText, "cloud text is never written to ocr_text (ADR-004)")
        assertEquals(listOf("receipt"), stored.autoLabels)
        assertTrue(stored.mlProcessed)
        assertFalse(stored.mlFailed)
    }

    @Test
    fun schema_should_HaveNoAiDescriptionColumnAndNoNewMigrationEntry_And_MlTagsSourceAcceptCloudClaude_When_SqAndMigrationRunnerAudited() = runBlocking<Unit> {
        val schema = javaClass.classLoader.getResourceAsStream("SteleDatabase.sq")?.bufferedReader()?.readText()
            ?: System.getProperty("stelekit.sq.file")?.let { java.io.File(it).readText() }
            ?: error("SteleDatabase.sq not on the classpath and stelekit.sq.file unset")
        val migrations = WiringInputs.productionSources.getValue("commonMain/dev/stapler/stelekit/db/MigrationRunner.kt")

        assertFalse("ai_description" in schema + migrations)
        assertTrue("cloud_description TEXT" in schema, "the column already exists in asset_index")
        assertTrue("ml_tags_source TEXT NOT NULL DEFAULT 'NONE'" in schema, "free text, no CHECK constraint")
        assertFalse(Regex("CHECK\\s*\\(\\s*ml_tags_source").containsMatchIn(schema))

        val rig = CloudRig()
        val asset = rig.putImage("a2")
        repo.saveAsset(asset)
        repo.updateCloudDescription(asset.uuid, "text", "CLOUD_CLAUDE")
        assertEquals("CLOUD_CLAUDE", repo.getAssetByUuid(asset.uuid).first().getOrNull()!!.mlTagsSource)
    }

    @Test
    fun deleteAiDescriptions_should_ClearAllCloudDescriptionsInOneActorWriteAndKeepOcrAndLabels_When_ExecutedThroughActor() = runBlocking<Unit> {
        val rig = CloudRig()
        repo.saveAsset(rig.putImage("d1").copy(ocrText = "printed", autoLabels = listOf("receipt")))
        repo.saveAsset(rig.putImage("d2"))
        repo.saveAsset(rig.putImage("d3"))
        repo.updateCloudDescription(dev.stapler.stelekit.asset.AssetUuid("d1"), "A receipt", "CLOUD_CLAUDE")
        repo.updateCloudDescription(dev.stapler.stelekit.asset.AssetUuid("d2"), "A dog", "CLOUD_CLAUDE")
        assertEquals(2L, repo.countCloudDescriptions().getOrNull())
        val actor = DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository())

        assertTrue(actor.execute { repo.clearCloudDescriptions() }.isRight())

        assertEquals(0L, repo.countCloudDescriptions().getOrNull())
        val d1 = repo.getAssetByUuid(dev.stapler.stelekit.asset.AssetUuid("d1")).first().getOrNull()!!
        assertEquals(null, d1.cloudDescription)
        assertEquals("NONE", d1.mlTagsSource)
        assertEquals("printed", d1.ocrText)
        assertEquals(listOf("receipt"), d1.autoLabels)
    }
}
