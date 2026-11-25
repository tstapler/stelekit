// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.annotation.AnnotationCanvas
import dev.stapler.stelekit.sketch.v2.annotation.DimensionAnnotationEngine
import dev.stapler.stelekit.sketch.v2.annotation.MeasureKind
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.RotateSelection
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.importer.ExcalidrawExporter
import dev.stapler.stelekit.sketch.v2.importer.ExcalidrawImporter
import dev.stapler.stelekit.sketch.excalidraw.ImportSource
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.snap.RotateStep
import dev.stapler.stelekit.sketch.v2.snap.SnapSettings
import dev.stapler.stelekit.ui.sketch.v2.NonDragAction
import dev.stapler.stelekit.ui.sketch.v2.NonDragStart
import dev.stapler.stelekit.ui.sketch.v2.NonDragTransformMode
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** E9: the rotate handle, snap steps, typed angle, non-drag mode, annotations and round trips for rotated geometry. */
class RotateToolingTest {
    private val eps = 1e-6
    private val line = LineElement(ElementBase(elId("l")), listOf(sp(0, 0), sp(200, 0)))
    private val box = filledRectEl("a", 0, 100, 100, 60)

    private fun sceneWith(vararg e: dev.stapler.stelekit.sketch.v2.scene.SceneElement): SketchSceneV2 = sceneOfElements(*e)

    @Test
    fun rotateHandle_should_TurnALineSelectionRigidly_AsOneCommand() {
        val ctx = testToolContext(sceneWith(line, box))
        val tool = SelectTool(selection = listOf(elId("l"), elId("a")))
        val handle = assertNotNull(tool.handleLayout(ctx)).handles.single { it.handle == Handle.Rotate }
        val frame = assertNotNull(tool.selectionFrame(ctx))
        val cmd = runScenario(tool, ctx) { down(handle.centre.x.value.toInt(), handle.centre.y.value.toInt()); up((frame.box.cx + 300).toInt(), frame.box.cy.toInt()) }
            .commands.single()
        val rotate = assertIs<RotateSelectionCommand>(cmd)
        assertEquals(PI / 2.0, rotate.delta, eps)
        assertEquals(frame.box.cx, rotate.pivotX, eps)
        assertEquals(frame.box.cy, rotate.pivotY, eps)
    }

    @Test
    fun rotateHandle_should_ExistOnTouch_ForASelectionTooSmallForFullHandles() {
        val tiny = filledRectEl("tiny", 0, 0, 12, 12)
        val ctx = testToolContext(sceneWith(tiny), zoom = 1.0)
        val layout = assertNotNull(SelectTool(selection = listOf(elId("tiny")), touchPresent = true).handleLayout(ctx))
        assertEquals(HandleTier.Compact, layout.tier)
        assertTrue(layout.handles.any { it.handle == Handle.Rotate })
    }

    @Test
    fun rotateDrag_should_LockToTheSnapStep_UnlessCtrlHeld() {
        val snap = SnapSettings(rotateStep = RotateStep.Deg45)
        val slanted = LineElement(ElementBase(elId("s")), listOf(sp(0, 0), sp(200, 120)))
        val ctx = testToolContext(sceneWith(slanted)).copy(snap = snap)
        val tool = SelectTool(selection = listOf(elId("s")))
        val handle = assertNotNull(tool.handleLayout(ctx)).handles.single { it.handle == Handle.Rotate }
        val sx = handle.centre.x.value.toInt()
        val sy = handle.centre.y.value.toInt()
        // The handle sits straight above the centre (100, 60); drag to roughly 40 degrees clockwise.
        val target = { deg: Double -> (100 + 300 * sin(deg * PI / 180)).toInt() to (60 - 300 * cos(deg * PI / 180)).toInt() }
        val (tx, ty) = target(40.0)
        val snapped = assertIs<RotateSelectionCommand>(runScenario(tool, ctx) { down(sx, sy); up(tx, ty) }.commands.single())
        assertEquals(PI / 4.0, snapped.delta, eps)
        val free = assertIs<RotateSelectionCommand>(runScenario(tool, ctx) { down(sx, sy); up(tx, ty, mods = Modifiers(ctrl = true)) }.commands.single())
        assertTrue(abs(free.delta - PI / 4.0) > 0.01)
    }

    @Test
    fun snapSettings_should_OfferFifteenFortyFiveAndNinety_AndShiftNeverGoesBelowFifteen() {
        assertEquals(listOf(0, 15, 45, 90), RotateStep.entries.map { it.degrees })
        assertNull(SnapSettings().rotateStepRadians(shift = false, bypass = false))
        assertEquals(RotateStep.Deg15.radians, SnapSettings().rotateStepRadians(shift = true, bypass = false))
        assertEquals(RotateStep.Deg90.radians, SnapSettings(rotateStep = RotateStep.Deg90).rotateStepRadians(shift = true, bypass = false))
        assertNull(SnapSettings(rotateStep = RotateStep.Deg90).rotateStepRadians(shift = false, bypass = true))
    }

