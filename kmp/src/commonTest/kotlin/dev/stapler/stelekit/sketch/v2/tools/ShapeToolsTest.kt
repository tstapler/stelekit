// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.2.1 (UX-08, UX-109): rectangle, diamond, ellipse and the line/polyline tool with point editing. */
class ShapeToolsTest {
    private val shift = Modifiers(shift = true)
    private val alt = Modifiers(alt = true)

    private fun added(r: ScenarioResult) = assertIs<AddElementCommand>(r.commands.single()).element

    @Test
    fun rectangle_should_CommitASquare_When_ShiftIsHeld() {
        val r = runScenario(RectangleTool()) { down(0, 0); move(100, 60, mods = shift); up(100, 60, mods = shift) }
        val rect = assertIs<RectElement>(added(r))
        assertEquals(100.0, rect.width.value)
        assertEquals(100.0, rect.height.value)
    }

    @Test
    fun rectangle_should_KeepTheDragDirection_When_SquaredTowardsTheUpperLeft() {
        val r = runScenario(RectangleTool()) { down(100, 100); up(40, 90, mods = shift) }
        val rect = assertIs<RectElement>(added(r))
        assertEquals(40.0, rect.x.value)
        assertEquals(40.0, rect.y.value)
        assertEquals(60.0, rect.width.value)
        assertEquals(60.0, rect.height.value)
    }

    @Test
    fun rectangle_should_DrawFromTheCentre_When_AltOrTheChipIsOn_ButNotAltWhereDisabled() {
        val viaAlt = assertIs<RectElement>(added(runScenario(RectangleTool()) { down(50, 50); up(70, 60, mods = alt) }))
        assertEquals(30.0, viaAlt.x.value)
        assertEquals(40.0, viaAlt.y.value)
        assertEquals(40.0, viaAlt.width.value)
        val viaChip = assertIs<RectElement>(added(runScenario(RectangleTool(ShapeOptions(fromCentre = true))) { down(50, 50); up(70, 60) }))
        assertEquals(viaAlt.copy(base = viaChip.base), viaChip)
        val linux = assertIs<RectElement>(added(runScenario(RectangleTool(ShapeOptions(altFromCentre = false))) { down(50, 50); up(70, 60, mods = alt) }))
        assertEquals(50.0, linux.x.value)
        assertEquals(20.0, linux.width.value)
    }

    @Test
    fun squareChip_should_ConstrainWithoutShift_ForATouchUser() {
        val rect = assertIs<RectElement>(added(runScenario(RectangleTool(ShapeOptions(square = true))) { down(0, 0); up(30, 10) }))
        assertEquals(30.0, rect.height.value)
    }

    @Test
    fun diamond_should_CommitAnAxisAlignedBox_And_ShiftMakesItSquare() {
        val plain = assertIs<DiamondElement>(added(runScenario(DiamondTool()) { down(10, 10); up(70, 40) }))
        assertEquals(60.0, plain.width.value)
        assertEquals(30.0, plain.height.value)
        val squared = assertIs<DiamondElement>(added(runScenario(DiamondTool()) { down(10, 10); up(70, 40, mods = shift) }))
        assertEquals(60.0, squared.height.value)
    }

    @Test
    fun ellipse_should_FitItsBoundingBox_And_ShiftMakesACircle() {
        val e = assertIs<EllipseElement>(added(runScenario(EllipseTool()) { down(0, 0); up(100, 60) }))
        assertEquals(50.0, e.cx.value)
        assertEquals(30.0, e.cy.value)
        assertEquals(50.0, e.rx.value)
        assertEquals(30.0, e.ry.value)
        val circle = assertIs<EllipseElement>(added(runScenario(EllipseTool()) { down(0, 0); up(100, 60, mods = shift) }))
        assertTrue(circle.isCircle)
        assertEquals(50.0, circle.rx.value)
    }

    @Test
    fun shapeTools_should_CommitNothing_When_TheDragIsAStrayClick() {
        for (tool in listOf(RectangleTool(), DiamondTool(), EllipseTool())) {
            val r = runScenario(tool) { down(5, 5); up(5, 6) }
            assertTrue(r.commands.isEmpty(), "${tool.id} committed a stray click")
        }
    }

    @Test
    fun shapeTools_should_ShowAPreviewMidDrag_And_CancelOnEscapeOrSecondPointer() {
        for (tool in listOf(RectangleTool(), DiamondTool(), EllipseTool())) {
            val mid = runScenario(tool) { down(0, 0); move(40, 40) }
            assertNotNull(mid.previews.last())
            val esc = runScenario(tool) { down(0, 0); move(40, 40); escape(); up(40, 40) }
            val second = runScenario(tool) { down(0, 0); down(9, 9, pointer = 1); up(40, 40) }
            assertTrue(esc.commands.isEmpty() && second.commands.isEmpty(), "${tool.id} did not cancel")
        }
    }

    @Test
    fun shapeTools_should_UseTheContextStyleAndFreshIds() {
        val r = runScenario(RectangleTool()) { down(0, 0); up(20, 20) }
        assertEquals("e0", added(r).id.value)
    }

    // ---- line ---------------------------------------------------------------------------------------------------

    private fun line(r: ScenarioResult) = assertIs<LineElement>(added(r))

    @Test
    fun line_should_CommitOneThreePointLine_When_ThreeClicksThenADoubleClick() {
        val r = runScenario(LineTool()) {
            down(0, 0); up(0, 0)
            down(40, 0); up(40, 0)
            down(40, 40); up(40, 40)
            down(40, 40); up(40, 40)
            down(40, 40); up(40, 40)
        }
        assertEquals(listOf(0.0 to 0.0, 40.0 to 0.0, 40.0 to 40.0), line(r).points.map { it.x.value to it.y.value })
        assertEquals(1, r.commands.size)
        assertTrue((r.finalTool as LineTool).placedPoints.isEmpty())
    }

