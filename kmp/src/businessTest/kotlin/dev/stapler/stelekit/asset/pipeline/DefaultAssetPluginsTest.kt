package dev.stapler.stelekit.asset.pipeline

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.ml.ImageLabeler
import dev.stapler.stelekit.platform.ml.Label
import dev.stapler.stelekit.platform.ml.NoOpTextRecognizer
import dev.stapler.stelekit.platform.ml.PdfTextExtractor
import dev.stapler.stelekit.platform.ml.TextRecognizer
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

/** Staged rollout: the master flag alone never constructs a plugin; each `asset_plugin_<id>_enabled` key adds one. */
class DefaultAssetPluginsTest {
    private val fs = FakeFileSystem()

    private class FakeLabeler : ImageLabeler {
        var calls = 0
        override suspend fun labelImage(imageBytes: ByteArray): Either<DomainError, List<Label>> {
            calls++
            return listOf(Label("cat", 0.9f)).right()
        }
    }

    private class FakePdf : PdfTextExtractor {
        var calls = 0
        override suspend fun extractText(absoluteFilePath: String): Either<DomainError, String> {
            calls++
            return "text".right()
        }
    }

    private class FakeRecognizer : TextRecognizer {
        var calls = 0
        override suspend fun recognize(imageBytes: ByteArray): Either<DomainError, String> {
            calls++
            return "Invoice 4821".right()
        }
    }

    private val recognizer = FakeRecognizer()
    private val labeler = FakeLabeler()
    private val pdf = FakePdf()
    private val caps = PlatformCaps(labeler, pdf, recognizer)

    private fun settingsWith(vararg ids: String) = FakeSettings().apply {
        ids.forEach { putBoolean(PluginEnableKeys.keyFor(it), true) }
    }

    private fun ids(settings: Settings, platform: PlatformCaps = caps) =
        defaultAssetPlugins(platform, settings, fs).map { it.id }

    @Test
    fun noKeySet_returnsNoPlugin_evenWithFullCapabilities() {
        assertEquals(emptyList(), ids(FakeSettings()))
    }

    @Test
    fun onlyTheImageLabelsKey_returnsOnlyTheLabelingPlugin() {
        assertEquals(listOf("on_device_labeling"), ids(settingsWith(PluginEnableKeys.IMAGE_LABELS)))
    }

    @Test
    fun bothKeys_returnLabelingThenPdfText_andNeverACloudPlugin() {
        val plugins = defaultAssetPlugins(caps, settingsWith(PluginEnableKeys.IMAGE_LABELS, PluginEnableKeys.PDF_TEXT), fs)
        assertEquals(listOf("on_device_labeling", "pdf_text"), plugins.map { it.id })
        assertTrue(plugins.none { it is CloudClaudePlugin || it is CloudVisionPlugin })
    }

    @Test
    fun textRecognitionKey_buildsOnlyTheOcrPlugin() {
        assertEquals(listOf("ocr"), ids(settingsWith(PluginEnableKeys.TEXT_RECOGNITION)))
    }

    @Test
    fun allThreeKeys_returnLabelingPdfThenOcr_andNeverACloudPlugin() {
        val all = settingsWith(PluginEnableKeys.IMAGE_LABELS, PluginEnableKeys.PDF_TEXT, PluginEnableKeys.TEXT_RECOGNITION)
        val plugins = defaultAssetPlugins(caps, all, fs)
        assertEquals(listOf("on_device_labeling", "pdf_text", "ocr"), plugins.map { it.id })
        assertTrue(plugins.none { it is CloudClaudePlugin || it is CloudVisionPlugin })
    }

    @Test
    fun noOpTextRecognizer_buildsNoOcrPlugin_evenWithTheKeySet() {
        val desktop = PlatformCaps(labeler, pdf, NoOpTextRecognizer())
        assertEquals(emptyList(), ids(settingsWith(PluginEnableKeys.TEXT_RECOGNITION), desktop))
    }

    @Test
    fun capabilityNone_buildsNoPlugin_evenWithKeysSet() {
        val all = settingsWith(PluginEnableKeys.IMAGE_LABELS, PluginEnableKeys.PDF_TEXT)
        assertEquals(emptyList(), ids(all, PlatformCaps.NONE))
    }

