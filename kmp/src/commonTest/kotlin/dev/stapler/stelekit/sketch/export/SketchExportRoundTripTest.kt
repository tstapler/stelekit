// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.export

import dev.stapler.stelekit.fusion.DimensionAliases
import dev.stapler.stelekit.fusion.DimensionEntry
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.RefTable
import dev.stapler.stelekit.sketch.SketchDocument
import dev.stapler.stelekit.sketch.SketchPoint
import dev.stapler.stelekit.sketch.SketchPrimitive
import dev.stapler.stelekit.sketch.SketchScene
import dev.stapler.stelekit.sketch.Zone
import dev.stapler.stelekit.sketch.ZoneSet
import dev.stapler.stelekit.sketchimport.LabelBinder
import dev.stapler.stelekit.sketchimport.readSvg
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.Rational
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SketchExportRoundTripTest {
    private fun pt(x: Long, y: Long) = SketchPoint(Inches(x), Inches(y))

    private fun doc(left: String, right: String, total: String? = null): SketchDocument {
        val scene = SketchScene.EMPTY
            .add(SketchPrimitive.Line("l1", pt(0, 0), pt(640, 0))).getOrNull()!!
            .add(SketchPrimitive.Rect("r1", pt(640, 100), pt(100, 40), "a&b<c")).getOrNull()!!
        val zones = listOfNotNull(
            Zone("zone.left", left),
            Zone("zone.right", right),
            total?.let { Zone("zone.total", it, isTotal = true) },
        )
        return SketchDocument(scene = scene, zones = ZoneSet(zones))
    }

    private val registry = DimensionRegistry(
        dimensions = listOf("zone.left", "zone.right", "zone.total").map {
            DimensionEntry(it, DimensionKind.LENGTH, it, DimensionAliases(svgLabel = listOf(it)))
        },
    )

    @Test
    fun exportedSvgReadsBackWithDimensionLabelsBoundToTheirKeys() {
        val svg = SketchSvgWriter.write(doc("33 7/16\"", "2' 3\"", "5' 9 7/16\""))
        val read = readSvg(svg)
        assertTrue(read.unsupported.isEmpty(), "${read.unsupported}")
        assertEquals(1, read.lines.size)
        assertEquals(1, read.rects.size)
        val bound = LabelBinder(registry, RefTable()).bind(read, "export").bound.associateBy { it.dimensionKey }
        assertEquals(Rational(535, 16), bound.getValue("zone.left").value)
        assertEquals(Rational(27), bound.getValue("zone.right").value)
        assertEquals(Rational(1111, 16), bound.getValue("zone.total").value)
    }

    @Test
    fun viewBoxIsIntegerSixteenthsThatContainsTheDrawing() {
        val read = readSvg(SketchSvgWriter.write(doc("12\"", "12\"")))
        val (x, y, w, h) = read.viewBox!!.split(' ').map { it.toLong() }
        assertTrue(x <= 0 && y <= 0)
        assertTrue(x + w >= 640 && y + h >= 100 + 40)
    }

    @Test
    fun metadataComesFirstWithTheSniffableMarkerAndCarriesExactDimensions() {
        val svg = SketchSvgWriter.write(doc("12\"", "6\""))
        assertTrue(svg.indexOf(SketchSvgWriter.MARKER) in 0 until HEAD_BYTES)
        val meta = readSvg(svg).metadata
        assertNotNull(meta)
        assertTrue(meta.contains("\"sixteenths\":192"), meta)
        assertTrue(meta.contains("\"sixteenths\":96"), meta)
    }

    @Test
    fun exportedSvgIsClassifiedAsADrawingWhateverItsPathOrMime() {
        val bytes = SketchSvgWriter.write(doc("12\"", "6\"")).encodeToByteArray()
        assertTrue(dev.stapler.stelekit.asset.DrawingContentSniffer.isSketchExport(bytes, "photo.svg", "image/svg+xml"))
    }

    @Test
    fun dimensionGroupsCarryDataAttributes() {
        val svg = SketchSvgWriter.write(doc("12\"", "6\""))
        assertTrue(svg.contains("""<g data-dim-id="zone.left" data-value="1&apos; 0&quot;" data-source="typed">"""), svg)
    }

    @Test
    fun layerNamesAreEscapedAndNothingIdentifyingLeaks() {
        val svg = SketchSvgWriter.write(doc("12\"", "6\""))
        assertTrue(svg.contains("data-layer=\"a&amp;b&lt;c\""), svg)
        assertFalse(svg.contains("/home") || svg.contains(".md") || svg.contains("file:"))
        assertTrue(readSvg(svg).unsupported.isEmpty())
    }

    @Test
    fun hiddenLayersAreLeftOut() {
        val base = doc("12\"", "6\"")
        val hidden = base.copy(sheet = base.sheet.copy(layers = listOf(dev.stapler.stelekit.sketch.SketchLayer("a&b<c", visible = false))))
        val read = readSvg(SketchSvgWriter.write(hidden))
        assertEquals(0, read.rects.size)
        assertEquals(1, read.lines.size)
    }

    @Test
    fun emptySheetStillWritesAValidDocument() {
        val read = readSvg(SketchSvgWriter.write(SketchDocument()))
        assertTrue(read.unsupported.isEmpty())
        assertNotNull(read.viewBox)
    }

    @Test
    fun anyWholeSixteenthLengthSurvivesTheRoundTripExactly() = runTest {
        checkAll(200, Arb.int(1..100_000)) { sixteenths ->
            val text = dev.stapler.stelekit.units.LengthFormatter.format(Inches(sixteenths.toLong()), dev.stapler.stelekit.units.LengthFormat.FEET_INCHES)
            val read = readSvg(SketchSvgWriter.write(doc(text, "1\"")))
            val bound = LabelBinder(registry, RefTable()).bind(read, "p").bound.associateBy { it.dimensionKey }
            assertEquals(Rational(sixteenths.toLong(), 16), bound.getValue("zone.left").value)
        }
    }

    @Test
    fun csvRowsMatchTheDimensionTable() {
        val csv = SketchCsvWriter.write(doc("12\"", "6\"", "18\"").exportDimensions())
        val lines = csv.trimEnd().split("\r\n")
        assertEquals(SketchCsvWriter.HEADER, lines[0])
        assertEquals(4, lines.size)
        assertTrue(lines[1].startsWith("zone.left,zone.left,"), lines[1])
    }

    @Test
    fun csvFieldsAreEscaped() {
        assertEquals("plain", SketchCsvWriter.textField("plain"))
        assertEquals("\"a,b\"", SketchCsvWriter.textField("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", SketchCsvWriter.textField("say \"hi\""))
        assertEquals("\"l1\nl2\"", SketchCsvWriter.textField("l1\nl2"))
        assertEquals("'=SUM(A1)", SketchCsvWriter.textField("=SUM(A1)"))
        assertEquals("\"'@x,y\"", SketchCsvWriter.textField("@x,y"))
    }

    @Test
    fun csvLeavesSixteenthsEmptyWhenUnknown() {
        val d = ExportDimension("k", "k", "", null, "under-constrained", "0")
        assertEquals("k,k,,,under-constrained,0", SketchCsvWriter.write(listOf(d)).trimEnd().split("\r\n")[1])
    }

    private companion object {
        const val HEAD_BYTES = 4096
    }
}
