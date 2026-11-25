// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.platform.SketchSaveOutcome
import dev.stapler.stelekit.sketch.v2.export.DrawingScale
import dev.stapler.stelekit.sketch.v2.export.Orientation
import dev.stapler.stelekit.sketch.v2.export.PaperSize
import dev.stapler.stelekit.sketch.v2.export.SheetSetup
import dev.stapler.stelekit.sketch.v2.export.SvgImageSource
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportFormat
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportRequest
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportSource
import dev.stapler.stelekit.sketch.v2.runtime.export.PngExporter
import dev.stapler.stelekit.sketch.v2.runtime.export.SketchExportRunner
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.units.LengthFormat
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** E7: the export dialog's page section as data: the suggested setup, the fit check, the tiling choice and what is remembered on the sheet. */
class ExportPageModelTest {
    private val runner = SketchExportRunner(PngExporter(), DrawListBuilder(FixedAdvanceTextMetricsProvider()))

    private fun scene(widthFeet: Int, heightFeet: Int): SketchSceneV2 =
        SketchSceneV2.empty().add(filledRectEl("room", 0, 0, widthFeet * UNITS_PER_FOOT, heightFeet * UNITS_PER_FOOT)).getOrNull()!!

    private fun source(scene: SketchSceneV2, format: LengthFormat = LengthFormat.FEET_INCHES, setup: SheetSetup? = null) =
        ExportSource(scene, emptySet(), SvgImageSource.None, baseName = "plan", lengthFormat = format, sheetSetup = setup)

    @Test
    fun aPdfRequest_should_StartFromASuggestedSetupThatFitsTheDrawing() {
        val model = ExportDialogModel(source(scene(30, 10)), runner)
        val request = model.normalised(ExportRequest(format = ExportFormat.Pdf))
        val setup = assertNotNull(request.sheet)
        assertEquals(PaperSize.Letter, setup.paper)
        assertEquals(Orientation.Landscape, setup.orientation, "a wide drawing goes on a landscape page")
        val preview = assertIs<ExportPreview.Page>(model.preview(request))
        assertTrue(preview.plan.fits)
        assertTrue(model.fitCheck(preview.plan).text.startsWith("Fits at ${setup.scale.label}"), model.fitCheck(preview.plan).text)
    }

    @Test
    fun aMetricSheet_should_SuggestA4AndAMetricScale() {
        val model = ExportDialogModel(source(scene(30, 10), LengthFormat.METRIC_MM), runner)
        val setup = assertNotNull(model.normalised(ExportRequest(format = ExportFormat.Pdf)).sheet)
        assertEquals(PaperSize.A4, setup.paper)
        assertTrue(setup.scale.label.contains(':'), setup.scale.label)
    }

    @Test
    fun theSheetsStoredSetup_should_BeWhatAnExportStartsFrom() {
        val stored = SheetSetup(PaperSize.A3, Orientation.Landscape, 10_000, DrawingScale.of(1, 100))
        val model = ExportDialogModel(source(scene(30, 10), setup = stored), runner)
        assertEquals(stored, model.normalised(ExportRequest(format = ExportFormat.Pdf)).sheet)
        assertEquals(stored, model.withPage(ExportRequest(format = ExportFormat.Svg), true).sheet)
        assertEquals(null, model.withPage(ExportRequest(format = ExportFormat.Svg, sheet = stored), false).sheet)
    }

    @Test
    fun anOverflowingDrawing_should_BeFlagged_WithTheScaleThatFits_AndOfferTilingOnlyForAPdf() {
        val model = ExportDialogModel(source(scene(55, 36)), runner)
        val setup = SheetSetup(scale = DrawingScale.of(1, 48))
        val pdf = ExportRequest(format = ExportFormat.Pdf, sheet = setup)
        val preview = assertIs<ExportPreview.Page>(model.preview(pdf))
        val check = model.fitCheck(preview.plan)
        assertFalse(check.fits)
        assertTrue(check.text.startsWith("Does not fit at 1/4\" = 1'"), check.text)
        assertNotNull(check.suggestion)
        assertTrue(model.usesTiling(pdf))
        assertFalse(model.usesTiling(pdf.copy(format = ExportFormat.Svg)), "an SVG is one page")
        assertTrue(model.normalised(pdf.copy(tiled = true)).tiled)
        val fitted = model.fitted(pdf.copy(tiled = true))
        assertFalse(fitted.tiled)
        assertTrue(assertIs<ExportPreview.Page>(model.preview(fitted)).plan.fits)
        assertFalse(model.usesTiling(fitted))
        assertFalse(model.normalised(fitted.copy(tiled = true)).tiled, "tiling clears itself once the drawing fits")
    }

    @Test
    fun tilingMoreThanTheBudget_should_BlockExportWithAReason() {
        val model = ExportDialogModel(source(scene(2000, 2000)), runner)
        val request = ExportRequest(format = ExportFormat.Pdf, sheet = SheetSetup(scale = DrawingScale.of(1, 1)), tiled = true)
        val preview = assertIs<ExportPreview.Page>(model.preview(request))
        assertTrue(preview.plan.tooManyPages)
        assertTrue(preview.blocked)
        assertFalse(ExportUiState.Open(request, preview).canExport)
        assertTrue(ExportUiState.Open(request.copy(tiled = false), preview.copy(tiled = false)).canExport, "one clipped page is still allowed")
    }

    @Test
    fun theController_should_RememberTheSheetSetup_AndSaveAPdf() {
        val remembered = mutableListOf<SheetSetup>()
        val files = FakeSketchFiles().also { it.outcome = { SketchSaveOutcome.Saved("plan.pdf", "Downloads", "/tmp/plan.pdf") } }
        val src = source(scene(30, 10))
        val controller = ExportDialogController({ src }, runner, files, {}, dispatcher = Dispatchers.Unconfined, rememberSheet = { remembered += it })
        controller.open()
        val open = assertIs<ExportUiState.Open>(controller.state.value)
        assertTrue(remembered.isEmpty(), "opening changes nothing")
        controller.update(open.request.copy(format = ExportFormat.Pdf))
        assertEquals(1, remembered.size, "the suggested setup is remembered when the PDF is chosen")
        val chosen = assertNotNull(remembered.single())
        controller.update(assertIs<ExportUiState.Open>(controller.state.value).request.copy(sheet = chosen.copy(scale = DrawingScale.of(1, 96))))
        assertEquals(DrawingScale.of(1, 96), remembered.last().scale)
        controller.export()
        assertEquals(listOf("plan.pdf"), files.saved)
    }

    private companion object {
        const val UNITS_PER_FOOT = 192
    }
}
