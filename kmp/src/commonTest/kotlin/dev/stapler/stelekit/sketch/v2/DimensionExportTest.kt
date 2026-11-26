// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.scene.VerticalAlign
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.GroupId
import dev.stapler.stelekit.sketch.v2.tools.RectSpec
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.Arrowhead
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import arrow.core.getOrElse
import dev.stapler.stelekit.sketch.v2.annotation.AnnotationText
import dev.stapler.stelekit.sketch.v2.annotation.DimensionAnnotationEngine
import dev.stapler.stelekit.sketch.v2.annotation.DimensionExport
import dev.stapler.stelekit.sketch.v2.annotation.DimensionExportOptions
import dev.stapler.stelekit.sketch.v2.annotation.DimensionsChoice
import dev.stapler.stelekit.sketch.v2.annotation.ExportInk
import dev.stapler.stelekit.sketch.v2.annotation.Measurements
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SheetDimensionsExtras
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.dimensionsOn
import dev.stapler.stelekit.sketch.v2.commands.withDimensionsOn
import dev.stapler.stelekit.sketch.v2.commands.withSheetKind
import dev.stapler.stelekit.sketch.v2.constraints.SheetKind
import dev.stapler.stelekit.sketch.v2.export.SvgExportOptions
import dev.stapler.stelekit.sketch.v2.export.SvgExporter
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.DrawOp
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportFormat
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportRequest
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportSource
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportStyle
import dev.stapler.stelekit.sketch.v2.runtime.export.PngExporter
import dev.stapler.stelekit.sketch.v2.runtime.export.SketchExportRunner
import dev.stapler.stelekit.sketch.v2.export.SvgImageSource
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.sketch.v2.tools.sp
import dev.stapler.stelekit.sketch.v2.tools.su
import dev.stapler.stelekit.ui.sketch.v2.DimensionAnnotationLayout
import dev.stapler.stelekit.ui.sketch.v2.DimensionPillModel
import dev.stapler.stelekit.ui.sketch.v2.PillCanvas
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** E1: persistent dimension annotations are in the exports, not only the editor overlay; they are the editor's own layout and the sheet's own flag. */
class DimensionExportTest {
    private val builder = DrawListBuilder(FixedAdvanceTextMetricsProvider())

    private fun scene(vararg elements: SceneElement): SketchSceneV2 = elements.fold(SketchSceneV2.empty()) { s, e -> s.add(e).getOrNull()!! }

    /** A 10 ft by 7 ft 6 in room with a table (4 ft by 2 ft) and a round rug (3 ft across), in sixteenths of an inch. */
    private fun room(dx: Int = 0, dy: Int = 0) = scene(
        filledRectEl("room", dx, dy, 1920, 1440),
        filledRectEl("table", dx + 400, dy + 400, 768, 384),
        EllipseElement(ElementBase(ElementId("rug")), su(dx + 1500), su(dy + 1000), su(240), su(240)),
    )

    private fun svg(scene: SketchSceneV2, options: DimensionExportOptions? = DimensionExportOptions(), background: String? = "#ffffff") =
        SvgExporter.export(scene, builder, RenderStyle.Clean, options = SvgExportOptions(background = background, dimensions = options))

    private fun texts(ops: List<DrawOp>) = ops.filterIsInstance<DrawOp.TextRun>().flatMap { it.lines.map { l -> l.text } }

    @Test
    fun svg_should_EqualItsGolden_When_ADimensionedRoomIsExported() {
        assertEquals(DimensionExportGolden.ROOM_SVG, svg(room()))
    }

    @Test
    fun svg_should_BeUnchanged_When_DimensionsAreNotRequested() {
        val plain = SvgExporter.export(room(), builder, RenderStyle.Clean, options = SvgExportOptions(background = "#ffffff"))
        assertEquals(plain, svg(room(), options = null))
        assertFalse(plain.contains("10' 0\""), "no annotation text without the option")
    }

    @Test
    fun svg_should_CarryEveryShapesValues_When_DimensionsAreOn() {
        val out = svg(room())
        for (value in listOf("10' 0\"", "7' 6\"", "4' 0\"", "2' 0\"", "Ø 2' 6\"")) assertTrue(out.contains(value), "missing $value in the export")
    }

