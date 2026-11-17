// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.platform.SketchSaveOutcome
import dev.stapler.stelekit.sketch.v2.export.SvgImageSource
import dev.stapler.stelekit.sketch.v2.library.LibraryItem
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportFormat
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportRequest
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportScope
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportSource
import dev.stapler.stelekit.sketch.v2.runtime.export.PngExporter
import dev.stapler.stelekit.sketch.v2.runtime.export.SketchExportRunner
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchImportExportStrings.Export as Strings
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The PNG encoder needs Skia, which Robolectric does not provide, so the save-path tests here export SVG; the PNG bytes are
 * `SketchExportRunnerPngTest`'s (jvmTest).
 *
 * Story 5.2.3 (UX-57, UX-58, UX-114): the export dialog's surface. Robolectric. Gradle-only: composes @Composable lambdas, which the
 * Bazel android target cannot compile (see BUILD.bazel).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w480dp-h900dp-xhdpi")
class ExportDialogTest {
    @get:Rule
    val rule = createComposeRule()

    private val runner = SketchExportRunner(PngExporter(), DrawListBuilder(FixedAdvanceTextMetricsProvider()))
    private val notices = mutableListOf<SketchNotice>()
    private val files = FakeSketchFiles()

    private fun u(v: Int) = SceneUnit.ofInt(v)

    private fun rect(id: String, x: Int, y: Int, w: Int = 10, h: Int = 10) = RectElement(ElementBase(ElementId(id)), u(x), u(y), u(w), u(h))

    private fun scene(vararg elements: SceneElement, layers: List<Layer> = emptyList()): SketchSceneV2 {
        val base = SketchSceneV2.empty().let { if (layers.isEmpty()) it else it.withLayers(layers) }
        return elements.fold(base) { s, e -> s.add(e).fold({ error("$it") }, { it }) }
    }

    private fun show(
        scene: SketchSceneV2,
        selection: Set<ElementId> = emptySet(),
        csv: (() -> ByteArray)? = null,
        library: List<LibraryItem> = emptyList(),
    ): ExportDialogController {
        val source = ExportSource(scene, selection, SvgImageSource.None, library, csv, baseName = "architecture")
        val controller = ExportDialogController({ source }, runner, files, { notices += it }, dispatcher = Dispatchers.Unconfined)
        controller.open()
        rule.setContent { ExportDialog(controller) }
        rule.waitForIdle()
        return controller
    }

    @Test
    fun dialog_should_ListEveryFormat_WithPngDefaultAndTheScaleBackgroundAndStyleOptions() {
        show(scene(rect("a", 0, 0, 200, 100)))

        rule.onNodeWithTag(ExportTags.DIALOG).assertIsDisplayed()
        for (format in ExportFormat.entries) rule.onNodeWithTag(ExportTags.format(format)).assertIsDisplayed()
        for (scale in 1..3) rule.onNodeWithTag(ExportTags.scale(scale)).assertIsDisplayed()
        rule.onNodeWithTag(ExportTags.BACKGROUND).assertIsDisplayed()
        rule.onNodeWithTag(ExportTags.style(dev.stapler.stelekit.sketch.v2.runtime.export.ExportStyle.AsDrawn)).assertIsDisplayed()
        rule.onNodeWithTag(ExportTags.style(dev.stapler.stelekit.sketch.v2.runtime.export.ExportStyle.Clean)).assertIsDisplayed()
        rule.onNodeWithTag(ExportTags.PREVIEW).assertTextContains("Preview: ", substring = true)
    }

    @Test
    fun csvAndLibrary_should_BeDisabledWithTheirReasons_UntilTheSheetHasDimensionsAndTheLibraryHoldsItems() {
        show(scene(rect("a", 0, 0)))
        rule.onNodeWithTag(ExportTags.format(ExportFormat.Csv)).assertIsNotEnabled()
        rule.onNode(hasText(Strings.CSV_DISABLED)).assertIsDisplayed()
        rule.onNodeWithTag(ExportTags.format(ExportFormat.Library)).assertIsNotEnabled()
        rule.onNode(hasText(Strings.LIBRARY_EMPTY)).assertIsDisplayed()
    }

    @Test
    fun selectionScope_should_BeDisabledWithAReason_WhenNothingIsSelected() {
        show(scene(rect("a", 0, 0)))
        rule.onNodeWithTag(ExportTags.scope(ExportScope.Selection)).assertIsNotEnabled()
        rule.onNode(hasText(Strings.SELECTION_DISABLED)).assertIsDisplayed()
    }

