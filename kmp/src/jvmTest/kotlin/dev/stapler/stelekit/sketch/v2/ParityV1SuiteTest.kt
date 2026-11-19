// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: desktop-only
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.sketch.SheetProps
import dev.stapler.stelekit.sketch.SketchDocument
import dev.stapler.stelekit.sketch.SketchLayer
import dev.stapler.stelekit.sketch.SketchPoint
import dev.stapler.stelekit.sketch.SketchPrimitive
import dev.stapler.stelekit.sketch.SketchScene
import dev.stapler.stelekit.sketch.export.SketchSvgWriter
import dev.stapler.stelekit.sketch.v2.bridge.V1ToV2
import dev.stapler.stelekit.sketch.v2.bridge.V2ToV1
import dev.stapler.stelekit.sketch.v2.commands.SetLayerLocked
import dev.stapler.stelekit.sketch.v2.commands.SetLayerVisible
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.export.SvgExporter
import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionPolicy
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.snap.ObjectSnap
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.W6Kit.run
import dev.stapler.stelekit.units.Inches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 6.2.4b (requirements metric 6 and the SM-6 gate of `ParityV1SuiteTest`): each v1 sketch capability (the line and
 * rectangle tools, sixteenth-inch geometry, paint order, named layers with visible and locked flags, undo, snapping, SVG
 * export, and the zones and dimensions a sheet carries) is exercised through the v2 engine. A row is one `@Test` named in
 * [rows]; [everyRow_should_HaveATestHere] fails if a row has no method. The Hand tool and the other twelve tools are covered
 * by `ToolsEndToEndTest`; the A1 gate and the flag default are owner decisions, not asserted here.
 */
class ParityV1SuiteTest {
    private fun pt(x: Long, y: Long) = SketchPoint(Inches(x), Inches(y))

    private val v1Scene: SketchScene = listOf<SketchPrimitive>(
        SketchPrimitive.Rect("r1", pt(0, 0), pt(160, 96)),
        SketchPrimitive.Rect("r2", pt(400, 300), pt(250, 120), layer = "Walls"),
        SketchPrimitive.Line("l1", pt(160, 48), pt(250, 120), layer = "Walls"),
        SketchPrimitive.Line("l2", pt(7, 13), pt(-33, 91)),
    ).fold(SketchScene.EMPTY) { s, p -> s.add(p).getOrNull() ?: error("fixture add ${p.id}") }

    private val v1Sheet = SheetProps(layers = listOf(SketchLayer("Walls", visible = true, locked = true), SketchLayer("Notes", visible = false)))

    private fun lifted() = V1ToV2.lift(v1Scene, v1Sheet).getOrNull() ?: error("lift refused the v1 fixture")

    @Test
    fun lineTool_should_DrawALine_ThatProjectsToAV1Line() {
        val editor = ScenarioEditor()
        editor.pick(ToolId.Line)
        editor.drag(0, 0, 160, 48)
        val line = editor.scene.elementsInOrder.single()
        assertTrue(line is LineElement, "the Line tool made $line")
        val projected = V2ToV1.projectScene(editor.scene)
        assertEquals(1, projected.primitives.size, "the drawn line is representable in v1")
        assertTrue(projected.primitives.single() is SketchPrimitive.Line)
        assertEquals(0, projected.droppedCount)
    }

    @Test
    fun rectangleTool_should_DrawARectangle_ThatProjectsToAV1Rect() {
        val editor = ScenarioEditor()
        editor.pick(ToolId.Rectangle)
        editor.drag(16, 16, 176, 112)
        val box = editor.scene.elementsInOrder.single()
        assertTrue(box is RectElement, "the Rectangle tool made $box")
        val rect = V2ToV1.projectScene(editor.scene).primitives.single() as SketchPrimitive.Rect
        assertEquals(setOf(16L, 176L), setOf(rect.a.x.sixteenths, rect.b.x.sixteenths))
        assertEquals(setOf(16L, 112L), setOf(rect.a.y.sixteenths, rect.b.y.sixteenths))
    }

    @Test
    fun geometry_should_SurviveTheV2Engine_InWholeSixteenths() {
        val back = V2ToV1.projectScene(lifted())
        assertEquals(v1Scene.primitives, back.primitives, "every corner, endpoint, id and layer is the same number after the round trip")
        assertEquals(0, back.droppedCount)
    }

    @Test
    fun aRectangleDrawnRightToLeft_should_KeepItsCorners() {
        val reversed = v1Scene.primitives.single { it.id == "r2" } as SketchPrimitive.Rect
        val back = V2ToV1.projectScene(lifted()).primitives.single { it.id == "r2" } as SketchPrimitive.Rect
        assertEquals(reversed.a, back.a)
        assertEquals(reversed.b, back.b)
    }

    @Test
    fun paintOrder_should_BeTheOrderOfTheScene() {
        assertEquals(listOf("r1", "r2", "l1", "l2"), lifted().elementsInOrder.map { it.id.value })
        assertEquals(listOf("r1", "r2", "l1", "l2"), V2ToV1.projectScene(lifted()).primitives.map { it.id })
    }