    @Test
    fun export_should_IgnoreTheViewport_And_MoveWithTheSheet() {
        val a = DimensionExport.layout(room())
        val b = DimensionExport.layout(room())
        assertEquals(a.map { it.text to it.textRect }, b.map { it.text to it.textRect }, "same shapes, same picture")
        val moved = DimensionExport.layout(room(dx = 3200, dy = -480))
        assertEquals(a.map { it.elementId to it.kind to it.text }, moved.map { it.elementId to it.kind to it.text })
        for ((x, y) in a.zip(moved)) {
            assertEquals(x.textRect.left + 3200, y.textRect.left, 0.001)
            assertEquals(x.textRect.top - 480, y.textRect.top, 0.001)
            assertEquals(x.fontSize, y.fontSize, 0.0001)
        }
    }

    @Test
    fun ink_should_FollowTheBackground_When_ItIsDarkLightOrTransparent() {
        assertEquals(ExportInk.DARK, ExportInk.forBackground("#ffffff"))
        assertEquals(ExportInk.DARK, ExportInk.forBackground(null))
        assertEquals(ExportInk.DARK, ExportInk.forBackground("not a colour"))
        assertEquals(ExportInk.LIGHT, ExportInk.forBackground("#000000"))
        assertEquals(ExportInk.LIGHT, ExportInk.forBackground("#1e1e1e"))
        assertEquals(ExportInk.LIGHT, ExportInk.forBackground("#123"))
        val dark = svg(room(), DimensionExportOptions(ink = ExportInk.forBackground("#1e1e1e")), background = "#1e1e1e")
        assertTrue(dark.contains("fill=\"${ExportInk.LIGHT}\""), "light value text on a dark sheet")
        assertTrue(dark.contains("stroke=\"${ExportInk.LIGHT}\""), "light lines on a dark sheet")
        val light = svg(room())
        assertTrue(light.contains("fill=\"${ExportInk.DARK}\"") && light.contains("stroke=\"${ExportInk.DARK}\""))
    }

    @Test
    fun layout_should_AnnotateAtMostTheCapOfShapes_When_TheSheetHoldsMore() {
        val cap = DimensionAnnotationEngine.MAX_ANNOTATED_SHAPES
        val total = cap + 60
        val grid = (0 until total).map { i -> filledRectEl("r$i", (i % 20) * 4000, (i / 20) * 4000, 3200, 3200) }
        val big = grid.fold(SketchSceneV2.empty()) { s, e -> s.add(e).getOrNull()!! }
        val ids = DimensionExport.layout(big, fitDp = 60_000.0).map { it.elementId }.toSet()
        assertEquals(cap, ids.size, "a sheet of $total large shapes annotates exactly the cap")
        val out = svg(big)
        assertTrue(out.startsWith("<svg"), "an over-cap sheet still exports")
    }

    @Test
    fun excalidrawElements_should_BeAddedUpToTheSceneCap_WithoutFailing() {
        val dims = DimensionExport.layout(room())
        val withElements = DimensionExport.withElements(room(), dims, ExportInk.DARK)
        assertTrue(withElements.size > room().size)
        assertTrue(withElements.layer(dev.stapler.stelekit.sketch.v2.scene.LayerId(DimensionExport.LAYER_ID)) != null)
    }

    @Test
    fun valuesInTheExport_should_EqualTheEditorsModel_ForAnyShapes() = runTest {
        checkAll(Arb.int(1..40), Arb.int(1..40), Arb.int(1..40), Arb.int(2..16)) { w, h, d, zoomTenths ->
            val s = scene(
                filledRectEl("a", 0, 0, w * 96, h * 96),
                filledRectEl("b", w * 96 + 900, 0, d * 64, h * 40 + 16),
                EllipseElement(ElementBase(ElementId("c")), su(w * 40 + 1400), su(h * 96 + 900), su(d * 24 + 16), su(d * 24 + 16)),
                LineElement(ElementBase(ElementId("l")), listOf(sp(0, h * 96 + 600), sp(w * 96 + d * 32, h * 96 + 600))),
            )
            val exported = DimensionExport.layout(s)
            for (e in exported) {
                val element = s[e.elementId]!!
                val measure = Measurements.of(element).first { it.kind == e.kind }
                assertEquals(measure.sixteenths, e.sixteenths)
                assertEquals(AnnotationText.value(e.kind, measure.sixteenths), e.text)
                assertEquals(DimensionPillModel.specs(element).first { it.kind.name == e.kind.name }.sixteenths, e.sixteenths)
            }
            val zoom = zoomTenths / 50.0
            val canvas = PillCanvas({ x, y -> (x * zoom + 20) to (y * zoom + 20) }, zoom, 900.0, 900.0)
            val shown = DimensionAnnotationLayout.layout(s.elementsInOrder, s.elementsInOrder, canvas, emptyList(), 1.0, 44.0)
            for (a in shown) {
                val same = exported.firstOrNull { it.elementId == a.elementId && it.kind.name == a.kind.name } ?: continue
                assertEquals(a.sixteenths, same.sixteenths)
                assertEquals(a.text, same.text)
            }
        }
    }