    @Test
    fun keyIsReReadOnEachCall_soAChangeNeedsNoRestart() {
        val settings = FakeSettings()
        assertEquals(emptyList(), ids(settings))
        settings.putBoolean(PluginEnableKeys.keyFor(PluginEnableKeys.PDF_TEXT), true)
        assertEquals(listOf("pdf_text"), ids(settings))
        settings.putBoolean(PluginEnableKeys.keyFor(PluginEnableKeys.PDF_TEXT), false)
        assertEquals(emptyList(), ids(settings))
    }

    @Test
    fun settingsReadFailure_countsAsOff() {
        val throwing = object : Settings {
            override fun getBoolean(key: String, defaultValue: Boolean): Boolean = error("boom")
            override fun putBoolean(key: String, value: Boolean) {}
            override fun getString(key: String, defaultValue: String): String = defaultValue
            override fun putString(key: String, value: String) {}
            override fun containsKey(key: String): Boolean = false
        }
        assertFalse(PluginEnableKeys.isEnabled(throwing, PluginEnableKeys.IMAGE_LABELS))
        assertEquals(emptyList(), ids(throwing))
    }

    @Test
    fun keyNameFollowsTheDocumentedPattern() {
        assertEquals("asset_plugin_image_labels_enabled", PluginEnableKeys.keyFor(PluginEnableKeys.IMAGE_LABELS))
    }

    // ── AssetPipelineService: inert when nothing is enabled (UT-164) ────────────────────────

    @OptIn(DirectRepositoryWrite::class)
    private fun asset(uuid: String, type: AssetMediaType) = AssetEntry(
        uuid = AssetUuid(uuid), filePath = "/g/assets/$uuid", relativePath = "../assets/$uuid",
        mediaType = type, subfolder = "assets", tags = emptyList(), autoLabels = emptyList(),
        ocrText = null, cloudDescription = null, pageUuids = emptyList(), sizeBytes = 1L,
        importedAtMs = 1L, mlProcessed = false, mlAttemptedAt = null, mlFailed = false,
        contentHash = null, isOrphan = false, mlTagsSource = "NONE",
    )

    @OptIn(DirectRepositoryWrite::class)
    @Test
    fun scheduleBackfill_withNoEnabledPlugin_startsNoDrain_andMarksNothing() = runBlocking<Unit> {
        val repo = InMemoryAssetRepository()
        repo.saveAsset(asset("img", AssetMediaType.IMAGE))
        repo.saveAsset(asset("doc", AssetMediaType.PDF))
        fs.writeFileBytes("/g/assets/img", byteArrayOf(1))
        val settings = FakeSettings()
        val service = AssetPipelineService(
            PluginRegistry(), repo, DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository()),
            pluginSource = { defaultAssetPlugins(caps, settings, fs) },
        )

        service.scheduleBackfill()

        assertEquals(2L, repo.countUnprocessedAssets().getOrNull(), "nothing marked processed or failed")
        assertEquals(0, labeler.calls)
        assertEquals(0, pdf.calls)
        service.shutdown()
    }

    @OptIn(DirectRepositoryWrite::class)
    @Test
    fun scheduleBackfill_rereadsKeys_andOnlyTouchesAssetsAnEnabledPluginHandles() = runBlocking<Unit> {
        val repo = InMemoryAssetRepository()
        repo.saveAsset(asset("img", AssetMediaType.IMAGE))
        repo.saveAsset(asset("doc", AssetMediaType.PDF))
        fs.writeFileBytes("/g/assets/img", byteArrayOf(1))
        val settings = FakeSettings()
        val service = AssetPipelineService(
            PluginRegistry(), repo, DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository()),
            pluginSource = { defaultAssetPlugins(caps, settings, fs) },
        )

        settings.putBoolean(PluginEnableKeys.keyFor(PluginEnableKeys.IMAGE_LABELS), true)
        service.scheduleBackfill()
        withTimeout(10_000) { service.progress.first { it is PipelineProgress.Finished } }

        assertEquals(1, labeler.calls)
        assertEquals(0, pdf.calls)
        val doc = repo.getAssetByUuid(AssetUuid("doc")).first().getOrNull()!!
        assertFalse(doc.mlProcessed, "the PDF has no enabled plugin, so enabling pdf_text later still processes it")
        assertFalse(doc.mlFailed)
        service.shutdown()
    }
}