    @Test
    fun line_should_ShowARubberBand_AfterTheFirstClick() {
        val r = runScenario(LineTool()) { down(0, 0); up(0, 0); move(30, 30) }
        val preview = assertNotNull(r.previews.last()).elements.single()
        assertEquals(2, assertIs<LineElement>(preview).points.size)
    }

    @Test
    fun line_should_DrawTwoPointLine_When_PressDragRelease() {
        val r = runScenario(LineTool()) { down(0, 0); move(30, 0); up(60, 0) }
        assertEquals(2, line(r).points.size)
        assertEquals(60.0, line(r).points.last().x.value)
    }

    @Test
    fun line_should_SnapToFifteenDegrees_When_ShiftIsHeld() {
        val r = runScenario(LineTool()) { down(0, 0); up(100, 20, mods = shift) }
        val (a, b) = line(r).points
        val angle = atan2(b.y.value - a.y.value, b.x.value - a.x.value)
        val steps = angle / (PI / 12.0)
        assertEquals(round(steps), steps, 1e-6)
    }

    @Test
    fun line_should_FinishWithDone_AndCommitNothingWithOneVertex() {
        val ctx = testToolContext()
        val two = runScenario(LineTool(), ctx) { down(0, 0); up(0, 0); down(50, 0); up(50, 0) }
        val finished = (two.finalTool as LineTool).done(ctx)
        assertEquals(2, assertIs<LineElement>(assertIs<AddElementCommand>(finished.command).element).points.size)
        val one = runScenario(LineTool(), ctx) { down(0, 0); up(0, 0) }
        assertNull((one.finalTool as LineTool).done(ctx).command)
    }

    @Test
    fun line_should_CancelWithNoCommand_OnEscapeOrSecondPointer() {
        val esc = runScenario(LineTool()) { down(0, 0); up(0, 0); down(50, 0); up(50, 0); escape() }
        val second = runScenario(LineTool()) { down(0, 0); up(0, 0); down(50, 0); down(9, 9, pointer = 1); up(50, 0) }
        assertTrue(esc.commands.isEmpty() && second.commands.isEmpty())
        assertTrue((esc.finalTool as LineTool).placedPoints.isEmpty())
    }

    @Test
    fun line_should_FinishItself_AtThePointCap() {
        val cap = dev.stapler.stelekit.sketch.v2.scene.SceneLimits.Default.maxPointsPerElement
        val r = runScenario(LineTool()) { for (i in 0 until cap) { down(i * GAP, 0); up(i * GAP, 0) } }
        assertEquals(cap, line(r).points.size)
    }

    // ---- point editing ------------------------------------------------------------------------------------------

    private fun lineDoc(): SketchDocumentV2 {
        val el = LineElement(ElementBase(elId("l1")), listOf(sp(0, 0), sp(100, 0), sp(100, 100)))
        return SketchDocumentV2(sceneOfElements(el))
    }

    @Test
    fun pointEdit_should_MoveAVertex_InOneUndoStep() {
        val doc = lineDoc()
        val ctx = testToolContext(doc.scene)
        val r = runScenario(LinePointEditTool(elId("l1")), ctx) { down(100, 0); move(120, 10); up(120, 10) }
        val cmd = assertIs<SetLinePointsCommand>(r.commands.single())
        assertEquals(listOf(sp(0, 0), sp(120, 10), sp(100, 100)), cmd.points)
        val history = commitToolCommand(doc, cmd)
        assertEquals(doc, history.undo().present)
        assertEquals(1, history.undoDepth)
    }

    @Test
    fun pointEdit_should_InsertAVertex_When_ASegmentIsDragged() {
        val ctx = testToolContext(lineDoc().scene)
        val r = runScenario(LinePointEditTool(elId("l1")), ctx) { down(50, 0); move(50, -20); up(50, -20) }
        assertEquals(listOf(sp(0, 0), sp(50, -20), sp(100, 0), sp(100, 100)), assertIs<SetLinePointsCommand>(r.commands.single()).points)
    }

    @Test
    fun pointEdit_should_LeaveTheMode_When_EmptyCanvasIsPressed_Or_EscapeWithoutDrag() {
        val ctx = testToolContext(lineDoc().scene)
        val away = runScenario(LinePointEditTool(elId("l1")), ctx) { down(300, 300); up(300, 300) }
        assertIs<SelectTool>(away.finalTool)
        assertTrue(away.commands.isEmpty())
        val esc = runScenario(LinePointEditTool(elId("l1")), ctx) { escape() }
        assertEquals(listOf(elId("l1")), (esc.finalTool as SelectTool).selection)
    }

    @Test
    fun pointEdit_should_CancelADrag_OnEscape_And_RemoveVertexWithoutDragging() {
        val ctx = testToolContext(lineDoc().scene)
        val r = runScenario(LinePointEditTool(elId("l1")), ctx) { down(100, 0); move(130, 10); escape(); up(130, 10) }
        assertTrue(r.commands.isEmpty())
        val tool = LinePointEditTool(elId("l1"))
        val removed = assertIs<SetLinePointsCommand>(tool.removeVertex(1, ctx).command)
        assertEquals(listOf(sp(0, 0), sp(100, 100)), removed.points)
        assertNull(tool.removeVertex(0, testToolContext(sceneOfElements(LineElement(ElementBase(elId("l1")), listOf(sp(0, 0), sp(9, 9)))))).command)
    }

    private companion object {
        /** Clicks closer than a few screen pixels to the last vertex are a finishing double click. */
        const val GAP = 10
    }
}
