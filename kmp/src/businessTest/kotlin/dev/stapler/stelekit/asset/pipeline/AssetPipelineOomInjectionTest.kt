@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.asset.pipeline

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.ml.ImageLabeler
import dev.stapler.stelekit.platform.ml.Label
import dev.stapler.stelekit.repository.AssetRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.wiring.FakeSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Injected OutOfMemoryError at three points: the plugin, the image port inside a real on-device
 * plugin (stands in for the downscale/decode step, which U8 does not have yet), and the
 * `ocr_text` write path. In each case the asset ends failed, the next asset still processes, no
 * Throwable escapes the service, and the run marker is cleared by the clean stop.
 */
class AssetPipelineOomInjectionTest {
    private val repo = InMemoryAssetRepository()
    private val actor = DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository())
    private val settings = FakeSettings()
    private val marker = PipelineRunMarker(settings, "test-process")

    private fun asset(id: String, type: AssetMediaType = AssetMediaType.IMAGE) = AssetEntry(
        uuid = AssetUuid(id), filePath = "/g/assets/$id", relativePath = "../assets/$id",
        mediaType = type, subfolder = "assets", tags = emptyList(), autoLabels = emptyList(),
        ocrText = null, cloudDescription = null, pageUuids = emptyList(), sizeBytes = 1L,
        importedAtMs = if (id == "bad") 1L else 2L, mlProcessed = false, mlAttemptedAt = null, mlFailed = false,
        contentHash = null, isOrphan = false, mlTagsSource = "NONE",
    )

    private suspend fun runDrain(plugin: AssetPipelinePlugin, repository: AssetRepository = repo) {
        repo.saveAsset(asset("bad"))
        repo.saveAsset(asset("good"))
        val service = AssetPipelineService(PluginRegistry().apply { register(plugin) }, repository, actor, runMarker = marker)
        service.scheduleBackfill()
        withTimeout(30_000) { service.progress.first { it is PipelineProgress.Finished } }
        service.shutdown()
    }

    private suspend fun assertBadFailedGoodProcessed() {
        val bad = repo.getAssetByUuid(AssetUuid("bad")).first().getOrNull()!!
        val good = repo.getAssetByUuid(AssetUuid("good")).first().getOrNull()!!
        assertTrue(bad.mlFailed, "the OOM asset is marked failed")
        assertFalse(bad.mlProcessed)
        assertTrue(good.mlProcessed, "the pipeline continued past the OOM")
        assertEquals("", settings.getString(PipelineRunMarker.MARKER_KEY, ""), "the clean stop cleared the run marker")
    }

    private class OomOnBad(private val whenId: (AssetEntry) -> Boolean) : AssetPipelinePlugin {
        override val id = "oom"
        override fun canProcess(asset: AssetEntry) = true
        override suspend fun processAsset(asset: AssetEntry): Either<DomainError, AssetPipelineResult> {
            if (whenId(asset)) throw OutOfMemoryError("injected")
            return AssetPipelineResult.Labels(listOf("ok")).right()
        }
    }

    @Test
    fun oomFromProcessAsset_marksFailed_andContinues() = runBlocking<Unit> {
        runDrain(OomOnBad { it.uuid.value == "bad" })
        assertBadFailedGoodProcessed()
    }

    @Test
    fun oomFromTheImagePort_insideTheOnDevicePlugin_marksFailed_andContinues() = runBlocking<Unit> {
        val fs = FakeFileSystem().apply {
            writeFileBytes("/g/assets/bad", byteArrayOf(1))
            writeFileBytes("/g/assets/good", byteArrayOf(2))
        }
        val labeler = object : ImageLabeler {
            override suspend fun labelImage(imageBytes: ByteArray): Either<DomainError, List<Label>> {
                if (imageBytes[0] == 1.toByte()) throw OutOfMemoryError("injected decode")
                return listOf(Label("cat", 0.9f)).right()
            }
        }
        runDrain(OnDeviceLabelingPlugin(labeler, fs))
        assertBadFailedGoodProcessed()
    }

    @Test
    fun oomFromTheOcrTextWritePath_marksFailed_andContinues() = runBlocking<Unit> {
        val oomOnOcrWrite = object : AssetRepository by repo {
            override suspend fun updateOcrText(uuid: AssetUuid, ocrText: String?): Either<DomainError, Unit> {
                if (uuid.value == "bad") throw OutOfMemoryError("injected write")
                return repo.updateOcrText(uuid, ocrText)
            }
        }
        runDrain(OomOnBad { false }, oomOnOcrWrite)
        assertBadFailedGoodProcessed()
    }
}
