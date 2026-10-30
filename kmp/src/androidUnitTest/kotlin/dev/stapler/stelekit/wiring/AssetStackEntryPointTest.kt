// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.wiring

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.asset.AssetIndexProgress
import dev.stapler.stelekit.asset.pipeline.AssetBuildFlags
import dev.stapler.stelekit.asset.pipeline.PlatformCaps
import dev.stapler.stelekit.asset.pipeline.PluginEnableKeys
import dev.stapler.stelekit.platform.ml.ImageLabeler
import dev.stapler.stelekit.platform.ml.Label
import dev.stapler.stelekit.platform.ml.PdfTextExtractor
import dev.stapler.stelekit.platform.ml.TextRecognizer
import java.util.concurrent.atomic.AtomicInteger
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.service.AttachmentResult
import dev.stapler.stelekit.service.MediaAttachmentService
import dev.stapler.stelekit.ui.AppState
import dev.stapler.stelekit.ui.GraphContentAssetStack
import dev.stapler.stelekit.ui.MemoryPressureListeners
import dev.stapler.stelekit.ui.rememberGraphContentAssetStack
import dev.stapler.stelekit.ui.rememberGraphContentGraphIoStack
import dev.stapler.stelekit.ui.rememberGraphContentVaultSetup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Story 4.1 / 4.2 wiring proof (Robolectric, in-memory backend, see [WiredAppFixture.android] for why):
 * the real `rememberGraphContentAssetStack` fills `asset_index` once the graph is fully loaded and the
 * Asset indexing switch is on, registers an attach, and stays inert while the switch is unset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AssetStackEntryPointTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val graphPath = "/test/wired-graph"
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
    private val dataDir: File = createTempDirectory("asset_stack_entry_").toFile()
    private var fixture: WiredAppFixture? = null

    @After
    fun tearDown() {
        AssetBuildFlags.internalBuild = false
        fixture?.let { runBlocking { it.close() } }
        dataDir.deleteRecursively()
    }

    private class FakeAttachments(private val fs: FakeFileSystem, private val bytes: ByteArray) : MediaAttachmentService {
        override suspend fun pickAndAttach(notesPath: EffectiveNotesPath, pageRelativePath: String): Either<DomainError, AttachmentResult> {
            fs.writeFileBytes(notesPath.child("assets/attached.png"), bytes)
            return AttachmentResult("../assets/attached.png", "attached.png").right()
        }
    }

    private class CountingLabeler : ImageLabeler {
        val calls = AtomicInteger()
        override suspend fun labelImage(imageBytes: ByteArray): Either<DomainError, List<Label>> {
            calls.incrementAndGet()
            return listOf(Label("cat", 0.9f)).right()
        }
    }

    private class CountingPdfExtractor : PdfTextExtractor {
        val calls = AtomicInteger()
        override suspend fun extractText(absoluteFilePath: String): Either<DomainError, String> {
            calls.incrementAndGet()
            return "Q3 report".right()
        }
    }

    private class CountingRecognizer : TextRecognizer {
        val calls = AtomicInteger()
        override suspend fun recognize(imageBytes: ByteArray): Either<DomainError, String> {
            calls.incrementAndGet()
            return "Invoice 4821".right()
        }
    }

    private val labeler = CountingLabeler()
    private val recognizer = CountingRecognizer()
    private val pdfExtractor = CountingPdfExtractor()
    private val pdf = "%PDF-1.4\n%%EOF".encodeToByteArray()

    private fun mount(
        switchOn: Boolean,
        loaded: Boolean = true,
        internalBuild: Boolean = true,
        pluginKeys: Set<String> = emptySet(),
        withPdf: Boolean = false,
    ): Triple<WiredAppFixture, MutableStateFlow<AppState>, () -> GraphContentAssetStack?> {
        AssetBuildFlags.internalBuild = internalBuild
        val fs = FakeFileSystem()
        repeat(3) { fs.writeFileBytes("$graphPath/assets/img$it.png", png) }
        if (withPdf) fs.writeFileBytes("$graphPath/assets/doc.pdf", pdf)
        val ports = FakePlatformPorts(fileSystem = fs)
        ports.settings.putBoolean(AssetBuildFlags.PIPELINE_ENABLED_KEY, switchOn)
        pluginKeys.forEach { ports.settings.putBoolean(PluginEnableKeys.keyFor(it), true) }
        val wired = runBlocking { WiredAppFixture.android(ApplicationProvider.getApplicationContext<Context>(), dataDir, ports, graphPath) }
        fixture = wired
        val appState = MutableStateFlow(AppState(currentGraphPath = graphPath, isFullyLoaded = loaded))
        val deps = wired.deps.copy(
            platformIntegrations = wired.deps.platformIntegrations.copy(
                attachmentService = FakeAttachments(fs, png + byteArrayOf(42)),
                platformCaps = PlatformCaps(labeler, pdfExtractor, recognizer),
            ),
        )
        var stack: GraphContentAssetStack? = null
        composeRule.setContent {
            val vault = rememberGraphContentVaultSetup(wired.graphManager, fs, null)
            val io = rememberGraphContentGraphIoStack(
                deps, vault.effectiveFileSystem, vault.activeGraphPath, vault.activeGraphInfo, Logger("AssetStackEntryPointTest"),
            )
            stack = rememberGraphContentAssetStack(
                deps, vault.effectiveFileSystem, io, appState, MemoryPressureListeners(), Logger("AssetStackEntryPointTest"),
            )
        }
        composeRule.waitForIdle()
        return Triple(wired, appState, { stack })
    }

    private fun rows(wired: WiredAppFixture) = runBlocking { wired.repos.assetRepository.countAssets().getOrNull() ?: -1L }

    /** The backfill runs on its own dispatcher; the effect that started it already ran inside `waitForIdle`. */
    private fun awaitIndexed(stack: GraphContentAssetStack?) = runBlocking {
        withTimeout(15_000) { assertNotNull(stack).indexing.progress.first { it is AssetIndexProgress.Finished } }
    }

    @Test
    fun switchOn_andGraphFullyLoaded_populatesAssetIndex_thenAttachIsRegisteredToo() {
        val (wired, _, stack) = mount(switchOn = true)

        awaitIndexed(stack())
        ObservableEffect.assert("asset-index-rows") { assertEquals(3L, rows(wired)) }

        val assets = assertNotNull(stack()?.attachmentService, "the attach path is wrapped when a platform service exists")
        runBlocking { assets.pickAndAttach(EffectiveNotesPath(graphPath), "") }
        assertEquals(4L, rows(wired))
        // The same bytes attached again are one row (idempotent on content hash).
        runBlocking { assets.pickAndAttach(EffectiveNotesPath(graphPath), "") }
        assertEquals(4L, rows(wired))
    }

    @Test
    fun switchUnset_leavesTheIndexEmpty_evenWhenTheGraphIsFullyLoaded() {
        val (wired, _, stack) = mount(switchOn = false)

        assertEquals(AssetIndexProgress.Idle, assertNotNull(stack()).indexing.progress.value, "the backfill was never started")
        assertEquals(0L, rows(wired))
        val attachments = assertNotNull(stack()?.attachmentService)
        runBlocking { attachments.pickAndAttach(EffectiveNotesPath(graphPath), "") }
        assertEquals(0L, rows(wired), "attach is not indexed while the switch is off")
    }

    @Test
    fun graphStillLoading_doesNotStartTheWalk_untilFullyLoaded() {
        val (wired, appState, stack) = mount(switchOn = true, loaded = false)

        assertEquals(AssetIndexProgress.Idle, assertNotNull(stack()).indexing.progress.value, "loadGraph must never overlap the backfill")
        assertEquals(0L, rows(wired))

        appState.value = appState.value.copy(isFullyLoaded = true)
        composeRule.waitForIdle()
        awaitIndexed(stack())
        assertEquals(3L, rows(wired))
    }

    @Test
    fun releaseVariant_neverStartsTheBackfill_evenIfTheKeyIsSet() {
        val (wired, _, stack) = mount(switchOn = true, internalBuild = false)

        assertEquals(AssetIndexProgress.Idle, assertNotNull(stack()).indexing.progress.value, "the backfill was never started")
        assertEquals(0L, rows(wired), "a build that hides the switch must not index, whatever the stored key says")
        assertEquals(false, assertNotNull(stack()).indexing.toggleVisible)
        assertEquals(null, stack()?.moveAsset, "Move is not offered when the switch is hidden")
    }

    private fun unprocessedRows(wired: WiredAppFixture) =
        runBlocking { wired.repos.assetRepository.countUnprocessedAssets().getOrNull() ?: -1L }

    @Test
    fun masterFlagOn_withEveryPluginKeyUnset_indexesButRunsNoPlugin() {
        val (wired, _, stack) = mount(switchOn = true, withPdf = true)

        awaitIndexed(stack())
        assertEquals(4L, rows(wired), "the PNGs and the PDF are indexed")
        assertEquals(4L, unprocessedRows(wired), "no asset is marked processed or failed")
        assertEquals(0, labeler.calls.get(), "the labeler is never called")
        assertEquals(0, pdfExtractor.calls.get(), "the PDF extractor is never called")
        assertEquals(0, recognizer.calls.get(), "the text recognizer is never called")
    }

    @Test
    fun masterFlagOn_withOnlyTheImageLabelsKey_labelsImagesAndLeavesPdfsAlone() {
        val (wired, _, stack) = mount(switchOn = true, pluginKeys = setOf(PluginEnableKeys.IMAGE_LABELS), withPdf = true)

        awaitIndexed(stack())
        runBlocking { withTimeout(15_000) { while (labeler.calls.get() < 3) kotlinx.coroutines.delay(20) } }
        runBlocking { withTimeout(15_000) { while (unprocessedRows(wired) > 1L) kotlinx.coroutines.delay(20) } }

        assertEquals(3, labeler.calls.get())
        assertEquals(0, pdfExtractor.calls.get(), "the PDF key is unset")
        assertEquals(1L, unprocessedRows(wired), "only the PDF stays unprocessed")
    }

    @Test
    fun masterFlagOn_withOnlyTheTextRecognitionKey_readsTextFromImagesAndNothingElse() {
        val (wired, _, stack) = mount(switchOn = true, pluginKeys = setOf(PluginEnableKeys.TEXT_RECOGNITION), withPdf = true)

        awaitIndexed(stack())
        runBlocking { withTimeout(15_000) { while (unprocessedRows(wired) > 1L) kotlinx.coroutines.delay(20) } }

        assertEquals(3, recognizer.calls.get())
        assertEquals(0, labeler.calls.get(), "the image-labels key is unset")
        assertEquals(0, pdfExtractor.calls.get(), "the PDF key is unset")
        val texts = runBlocking { wired.repos.assetRepository.getAssets(100, 0).first().getOrNull().orEmpty() }
        assertEquals(3, texts.count { it.ocrText == "Invoice 4821" && it.mlProcessed })
    }
}