    @Test
    fun typedAngle_should_ParseDegreesAndBuildOneCommand() {
        assertEquals(30.0, RotatePlan.parseDegrees("30"))
        assertEquals(-12.5, RotatePlan.parseDegrees(" -12.5 deg "))
        assertEquals(45.0, RotatePlan.parseDegrees("45°"))
        assertNull(RotatePlan.parseDegrees("abc"))
        assertNull(RotatePlan.parseDegrees("1e999"))
        assertNull(RotatePlan.parseDegrees("999999"))
        val scene = sceneWith(line, box)
        val ids = listOf(elId("l"), elId("a"))
        val centre = assertNotNull(RotatePlan.command(scene, ids, 90.0, RotatePivot.Centre))
        assertEquals(PI / 2.0, centre.delta, eps)
        assertEquals(100.0, centre.pivotX, eps)
        assertEquals(80.0, centre.pivotY, eps)
        val corner = assertNotNull(RotatePlan.command(scene, ids, 90.0, RotatePivot.TopLeft))
        assertEquals(0.0, corner.pivotX, eps)
        assertEquals(0.0, corner.pivotY, eps)
        assertNull(RotatePlan.command(scene, ids, 0.0, RotatePivot.Centre))
        assertEquals(200.0 to 0.0, RotatePlan.pivotOf(scene, ids, RotatePivot.TopRight))
        assertEquals(200.0 to 160.0, RotatePlan.pivotOf(scene, ids, RotatePivot.BottomRight))
        assertEquals(0.0 to 160.0, RotatePlan.pivotOf(scene, ids, RotatePivot.BottomLeft))
        assertNull(RotatePlan.pivotOf(scene, emptyList(), RotatePivot.Centre))
        assertEquals(RotateStep.Free, RotateStep.Deg90.next())
        assertEquals(RotateStep.Deg15, RotateStep.Free.next())
    }

    @Test
    fun nonDragRotate_should_TurnALineSelection_AndNoLongerRefuseIt() {
        val scene = sceneWith(line, box)
        val started = NonDragTransformMode.begin(NonDragAction.Rotate, scene, listOf(elId("l"), elId("a")))
        val mode = assertIs<NonDragStart.Started>(started).mode.nudge(1, 0)
        val cmd = assertIs<RotateSelectionCommand>(mode.apply().command)
        assertEquals(10.0 * PI / 180.0, cmd.delta, eps)
    }

    @Test
    fun annotations_should_RunAlongARotatedBoxAndASlantedLine() {
        val canvas = AnnotationCanvas({ x, y -> x to y }, 1.0, 2000.0, 2000.0)
        val turned = RectElement(ElementBase(elId("r")), su(500), su(500), su(300), su(100), angle = PI / 6.0)
        val slanted = LineElement(ElementBase(elId("s")), listOf(sp(100, 100), sp(400, 300)))
        val placed = DimensionAnnotationEngine.layout(listOf(turned, slanted), listOf(turned, slanted), canvas, emptyList(), 1.0, 44.0)
        val width = placed.single { it.elementId == turned.id && it.kind == MeasureKind.Width }
        assertParallel(width.line.b.x - width.line.a.x, width.line.b.y - width.line.a.y, cos(PI / 6.0), sin(PI / 6.0))
        val length = placed.single { it.elementId == slanted.id }
        assertParallel(length.line.b.x - length.line.a.x, length.line.b.y - length.line.a.y, 300.0, 200.0)
    }

    private fun assertParallel(ax: Double, ay: Double, bx: Double, by: Double) {
        val cross = (ax * by - ay * bx) / (hypot(ax, ay) * hypot(bx, by))
        assertEquals(0.0, cross, 1e-9)
    }

    @Test
    fun rotatedGeometry_should_RoundTripThroughTheSheetCodecAndExcalidraw() = runTest {
        val text = TextElement(ElementBase(elId("t")), su(0), su(200), su(60), su(20), text = "hello")
        val ellipse = EllipseElement(ElementBase(elId("e")), su(300), su(300), su(40), su(20))
        val doc = SketchDocumentV2(sceneWith(line, box, text, ellipse), sheetUuid = "11111111-1111-4111-8111-111111111111")
        val ids = doc.scene.elementsInOrder.map { it.id }
        val turned = Transaction.begin(doc).apply(RotateSelection(ids, PI / 6.0, 50.0, 50.0)).getOrNull()?.doc ?: error("rotate failed")

        val back = SheetCodecV2.decode(SheetCodecV2.encode(turned).getOrNull() ?: error("encode")).getOrNull() ?: error("decode")
        assertEquals(turned.scene.elementsInOrder, back.scene.elementsInOrder)

        val exported = ExcalidrawExporter().export(turned.scene).getOrNull() ?: error("export")
        val imported = ExcalidrawImporter().import(ImportSource.ofBytes(exported.text.encodeToByteArray())).getOrNull() ?: error("import")
        val byId = imported.elements.associateBy { it.id }
        assertEquals((turned.scene[elId("a")] as RectElement).angle, (byId.getValue(elId("a")) as RectElement).angle, eps)
        assertEquals((turned.scene[elId("t")] as TextElement).angle, (byId.getValue(elId("t")) as TextElement).angle, eps)
        assertEquals((turned.scene[elId("e")] as EllipseElement).angle, (byId.getValue(elId("e")) as EllipseElement).angle, eps)
        val sent = (turned.scene[elId("l")] as LineElement).points
        val got = (byId.getValue(elId("l")) as LineElement).points
        sent.indices.forEach { i ->
            assertEquals(sent[i].x.value, got[i].x.value, 1e-3)
            assertEquals(sent[i].y.value, got[i].y.value, 1e-3)
        }
    }
}
