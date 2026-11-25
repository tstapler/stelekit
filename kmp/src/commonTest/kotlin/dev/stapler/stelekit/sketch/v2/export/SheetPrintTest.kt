// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.export

import arrow.core.getOrElse
import dev.stapler.stelekit.sketch.v2.annotation.DimensionsChoice
import dev.stapler.stelekit.sketch.v2.index.Bounds
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.DrawOp
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportFormat
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportRequest
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportSource
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportStyle
import dev.stapler.stelekit.sketch.v2.runtime.export.PngExporter
import dev.stapler.stelekit.sketch.v2.runtime.export.SketchExportRunner
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.sketch.v2.tools.sp
import dev.stapler.stelekit.units.LengthFormat
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.element
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E7: a sheet on paper. The SVG states its real-world size and scale; a length measured on the page in physical units is the
 * drawn length times the scale; the tiles of a big drawing cover it exactly once; and the PDF is deterministic, well formed and
 * carries the same numbers the SVG has.
 */
class SheetPrintTest {
    /** A fresh runner per use: the draw-list builder caches by element id and version, and these tests reuse one id for different lines. */
    private val runner get() = SketchExportRunner(PngExporter(), DrawListBuilder(FixedAdvanceTextMetricsProvider()))

    private fun lineScene(units: Int): SketchSceneV2 =
        SketchSceneV2.empty().add(LineElement(ElementBase(ElementId("line")), listOf(sp(0, 0), sp(units, 0)))).getOrNull()!!

    private fun source(scene: SketchSceneV2, format: LengthFormat = LengthFormat.FEET_INCHES) =
        ExportSource(scene, emptySet(), dev.stapler.stelekit.sketch.v2.export.SvgImageSource.None, baseName = "plan", lengthFormat = format)

    private fun export(scene: SketchSceneV2, setup: SheetSetup, format: ExportFormat = ExportFormat.Svg, tiled: Boolean = false, lengths: LengthFormat = LengthFormat.FEET_INCHES): ByteArray =
        runner.run(source(scene, lengths), ExportRequest(format = format, sheet = setup, dimensions = DimensionsChoice.Off, tiled = tiled, style = ExportStyle.Clean)).getOrElse { error("$it") }.bytes

    private fun svg(scene: SketchSceneV2, setup: SheetSetup, lengths: LengthFormat = LengthFormat.FEET_INCHES) = export(scene, setup, lengths = lengths).decodeToString()