    @Test
    fun excalidrawFormat_should_ShowTheFlattenNoticeOnlyWhenTheSheetHasTwoLayers() {
        val layers = listOf(Layer.DefaultLayer, Layer(LayerId("two"), "Layer 2", order = 1))
        show(scene(rect("a", 0, 0), layers = layers))
        rule.onNodeWithTag(ExportTags.FLATTEN).assertDoesNotExist()
        rule.onNodeWithTag(ExportTags.format(ExportFormat.Excalidraw)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ExportTags.FLATTEN).assertIsDisplayed().assertTextContains("Layers are flattened in the exported file (ADR-006).")
    }

    @Test
    fun excalidrawFormat_should_NotShowTheFlattenNoticeForOneLayer() {
        show(scene(rect("a", 0, 0)))
        rule.onNodeWithTag(ExportTags.format(ExportFormat.Excalidraw)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ExportTags.FLATTEN).assertDoesNotExist()
    }

    @Test
    fun anImpossiblePng_should_StayOpenWithTheReason_AndUseOneXFixesIt() {
        show(scene(rect("a", 0, 0), rect("b", 11_990, 7_990)))
        rule.onNodeWithTag(ExportTags.scale(3)).performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(ExportTags.EXPORT).performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(ExportTags.DIALOG).assertIsDisplayed()
        rule.onNodeWithTag(ExportTags.ERROR).assertIsDisplayed()
        rule.onNode(hasText("px wide. Choose 1x or export a frame.", substring = true)).assertIsDisplayed()
        rule.onNodeWithTag(ExportTags.EXPORT).assertIsNotEnabled()
        rule.onNodeWithTag(ExportTags.CANCEL).assertIsDisplayed()

        rule.onNodeWithTag(ExportTags.USE_1X).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ExportTags.ERROR).assertDoesNotExist()
        rule.onNodeWithTag(ExportTags.EXPORT).assertIsEnabled()
        assertTrue(files.saved.isEmpty())
    }

    @Test
    fun aBlockedDownload_should_SayWhy_AndTryAgainSavesAndCloses() {
        files.outcome = { SketchSaveOutcome.Blocked }
        val controller = show(scene(rect("a", 0, 0)))
        rule.onNodeWithTag(ExportTags.format(ExportFormat.Svg)).performClick()
        rule.onNodeWithTag(ExportTags.EXPORT).performClick()
        rule.waitForIdle()

        rule.onNode(hasText(Strings.BLOCKED)).assertIsDisplayed()
        rule.onNodeWithTag(ExportTags.TRY_AGAIN).assertIsDisplayed()

        files.outcome = { SketchSaveOutcome.Saved("architecture.png", "Downloads", null) }
        rule.onNodeWithTag(ExportTags.TRY_AGAIN).performClick()
        rule.waitForIdle()
        assertEquals(ExportUiState.Closed, controller.state.value)
        rule.onNodeWithTag(ExportTags.DIALOG).assertDoesNotExist()
        assertEquals(listOf(Strings.EXPORT_SAVED_KEY), notices.map { it.key })
    }

    @Test
    fun anUnwritableTarget_should_OfferChooseAnotherLocation() {
        files.outcome = { SketchSaveOutcome.Unwritable }
        show(scene(rect("a", 0, 0)))
        rule.onNodeWithTag(ExportTags.format(ExportFormat.Svg)).performClick()
        rule.onNodeWithTag(ExportTags.EXPORT).performClick()
        rule.waitForIdle()

        rule.onNode(hasText(Strings.UNWRITABLE)).assertIsDisplayed()
        rule.onNodeWithTag(ExportTags.ANOTHER_LOCATION).assertIsDisplayed()
    }

    @Test
    fun cancel_should_CloseTheDialogWithoutAToast() {
        val controller = show(scene(rect("a", 0, 0)))
        rule.onNodeWithTag(ExportTags.CANCEL).performClick()
        rule.waitForIdle()

        assertEquals(ExportUiState.Closed, controller.state.value)
        assertTrue(notices.isEmpty() && files.saved.isEmpty())
        rule.onNodeWithTag(ExportTags.DIALOG).assertDoesNotExist()
    }

    @Test
    fun request_should_FollowTheOptionsTheUserPicks() {
        val controller = show(scene(rect("a", 0, 0)), selection = setOf(ElementId("a")), library = listOf(LibraryItem("l", "n", 0, emptyList(), 0)))
        rule.onNodeWithTag(ExportTags.format(ExportFormat.Svg)).performClick()
        rule.onNodeWithTag(ExportTags.scope(ExportScope.Whole)).performClick()
        rule.waitForIdle()
        assertEquals(ExportRequest(format = ExportFormat.Svg, scope = ExportScope.Whole), (controller.state.value as ExportUiState.Open).request.copy(scale = 2))
    }
}