    @Test
    fun namedLayers_should_KeepTheirNamesAndFlags() {
        val back = V2ToV1.projectScene(lifted())
        assertEquals(setOf("Walls", "Notes"), back.layers.map { it.name }.toSet() - setOf(""))
        assertEquals(SketchLayer("Walls", visible = true, locked = true), back.layers.single { it.name == "Walls" })
        assertEquals(SketchLayer("Notes", visible = false, locked = false), back.layers.single { it.name == "Notes" })
        assertTrue(back.primitives.filter { it.layer == "Walls" }.size == 2)
    }

    @Test
    fun layerVisibleAndLocked_should_BeEditableInV2_AndShowInTheProjection() {
        val walls = V1ToV2.layerId("Walls")
        val doc = run(SketchDocumentV2(scene = lifted()), SetLayerVisible(walls, false), SetLayerLocked(walls, false))
        val layer = V2ToV1.projectScene(doc.scene).layers.single { it.name == "Walls" }
        assertTrue(!layer.visible && !layer.locked, "hiding and unlocking Walls in v2 is what v1 would read")
    }

    @Test
    fun undo_should_RestoreTheSheetBeforeTheGesture() {
        val editor = ScenarioEditor()
        editor.pick(ToolId.Rectangle)
        editor.drag(16, 16, 176, 112)
        assertEquals(1, editor.scene.elementsInOrder.size)
        editor.undo()
        assertEquals(0, editor.scene.elementsInOrder.size, "one gesture is one undo step, as in v1")
    }

    @Test
    fun snapping_should_PullADraggedShapeToAnotherShapesEdge() {
        val scene = lifted()
        val index = SpatialIndex().also { it.rebuild(scene) }
        // r1's right edge sits at 163 after the move; the line l1 starts at 160, inside the 8 px reach.
        val near = ObjectSnap.snapMove(scene, index, setOf(ElementId("r1")), dx = 3.0, dy = 0.0, zoom = 1.0)
        assertEquals(0.0, near.dx, "the shape snaps back so its edge meets the other shape's edge")
        assertTrue(near.guides.isNotEmpty(), "and a guide is shown")
    }

    @Test
    fun svgExport_should_DrawEveryPrimitive_AndKeepTheV1BytesOfAnUnchangedSheet() {
        val before = SketchSvgWriter.write(SketchDocument(v1Scene, sheet = v1Sheet))
        val back = V2ToV1.projectScene(lifted())
        val after = SketchSvgWriter.write(SketchDocument(back.primitives.fold(SketchScene.EMPTY) { s, p -> s.add(p).getOrNull()!! }, sheet = v1Sheet))
        assertEquals(before, after, "export_v1(converted sheet) equals export_v1(original sheet)")

        val svg = SvgExporter.export(lifted(), DrawListBuilder(FixedAdvanceTextMetricsProvider()), RenderStyle.Clean)
        assertTrue(svg.startsWith("<svg") || svg.contains("<svg"), "the v2 export is an SVG document")
        assertTrue(Regex("<path").findAll(svg).count() >= v1Scene.size, "a path for each of the ${v1Scene.size} primitives")
    }

    @Test
    fun zonesAndDimensions_should_NotBlockConversion_BecauseV2EditsThem() {
        val bundle = SketchTestSupport.bundle(sheets = 2)
        assertTrue(bundle.dimensions.isNotEmpty(), "the fixture project carries a dimension")
        assertTrue(ConversionPolicy.TECHNICAL_DATA_EDITABLE)
        bundle.sheets.forEach { assertNull(ConversionPolicy.refusal(bundle, it), "refused ${it.uuid}") }
    }

    private val rows = listOf(
        "lineTool_should_DrawALine_ThatProjectsToAV1Line",
        "rectangleTool_should_DrawARectangle_ThatProjectsToAV1Rect",
        "geometry_should_SurviveTheV2Engine_InWholeSixteenths",
        "aRectangleDrawnRightToLeft_should_KeepItsCorners",
        "paintOrder_should_BeTheOrderOfTheScene",
        "namedLayers_should_KeepTheirNamesAndFlags",
        "layerVisibleAndLocked_should_BeEditableInV2_AndShowInTheProjection",
        "undo_should_RestoreTheSheetBeforeTheGesture",
        "snapping_should_PullADraggedShapeToAnotherShapesEdge",
        "svgExport_should_DrawEveryPrimitive_AndKeepTheV1BytesOfAnUnchangedSheet",
        "zonesAndDimensions_should_NotBlockConversion_BecauseV2EditsThem",
    )

    @Test
    fun v2Editor_should_ExerciseEveryV1Capability_When_ParitySuiteRun() {
        val tests = ParityV1SuiteTest::class.java.declaredMethods.map { it.name }.toSet()
        assertEquals(emptyList(), rows.filterNot { it in tests }, "a parity row has no test")
    }

    @Test
    fun everyRow_should_HaveATestHere() {
        val annotated = ParityV1SuiteTest::class.java.declaredMethods.filter { m -> m.annotations.any { it.annotationClass.simpleName == "Test" } }.map { it.name }
        assertEquals(rows.toSet(), annotated.toSet() - setOf("v2Editor_should_ExerciseEveryV1Capability_When_ParitySuiteRun", "everyRow_should_HaveATestHere"))
    }
}