    @Test
    fun theSheetFlag_should_DefaultByKind_AndBeStoredOnlyOnceChosen() {
        val diagram = SketchDocumentV2()
        val technical = diagram.withSheetKind(SheetKind.Technical)
        assertNull(SheetDimensionsExtras.read(diagram.extras))
        assertFalse(diagram.dimensionsOn)
        assertTrue(technical.dimensionsOn)
        assertFalse(technical.withDimensionsOn(false).dimensionsOn)
        assertTrue(diagram.withDimensionsOn(true).dimensionsOn)
        assertEquals(technical, technical.withDimensionsOn(false).withDimensionsOn(null))
        assertEquals(SketchDocumentV2().extras, JsonObject(emptyMap()))
    }

    @Test
    fun theSheetFlag_should_SurviveTheCodec_AndLeaveExistingSheetsByteForByte() {
        val old = SketchDocumentV2(scene = room(), sheetUuid = "s1")
        val oldBytes = SheetCodecV2.encode(old).getOrElse { error("$it") }.decodeToString()
        assertFalse(oldBytes.contains(SheetDimensionsExtras.KEY), "a sheet that never chose carries no key")
        val chosen = old.withDimensionsOn(true)
        val bytes = SheetCodecV2.encode(chosen).getOrElse { error("$it") }
        assertTrue(bytes.decodeToString().contains("\"${SheetDimensionsExtras.KEY}\":true"))
        val back = SheetCodecV2.decode(bytes).getOrElse { error("$it") }
        assertEquals(true, SheetDimensionsExtras.read(back.extras))
        assertEquals(bytes.decodeToString(), SheetCodecV2.encode(back).getOrElse { error("$it") }.decodeToString(), "canonical JSON is stable")
        assertEquals(oldBytes, SheetCodecV2.encode(chosen.withDimensionsOn(null)).getOrElse { error("$it") }.decodeToString())
    }

    private val runner = SketchExportRunner(PngExporter(), builder)

    private fun runSvg(sheet: Boolean, choice: DimensionsChoice): String =
        runner.run(ExportSource(room(), emptySet(), SvgImageSource.None, sheetDimensions = sheet), ExportRequest(format = ExportFormat.Svg, style = ExportStyle.Clean, dimensions = choice))
            .getOrElse { error("$it") }.bytes.decodeToString()

    @Test
    fun theExportDefault_should_FollowTheSheet_AndAnExplicitChoiceShould_OverrideIt() {
        assertTrue(runSvg(sheet = true, choice = DimensionsChoice.Sheet).contains("10' 0\""))
        assertFalse(runSvg(sheet = false, choice = DimensionsChoice.Sheet).contains("10' 0\""))
        assertTrue(runSvg(sheet = false, choice = DimensionsChoice.On).contains("10' 0\""))
        assertFalse(runSvg(sheet = true, choice = DimensionsChoice.Off).contains("10' 0\""))
    }

    @Test
    fun excalidrawExport_should_CarryTheValues_When_TheSheetShowsThem() {
        val request = ExportRequest(format = ExportFormat.Excalidraw, dimensions = DimensionsChoice.Sheet)
        val withDims = runner.run(ExportSource(room(), emptySet(), SvgImageSource.None, sheetDimensions = true), request).getOrElse { error("$it") }.bytes.decodeToString()
        val without = runner.run(ExportSource(room(), emptySet(), SvgImageSource.None, sheetDimensions = false), request).getOrElse { error("$it") }.bytes.decodeToString()
        assertTrue(withDims.contains("10' 0") && !without.contains("10' 0"))
    }

    @Test
    fun drawOps_should_HaveOneStrokedPathAndOneTextRunPerAnnotation() {
        val dims = DimensionExport.layout(room())
        val ops = DimensionExport.drawOps(dims, ExportInk.DARK)
        assertEquals(dims.size, ops.filterIsInstance<DrawOp.PathOp>().size)
        assertEquals(dims.map { it.text }, texts(ops))
    }

    private fun onLayer(id: String, layer: String, group: String? = null) =
        ElementBase(ElementId(id), LayerId(layer), groupIds = listOfNotNull(group?.let { GroupId(it) }))

