// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.captureRoboImage
import dev.stapler.stelekit.sketch.v2.export.DrawingScale
import dev.stapler.stelekit.sketch.v2.export.SheetSetup
import dev.stapler.stelekit.sketch.v2.export.SvgImageSource
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportFormat
import dev.stapler.stelekit.sketch.v2.runtime.export.PngExporter
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportSource
import dev.stapler.stelekit.sketch.v2.runtime.export.SketchExportRunner
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E7: the page section of the export dialog at phone width (360 dp wide; 1500 dp tall so the long dialog shows whole in one image). The tests assert the dialog's state (the fit check text, the
 * tiling choice, the remembered setup); the PNGs are written only when run with `-Proborazzi.test.record=true`, for looking at.
 * Robolectric. Gradle-only: composes @Composable lambdas, which the Bazel android target cannot compile (see BUILD.bazel).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h1500dp-xxhdpi")
class ExportDialogPageTest {
    @get:Rule
    val rule = createComposeRule()

    private val runner = SketchExportRunner(PngExporter(), DrawListBuilder(FixedAdvanceTextMetricsProvider()))
    private val files = FakeSketchFiles()
    private val remembered = mutableListOf<SheetSetup>()

    private fun room(widthFeet: Int, heightFeet: Int): SketchSceneV2 = SketchSceneV2.empty().add(
        RectElement(ElementBase(ElementId("room")), SceneUnit.ofInt(0), SceneUnit.ofInt(0), SceneUnit.ofInt(widthFeet * UNITS_PER_FOOT), SceneUnit.ofInt(heightFeet * UNITS_PER_FOOT)),
    ).getOrNull()!!

    private fun show(scene: SketchSceneV2): ExportDialogController {
        val source = ExportSource(scene, emptySet(), SvgImageSource.None, baseName = "plan")
        val controller = ExportDialogController({ source }, runner, files, {}, dispatcher = Dispatchers.Unconfined, rememberSheet = { remembered += it })
        controller.open()
        rule.setContent { ExportDialog(controller) }
        rule.waitForIdle()
        return controller
    }

    private fun open(controller: ExportDialogController) = controller.state.value as ExportUiState.Open

    private fun tap(tag: String) {
        // A semantic click, not a touch: the dialog scrolls, and a scroll-to inside its text-field column never goes idle under Robolectric.
        rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    @Test
    fun pdf_should_ShowThePageAndItsFitCheck_OnAPhone() {
        val controller = show(room(30, 10))
        tap(ExportTags.format(ExportFormat.Pdf))
        rule.onNodeWithTag(ExportPageTags.FIT).assertTextContains("Fits at", substring = true)
        rule.onNodeWithTag(ExportPageTags.PREVIEW).assertExists()
        val setup = assertNotNull(open(controller).request.sheet)
        assertEquals(ExportFormat.Pdf, open(controller).request.format)
        assertEquals(setup, remembered.single(), "the suggested setup is remembered with the sheet")
        rule.onNodeWithTag(ExportTags.DIALOG).captureRoboImage("build/outputs/roborazzi/export-page-1-fits.png")
    }

    @Test
    fun anOverflowingDrawing_should_WarnOfferTheFittingScaleAndTheTilingChoice() {
        val controller = show(room(55, 36))
        tap(ExportTags.format(ExportFormat.Pdf))
        tap(ExportPageTags.scale("1:48"))
        rule.onNodeWithTag(ExportPageTags.FIT).assertTextContains("Does not fit at 1/4\" = 1'", substring = true)
        rule.onNodeWithTag(ExportPageTags.SUGGESTION).assertTextContains("Fits at", substring = true)
        assertFalse(open(controller).request.tiled)
        tap(ExportPageTags.TILED)
        assertTrue(open(controller).request.tiled)
        rule.onNodeWithTag(ExportTags.DIALOG).captureRoboImage("build/outputs/roborazzi/export-page-2-overflow-tiled.png")

        tap(ExportPageTags.FIT_BUTTON)
        rule.onNodeWithTag(ExportPageTags.FIT).assertTextContains("Fits at", substring = true)
        rule.onNodeWithTag(ExportPageTags.TILED).assertDoesNotExist()
        assertFalse(open(controller).request.tiled)
    }

    @Test
    fun svg_should_OfferThePageAsAChoice() {
        val controller = show(room(10, 8))
        tap(ExportTags.format(ExportFormat.Svg))
        rule.onNodeWithTag(ExportPageTags.FIT).assertDoesNotExist()
        tap(ExportPageTags.PAGE_ON)
        assertNotNull(open(controller).request.sheet)
        tap(ExportPageTags.scale("1:48"))
        assertEquals(DrawingScale.of(1, 48), open(controller).request.sheet!!.scale)
        tap(ExportPageTags.TITLE_BLOCK)
        tap(ExportPageTags.NORTH)
        tap(ExportPageTags.north(90))
        assertEquals(90, open(controller).request.sheet!!.northDegrees)
        rule.onNodeWithTag(ExportTags.DIALOG).captureRoboImage("build/outputs/roborazzi/export-page-3-svg-furniture.png")
        tap(ExportPageTags.PAGE_ON)
        assertEquals(null, open(controller).request.sheet)
    }

    private companion object {
        const val UNITS_PER_FOOT = 192
    }
}