    private val twoPoint = Regex("""<path d="M(-?[0-9.]+) (-?[0-9.]+) L(-?[0-9.]+) (-?[0-9.]+)"""")

    /** The line's length as the page says it, in inches: the viewBox length times the physical width over the viewBox width. */
    private fun measuredInches(svg: String): Double {
        val width = Regex("""<svg[^>]* width="([0-9.]+)(in|mm)"""").find(svg) ?: error(svg.take(600))
        val inches = width.groupValues[1].toDouble() / if (width.groupValues[2] == "mm") MM_PER_INCH else 1.0
        val box = Regex("""viewBox="([-0-9.]+) ([-0-9.]+) ([-0-9.]+) ([-0-9.]+)"""").find(svg)!!.groupValues.drop(1).map { it.toDouble() }
        val line = (twoPoint.find(svg) ?: error(svg.take(1800))).groupValues.drop(1).map { it.toDouble() }
        return abs(line[2] - line[0]) * inches / box[2]
    }

    @Test
    fun aTenFootLine_should_MeasureTwoAndAHalfInches_When_ExportedAtAQuarterInchToTheFoot() {
        val setup = SheetSetup(scale = DrawingScale.parse("1/4\" = 1'")!!, scaleBar = false)
        val out = svg(lineScene(TEN_FEET), setup)
        assertEquals(2.5, measuredInches(out), 1e-6)
        assertTrue(out.contains("width=\"8.5in\" height=\"11in\" data-sketch-scale=\"1:48\""), out.take(300))
        assertTrue(out.contains("viewBox=\"0 0 136 176\""))
    }

    @Test
    fun physicalLength_should_BeTheDrawnLengthTimesTheScale_ForEveryPresetAndLength() = runTest {
        val scales = ScalePresets.architectural + ScalePresets.metric
        checkAll(Arb.int(1..MAX_UNITS), Arb.element(scales)) { units, preset ->
            val setup = SheetSetup(scale = preset.scale, scaleBar = false)
            val expected = units / 16.0 * preset.scale.paper / preset.scale.real
            val out = svg(lineScene(units), setup)
            assertEquals(expected, measuredInches(out), 1e-3, "${preset.label}, $units units: ${out.take(900)}")
        }
    }

    @Test
    fun aMetricLine_should_MeasureItsMillimetresAtOneToFifty_OnA4() {
        val setup = SheetSetup(PaperSize.A4, Orientation.Landscape, 10_000, DrawingScale.parse("1:50")!!, scaleBar = false)
        val out = svg(lineScene(METRIC_5M_UNITS), setup, LengthFormat.METRIC_MM)
        assertTrue(out.contains("width=\"297mm\" height=\"210mm\""), out.take(300))
        val mm = measuredInches(out) * 25.4
        assertEquals(METRIC_5M_UNITS * 1.5875 / 50, mm, 0.01)
        assertEquals(100.0, mm, 0.1)
    }

    @Test
    fun oneToOne_should_BeTheIdentity_AndATwoToOneScaleEnlarges() {
        assertEquals(3.0, measuredInches(svg(lineScene(THREE_INCHES), SheetSetup(scale = DrawingScale.of(1, 1), scaleBar = false))), 1e-6)
        assertEquals(6.0, measuredInches(svg(lineScene(THREE_INCHES), SheetSetup(scale = DrawingScale.of(2, 1), scaleBar = false))), 1e-6)
    }

    @Test
    fun anSvgWithoutASheetSetup_should_BeTheExistingExport() {
        val plain = runner.run(source(lineScene(TEN_FEET)), ExportRequest(format = ExportFormat.Svg, dimensions = DimensionsChoice.Off)).getOrElse { error("$it") }.bytes.decodeToString()
        assertFalse(plain.contains("data-sketch"))
        assertTrue(Regex("""<svg [^>]* width="[0-9.]+" height="[0-9.]+" viewBox""").containsMatchIn(plain), "numeric size, as before")
    }

    @Test
    fun theScaleBar_should_BeDrawnAtTheSheetsScale() = runTest {
        val scales = ScalePresets.architectural + ScalePresets.metric
        checkAll(Arb.element(scales), Arb.element(listOf(false, true))) { preset, metric ->
            val setup = SheetSetup(scale = preset.scale, scaleBar = true)
            val plan = SheetPlanner.plan(setup, Bounds(0.0, 0.0, 100.0, 100.0), metric)
            val ops = PageComposer.compose(plan, plan.tiles.first(), emptyList(), false, "#000000")
            val segments = ops.filterIsInstance<DrawOp.PathOp>().filter { it.elementId.value.startsWith("page.bar.") }
            val label = ops.filterIsInstance<DrawOp.TextRun>().first { it.elementId.value == "page.bar.end" }.lines.single().text
            if (segments.isEmpty()) return@checkAll
            val xs = segments.flatMap { it.segments.filterIsInstance<dev.stapler.stelekit.sketch.v2.render.PathSegment.MoveTo>().map { m -> m.x } } +
                segments.flatMap { it.segments.filterIsInstance<dev.stapler.stelekit.sketch.v2.render.PathSegment.LineTo>().map { l -> l.x } }
            val pageLength = xs.max() - xs.min()
            val realUnits = realUnitsOf(label)
            assertEquals(realUnits * preset.scale.factor, pageLength, 1e-6, "${preset.label}: bar labelled $label")
        }
    }

    /** The real length a bar label names, in scene units: `10'`, `6"`, `1/2"`, `50 mm`, `2 m`. */
    private fun realUnitsOf(label: String): Double = when {
        label.endsWith("'") -> label.removeSuffix("'").toDouble() * UNITS_PER_FOOT
        label.endsWith("\"") -> label.removeSuffix("\"").let { if (it.contains('/')) it.substringBefore('/').toDouble() / it.substringAfter('/').toDouble() else it.toDouble() } * UNITS_PER_INCH
        label.endsWith(" mm") -> label.removeSuffix(" mm").toDouble() / MM_PER_UNIT
        label.endsWith(" m") -> label.removeSuffix(" m").toDouble() * MM_PER_METRE / MM_PER_UNIT
        else -> error("unknown label $label")
    }

    @Test
    fun tiles_should_CoverTheDrawingExactlyOnce() = runTest {
        val papers = PaperSize.presets
        checkAll(Arb.int(1..MAX_DRAWING), Arb.int(1..MAX_DRAWING), Arb.element(papers), Arb.element(ScalePresets.architectural + ScalePresets.metric), Arb.int(0..SAMPLES)) { w, h, paper, preset, seed ->
            val setup = SheetSetup(paper, if (seed % 2 == 0) Orientation.Landscape else Orientation.Portrait, SheetSetup.defaultMargin(paper), preset.scale, scaleBar = seed % 3 == 0, titleBlock = if (seed % 5 == 0) TitleBlock(title = "T") else null)
            val plan = SheetPlanner.plan(setup, Bounds(0.0, 0.0, w.toDouble(), h.toDouble()), false)
            if (plan.tooManyPages) return@checkAll
            val tiles = plan.tiles
            assertEquals(plan.pageCount, tiles.size)
            assertEquals(plan.scaledWidth * plan.scaledHeight, tiles.sumOf { it.window.width * it.window.height }, 1e-6 * (1 + plan.scaledWidth * plan.scaledHeight))
            for (i in 0 until SAMPLES) {
                val px = plan.scaledWidth * ((i * GOLDEN) % 1.0)
                val py = plan.scaledHeight * ((i * GOLDEN * GOLDEN + seed) % 1.0)
                val hits = tiles.count { px >= it.window.x && px < it.window.right && py >= it.window.y && py < it.window.bottom }
                assertEquals(1, hits, "point ($px, $py) of ${plan.scaledWidth} x ${plan.scaledHeight} on ${plan.columns} x ${plan.rows} pages")
            }
            for (t in tiles) assertTrue(t.window.width <= plan.area.width + 1e-9 && t.window.height <= plan.area.height + 1e-9, "a tile fits one page")
        }
    }

    @Test
    fun theFitCheck_should_SayWhenItFits_AndOfferTheLargestScaleThatDoes() {
        val sixtyFeet = Bounds(0.0, 0.0, 60.0 * UNITS_PER_FOOT, 20.0 * UNITS_PER_FOOT)
        val quarter = SheetSetup(scale = DrawingScale.of(1, 48))
        val tight = SheetPlanner.plan(quarter, sixtyFeet, false)
        assertFalse(tight.fits)
        assertEquals(2, tight.columns)
        val best = assertNotNull(SheetPlanner.bestFit(quarter, sixtyFeet, false))
        assertTrue(SheetPlanner.plan(quarter.copy(scale = best), sixtyFeet, false).fits)
        val larger = ScalePresets.architectural.map { it.scale }.takeWhile { it != best }
        assertTrue(larger.none { SheetPlanner.plan(quarter.copy(scale = it), sixtyFeet, false).fits }, "no larger preset fits")
        assertTrue(SheetPlanner.plan(quarter, Bounds(0.0, 0.0, 10.0 * UNITS_PER_FOOT, 8.0 * UNITS_PER_FOOT), false).fits)
    }

    @Test
    fun aTiledPdf_should_WriteOnePagePerTile_AndANonTiledOneJustTheFirst() {
        val big = SketchSceneV2.empty().add(filledRectEl("room", 0, 0, 60 * UNITS_PER_FOOT_INT, 20 * UNITS_PER_FOOT_INT)).getOrNull()!!
        val setup = SheetSetup(scale = DrawingScale.of(1, 48), scaleBar = false)
        val tiled = export(big, setup, ExportFormat.Pdf, tiled = true).let(::latin1)
        val plan = assertNotNull(runner.plan(source(big), ExportRequest(format = ExportFormat.Pdf, sheet = setup, dimensions = DimensionsChoice.Off)))
        assertTrue(plan.pageCount > 1)
        assertEquals(plan.pageCount, Regex("/Type /Page /").findAll(tiled).count())
        val single = export(big, setup, ExportFormat.Pdf).let(::latin1)
        assertEquals(1, Regex("/Type /Page /").findAll(single).count())
    }

    private val goldenSetup = SheetSetup(titleBlock = TitleBlock("Kitchen", "T. Stapler", "2026-11-24", "A-1", "Cabinets 36 in deep"), northDegrees = 30)

    private fun goldenScene() = SketchSceneV2.empty().add(filledRectEl("room", 0, 0, 1920, 1440)).getOrNull()!!

    @Test
    fun theSampleSheetSvg_should_EqualItsGolden() {
        assertEquals(SheetPrintGolden.SVG, export(goldenScene(), goldenSetup).decodeToString())
    }

    @Test
    fun theSampleSheetPdf_should_EqualItsGoldenByteForByte() {
        val bytes = export(goldenScene(), goldenSetup, ExportFormat.Pdf)
        assertEquals(SheetPrintGolden.PDF, latin1(bytes))
        PdfChecks.assertWellFormed(bytes)
    }

    @Test
    fun aPdf_should_BeWellFormedAndDeterministic() {
        val setup = SheetSetup(scale = DrawingScale.of(1, 48), titleBlock = TitleBlock("Kitchen (north) \\ plan", "T. S.", "2026-11-24", "A-1", "Cabinets 36 in deep"), northDegrees = 30)
        val scene = SketchSceneV2.empty().add(filledRectEl("room", 0, 0, 1920, 1440)).getOrNull()!!
        val a = export(scene, setup, ExportFormat.Pdf)
        assertTrue(a.contentEquals(export(scene, setup, ExportFormat.Pdf)), "same inputs, same bytes")
        PdfChecks.assertWellFormed(a)
        val text = a.let(::latin1)
        assertTrue(text.startsWith("%PDF-1.4\n"))
        assertTrue(text.contains("/MediaBox [0 0 612 792]"), "Letter is 612 x 792 points")
        assertTrue(text.contains("(Kitchen \\(north\\) \\\\ plan)"), "title text is escaped")
        assertTrue(text.contains("/BaseFont /Helvetica"))
        assertFalse(text.contains("/CreationDate"), "nothing time dependent")
    }

    @Test
    fun aPdfPagesSize_should_FollowThePaper() {
        val scene = SketchSceneV2.empty().add(filledRectEl("room", 0, 0, 480, 480)).getOrNull()!!
        val a4 = export(scene, SheetSetup(PaperSize.A4, Orientation.Landscape, 10_000, DrawingScale.of(1, 50), scaleBar = false), ExportFormat.Pdf).let(::latin1)
        assertTrue(a4.contains("/MediaBox [0 0 841.8898 595.2756]"), a4.substringAfter("/MediaBox").take(40))
    }

    @Test
    fun pdfFonts_should_MeasureLikeTheBase14Metrics() {
        assertEquals(95, PdfFonts.tableSize)
        assertEquals((722 + 556 + 222 + 222 + 556) * 10 / 1000.0, PdfFonts.width("Hello", 10.0), 1e-9)
        assertEquals(6.0 * 5, PdfFonts.width("12345", 10.0, PdfFace.Courier) / 10 * 10, 1e-9)
        assertEquals("Hello", PdfFonts.fit("Hello", 10.0, 100.0))
        assertTrue(PdfFonts.fit("A very long title indeed", 10.0, 40.0).endsWith("..."))
    }

    private companion object {
        const val TEN_FEET = 1920
        const val THREE_INCHES = 48
        const val METRIC_5M_UNITS = 3150
        const val MAX_UNITS = 2400
        const val MAX_DRAWING = 40_000
        const val SAMPLES = 40
        const val GOLDEN = 0.6180339887
        const val MM_PER_INCH = 25.4
        const val MM_PER_UNIT = 1.5875
        const val MM_PER_METRE = 1000.0
        const val UNITS_PER_INCH = 16.0
        const val UNITS_PER_FOOT = 192.0
        const val UNITS_PER_FOOT_INT = 192
    }
}

/** A just-enough PDF reader: the cross-reference table must point at each object, and the trailer at the table. */
internal object PdfChecks {
    fun assertWellFormed(bytes: ByteArray) {
        val text = bytes.let(::latin1)
        assertTrue(text.endsWith("%%EOF\n"))
        val start = Regex("startxref\n([0-9]+)\n%%EOF").find(text)!!.groupValues[1].toInt()
        assertTrue(text.startsWith("xref\n", start), "startxref points at the table")
        val entries = Regex("([0-9]{10}) 00000 n ").findAll(text.substring(start)).map { it.groupValues[1].toInt() }.toList()
        assertTrue(entries.isNotEmpty())
        entries.forEachIndexed { i, offset -> assertTrue(text.startsWith("${i + 1} 0 obj\n", offset), "object ${i + 1} is at $offset") }
        val size = Regex("/Size ([0-9]+)").find(text.substring(start))!!.groupValues[1].toInt()
        assertEquals(entries.size + 1, size)
        for (m in Regex("/Length ([0-9]+)>>\nstream\n".replace(">>", " >>")).findAll(text)) {
            val length = m.groupValues[1].toInt()
            assertEquals("\nendstream", text.substring(m.range.last + 1 + length, m.range.last + 1 + length + "\nendstream".length))
        }
    }
}

/** The bytes as one char each, so a PDF's binary header and octal-escaped text read as the ASCII they are. */
internal fun latin1(bytes: ByteArray): String = String(CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() })