    /** A dimension as `build` writes it: a headed arrow and its value text in one group. */
    private fun mark(id: String, x0: Int, y0: Int, x1: Int, y1: Int, text: String? = "10' 0\"", head: Arrowhead = Arrowhead.Arrow): List<SceneElement> {
        val arrow = ArrowElement(onLayer("$id.line", "dimensions", id), listOf(sp(x0, y0), sp(x1, y1)), null, null, head, head)
        val label = text?.let {
            TextElement(onLayer("$id.text", "dimensions", id), su(x0), su(y0 - 90), su(300), su(80), 0.0, it, it, 64.0, 2, TextAlign.Center, VerticalAlign.Middle)
        }
        return listOfNotNull(arrow, label)
    }

    private fun layered(vararg elements: SceneElement): SketchSceneV2 {
        val layers = listOf(Layer(LayerId.Default, "Plan", order = 0), Layer(LayerId("openings"), "Openings", order = 1), Layer(LayerId("dimensions"), "Dimensions", order = 2))
        return elements.fold(SketchSceneV2.empty().withLayers(layers)) { s, e -> s.add(e).getOrNull()!! }
    }

    private fun values(scene: SketchSceneV2) = DimensionExport.layout(scene).map { it.elementId.value to it.text }

    private val bigRoom = filledRectEl("room", 0, 0, 1920, 1440)
    private val both = setOf("room" to "10' 0\"", "room" to "7' 6\"")

    private fun roomWith(mark: List<SceneElement>) = values(layered(bigRoom, *mark.toTypedArray())).toSet()

    @Test
    fun aMarksOwnParts_should_NotBeMeasured() {
        val extension = LineElement(onLayer("ext", "dimensions", "m"), listOf(sp(0, -48), sp(0, -432)))
        val ids = values(layered(bigRoom, extension, *mark("m", 0, -384, 1920, -384).toTypedArray())).map { it.first }
        assertEquals(setOf("room"), ids.toSet())
    }

    @Test
    fun aLength_should_NotBeRepeated_When_AMarkStatesIt() {
        assertEquals(setOf("room" to "7' 6\""), roomWith(mark("north", 0, -384, 1920, -384)))
        // Drawn right to left, on the other side: still the same stretch.
        assertEquals(setOf("room" to "7' 6\""), roomWith(mark("south", 1920, 1800, 0, 1800)))
    }

    @Test
    fun aLength_should_StillBeShown_When_NoMarkStatesIt() {
        assertEquals(both, roomWith(mark("short", 0, -384, 960, -384, "5' 0\"")))
        assertEquals(both, roomWith(mark("shifted", 400, -384, 2320, -384)))
        assertEquals(both, roomWith(mark("slanted", 0, -384, 1920, -500)))
        assertEquals(both, roomWith(mark("headless", 0, -384, 1920, -384, head = Arrowhead.None)))
        assertEquals(both, roomWith(mark("words", 0, -384, 1920, -384, "north wall")))
        assertEquals(both, roomWith(mark("bare", 0, -384, 1920, -384, text = null)))
    }

    @Test
    fun aMark_should_StateOnlyTheShapeItIsBeside() {
        val below = filledRectEl("below", 0, 6000, 1920, 960)
        val shown = values(layered(bigRoom, below, *mark("north", 0, -384, 1920, -384).toTypedArray()))
        assertTrue("below" to "10' 0\"" in shown, "$shown")
        assertFalse("room" to "10' 0\"" in shown, "$shown")
    }

    @Test
    fun aDiagonalLine_should_NotBeRepeated_When_ItsMarkIsOffsetStraightAcross() {
        val line = LineElement(onLayer("diagonal", "default"), listOf(sp(0, 0), sp(1536, 768)))
        val value = values(layered(line)).single().second
        assertTrue(values(layered(line, *mark("m", 0, 384, 1536, 1152, value).toTypedArray())).isEmpty())
    }

    @Test
    fun handDrawnLines_should_BeMeasured_OnTheOpeningsAndDimensionsLayers() {
        val a = LineElement(onLayer("a", "openings"), listOf(sp(0, 0), sp(1920, 0)))
        val b = LineElement(onLayer("b", "dimensions"), listOf(sp(0, 960), sp(1920, 960)))
        assertEquals(setOf("a", "b"), values(layered(a, b)).map { it.first }.toSet())
    }

    @Test
    fun anOpeningSymbol_should_ShowItsWidthOnce() {
        val gap = filledRectEl("gap", 400, 1403, 576, 74, RectSpec(layer = LayerId("openings"), group = "door"))
        val leaf = LineElement(onLayer("leaf", "openings", "door"), listOf(sp(400, 1440), sp(400, 864)))
        val symbol = values(layered(bigRoom, gap, leaf)).filter { it.first != "room" }
        assertEquals(listOf("gap" to "3' 0\""), symbol)
    }
}
