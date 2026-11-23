// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.sketch.v2.tools.sceneOfElements
import dev.stapler.stelekit.sketch.v2.tools.sp
import dev.stapler.stelekit.sketch.v2.tools.su
import dev.stapler.stelekit.ui.sketch.lengthTextOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The dimension pills of a technical sheet: which pills a shape gets, where they sit, and what a typed length becomes. */
class DimensionPillModelTest {
    private fun canvas(zoom: Double = 1.0, panX: Double = 0.0, panY: Double = 0.0, w: Double = 360.0, h: Double = 600.0) =
        PillCanvas({ x, y -> (x * zoom + panX) to (y * zoom + panY) }, zoom, w, h)

    private fun ellipse(id: String, cx: Int, cy: Int, rx: Int, ry: Int) = EllipseElement(ElementBase(ElementId(id)), su(cx), su(cy), su(rx), su(ry))

    private fun line(id: String, ax: Int, ay: Int, bx: Int, by: Int) = LineElement(ElementBase(ElementId(id)), listOf(sp(ax, ay), sp(bx, by)))

    private fun List<PlacedPill>.of(kind: PillKind) = first { it.kind == kind }

    private fun apply(element: SceneElement, values: Map<PillKind, Long>): SceneElement {
        val doc = SketchDocumentV2(scene = sceneOfElements(element))
        val command = assertNotNull(PillResize.command(element, values))
        return command.apply(doc).getOrNull()!!.doc.scene[element.id]!!
    }

    @Test
    fun specs_should_BeWidthAndHeightForARect_DiameterForACircle_LengthForATwoPointLine_AndNoneForAPolyline() {
        assertEquals(listOf(PillKind.Width, PillKind.Height), DimensionPillModel.specs(filledRectEl("r", 0, 0, 160, 80)).map { it.kind })
        assertEquals(listOf(160L, 80L), DimensionPillModel.specs(filledRectEl("r", 0, 0, 160, 80)).map { it.sixteenths })
        assertEquals(listOf(PillKind.Width, PillKind.Height), DimensionPillModel.specs(ellipse("e", 50, 50, 40, 20)).map { it.kind })
        assertEquals(listOf(PillSpec(PillKind.Diameter, 80), PillSpec(PillKind.Radius, 40)), DimensionPillModel.specs(ellipse("c", 50, 50, 40, 40)))
        assertEquals(listOf(PillSpec(PillKind.Length, 50)), DimensionPillModel.specs(line("l", 0, 0, 30, 40)))
        val poly = LineElement(ElementBase(ElementId("p")), listOf(sp(0, 0), sp(10, 0), sp(10, 10)))
        assertTrue(DimensionPillModel.specs(poly).isEmpty())
    }

    @Test
    fun shown_should_RequireATechnicalEditableSheet_PillsOn_OneShape_AndTheSelectTool() {
        assertTrue(DimensionPillModel.shown(true, false, true, 1, ToolId.Select))
        assertTrue(!DimensionPillModel.shown(false, false, true, 1, ToolId.Select))
        assertTrue(!DimensionPillModel.shown(true, true, true, 1, ToolId.Select))
        assertTrue(!DimensionPillModel.shown(true, false, false, 1, ToolId.Select))
        assertTrue(!DimensionPillModel.shown(true, false, true, 2, ToolId.Select))
        assertTrue(!DimensionPillModel.shown(true, false, true, 0, ToolId.Select))
        assertTrue(!DimensionPillModel.shown(true, false, true, 1, ToolId.Rectangle))
    }

    @Test
    fun layout_should_PutWidthCentredBelowTheBottomEdge_AndHeightCentredBesideTheRightEdge() {
        val rect = filledRectEl("r", 100, 200, 160, 80)
        val pills = DimensionPillModel.layout(rect, canvas())
        val width = pills.of(PillKind.Width)
        val height = pills.of(PillKind.Height)
        assertEquals(180.0, width.centreX, 0.001)
        assertTrue(width.top >= 280.0 + DimensionPillModel.GAP_DP - 0.001, "width pill sits below the bottom edge, away from the rotate handle: ${width.top}")
        assertEquals(240.0, height.centreY, 0.001)
        assertTrue(height.left >= 260.0 + DimensionPillModel.GAP_DP - 0.001, "height pill sits right of the right edge: ${height.left}")
    }

    @Test
    fun layout_should_FollowPanAndZoom() {
        val rect = filledRectEl("r", 100, 200, 160, 80)
        val base = DimensionPillModel.layout(rect, canvas()).of(PillKind.Width)
        val panned = DimensionPillModel.layout(rect, canvas(panX = 30.0, panY = 10.0)).of(PillKind.Width)
        assertEquals(base.centreX + 30.0, panned.centreX, 0.001)
        assertEquals(base.centreY + 10.0, panned.centreY, 0.001)
        val zoomed = DimensionPillModel.layout(rect, canvas(zoom = 0.5)).of(PillKind.Width)
        assertEquals(90.0, zoomed.centreX, 0.001)
    }

    @Test
    fun layout_should_FlipToTheTop_When_TheShapeIsAtTheBottomEdge() {
        val rect = filledRectEl("r", 100, 500, 160, 80)
        val width = DimensionPillModel.layout(rect, canvas()).of(PillKind.Width)
        assertTrue(width.bottom <= 500.0, "flipped above the shape: ${width.bottom}")
    }

    @Test
    fun layout_should_FlipHeightToTheLeft_When_TheShapeIsAtTheRightEdge() {
        val rect = filledRectEl("r", 200, 200, 150, 80)
        val height = DimensionPillModel.layout(rect, canvas()).of(PillKind.Height)
        assertTrue(height.right <= 200.0, "flipped left of the shape: ${height.right}")
    }

    @Test
    fun layout_should_ClampInsideTheCanvas_When_TheShapeFillsTheViewport() {
        val rect = filledRectEl("r", -50, -50, 460, 700)
        for (pill in DimensionPillModel.layout(rect, canvas())) {
            assertTrue(pill.left >= 0.0 && pill.top >= 0.0 && pill.right <= 360.0 && pill.bottom <= 600.0, "pill outside canvas: $pill")
        }
    }

    @Test
    fun layout_should_KeepPillsOffEachOther_When_TheShapeIsTiny() {
        val rect = filledRectEl("r", 150, 300, 10, 10)
        val pills = DimensionPillModel.layout(rect, canvas())
        assertTrue(!pills[0].overlaps(pills[1]), "pills overlap: $pills")
    }

    @Test
    fun layout_should_MoveWidthToTheSide_When_TheShapeIsRotatedAQuarterTurn() {
        val rect = RectElement(ElementBase(ElementId("r")), su(100), su(200), su(160), su(80), angle = PI / 2)
        val width = DimensionPillModel.layout(rect, canvas()).of(PillKind.Width)
        // Rotated about the centre (180, 240) a quarter turn, the bottom edge faces left.
        assertTrue(width.right < 180.0 - 40.0, "width pill follows the rotated bottom edge: ${width.right}")
    }

    @Test
    fun layout_should_PutTheLengthPillBesideTheMidpointOfALine_WithoutCoveringIt() {
        val l = line("l", 100, 300, 260, 300)
        val pill = DimensionPillModel.layout(l, canvas()).single()
        assertEquals(180.0, pill.centreX, 0.001)
        assertTrue(pill.bottom <= 300.0, "above a horizontal line: ${pill.bottom}")
        val diagonal = DimensionPillModel.layout(line("d", 100, 300, 260, 100), canvas()).single()
        assertTrue(!rectCrossesSegment(diagonal, 100.0 to 300.0, 260.0 to 100.0), "pill covers the line")
    }

    private fun rectCrossesSegment(p: PlacedPill, a: Pair<Double, Double>, b: Pair<Double, Double>): Boolean {
        val steps = 200
        return (0..steps).any { i ->
            val t = i.toDouble() / steps
            val x = a.first + (b.first - a.first) * t
            val y = a.second + (b.second - a.second) * t
            x > p.left && x < p.right && y > p.top && y < p.bottom
        }
    }

    @Test
    fun layout_should_BeEmpty_When_TheCanvasHasNoSize() {
        assertTrue(DimensionPillModel.layout(filledRectEl("r", 0, 0, 10, 10), canvas(w = 0.0, h = 0.0)).isEmpty())
    }

    @Test
    fun everyPill_should_BeATouchTarget_InsideTheCanvas_AndOffTheOthers_ForAnyRectInView() = runTest {
        checkAll(Arb.int(0, 200), Arb.int(0, 300), Arb.int(40, 160), Arb.int(40, 200), Arb.double(0.5, 2.0).filter { it.isFinite() }) { x, y, w, h, zoom ->
            val c = canvas(zoom = zoom, w = 400.0, h = 700.0)
            val pills = DimensionPillModel.layout(filledRectEl("r", x, y, w, h), c)
            assertEquals(2, pills.size)
            for (p in pills) {
                assertTrue(p.heightDp >= 44.0 && p.widthDp >= 44.0, "target below the touch floor: $p")
                assertTrue(p.left >= 0.0 && p.top >= 0.0 && p.right <= 400.0 && p.bottom <= 700.0, "outside canvas: $p")
            }
            assertTrue(!pills[0].overlaps(pills[1]), "pills overlap: $pills")
        }
    }

    @Test
    fun resizeCommand_should_KeepTheTopLeftFixed_AndSetOnlyTheTypedSide() {
        val resized = assertIs<RectElement>(apply(filledRectEl("r", 40, 50, 160, 80), mapOf(PillKind.Width to 200L)))
        assertEquals(listOf(40.0, 50.0, 200.0, 80.0), listOf(resized.x.value, resized.y.value, resized.width.value, resized.height.value))
    }

    @Test
    fun resizeCommand_should_SetWidthAndHeightTogether_InOneCommand() {
        val resized = assertIs<RectElement>(apply(filledRectEl("r", 40, 50, 160, 80), mapOf(PillKind.Width to 200L, PillKind.Height to 33L)))
        assertEquals(listOf(200.0, 33.0), listOf(resized.width.value, resized.height.value))
    }

    @Test
    fun resizeCommand_should_FitACircleToTheDiameter_KeepingItsTopLeft() {
        val resized = assertIs<EllipseElement>(apply(ellipse("c", 100, 100, 40, 40), mapOf(PillKind.Diameter to 120L)))
        assertEquals(60.0, resized.rx.value)
        assertEquals(60.0, resized.cx.value - resized.rx.value)
    }

    @Test
    fun resizeCommand_should_ScaleALineToTheTypedLength_KeepingItsDirection() {
        val scaled = assertIs<LineElement>(apply(line("l", 0, 0, 30, 40), mapOf(PillKind.Length to 100L)))
        val (a, b) = scaled.points
        assertEquals(100.0, hypot(b.x.value - a.x.value, b.y.value - a.y.value), 0.5)
        assertEquals(40.0 / 30.0, (b.y.value - a.y.value) / (b.x.value - a.x.value), 0.01)
        assertEquals(0.0, a.x.value)
    }

    @Test
    fun resizeCommand_should_BeNull_When_TheValuesNameNoKindOfTheElement() {
        assertNull(PillResize.command(filledRectEl("r", 0, 0, 10, 10), mapOf(PillKind.Length to 5L)))
        assertNull(PillResize.command(filledRectEl("r", 0, 0, 10, 10), emptyMap()))
    }

    @Test
    fun theFieldText_should_ParseBackToTheSameLength() = runTest {
        checkAll(Arb.int(1, 20_000)) { n ->
            val parsed = DimensionPillModel.parse(lengthTextOf(n.toLong()))
            assertEquals(DimensionPillModel.ParsedLength.Valid(n.toLong()), parsed, "text was ${lengthTextOf(n.toLong())}")
        }
    }

    @Test
    fun parse_should_AcceptFractionsAndRejectGarbageWithAMessage() {
        assertEquals(DimensionPillModel.ParsedLength.Valid(12 * 16 + 3), DimensionPillModel.parse("12 3/16\""))
        assertIs<DimensionPillModel.ParsedLength.Invalid>(DimensionPillModel.parse("abc")).also { assertTrue(it.message.isNotBlank()) }
        assertIs<DimensionPillModel.ParsedLength.Invalid>(DimensionPillModel.parse(""))
        assertIs<DimensionPillModel.ParsedLength.Invalid>(DimensionPillModel.parse("-4"))
    }

    @Test
    fun edit_should_GoFromWidthToHeight_AndFinishWithOneCommand() {
        val rect = filledRectEl("r", 40, 50, 160, 80)
        val edit = DimensionPillEdit(rect, PillKind.Width)
        assertEquals(PillStep.Next(PillKind.Height), edit.submit("20\""))
        assertEquals(PillKind.Height, edit.current)
        val done = assertIs<PillStep.Done>(edit.submit("3\""))
        val command = assertNotNull(done.command)
        assertEquals(listOf(40.0, 50.0, 320.0, 48.0), listOf(command.x, command.y, command.width, command.height))
    }

    @Test
    fun edit_should_ShowTheParsersMessage_AndKeepTheValuesAndTheField_When_TheTextIsInvalid() {
        val edit = DimensionPillEdit(filledRectEl("r", 0, 0, 160, 80), PillKind.Width)
        val step = assertIs<PillStep.Invalid>(edit.submit("wide"))
        assertTrue(step.message.isNotBlank())
        assertEquals(PillKind.Width, edit.current)
        assertEquals(lengthTextOf(160), edit.textOf(PillKind.Width))
    }

    @Test
    fun edit_should_FinishWithoutACommand_When_NothingChanged() {
        val edit = DimensionPillEdit(filledRectEl("r", 0, 0, 160, 80), PillKind.Height)
        assertEquals(PillStep.Done(null), edit.submit(lengthTextOf(80)))
    }

    @Test
    fun edit_should_StartOnTheTappedPill_AndFinishOnTheLastField() {
        val edit = DimensionPillEdit(filledRectEl("r", 0, 0, 160, 80), PillKind.Height)
        assertTrue(edit.isLast)
        val circle = DimensionPillEdit(ellipse("c", 50, 50, 40, 40), PillKind.Width)
        assertEquals(PillKind.Diameter, circle.current)
        assertTrue(circle.isLast)
    }

    @Test
    fun pillText_should_NameTheSideAndShowFeetInchesAndSixteenths() {
        assertEquals("W " + lengthTextOf(195), DimensionPillModel.pillText(PillSpec(PillKind.Width, 195)))
        assertTrue(DimensionPillModel.pillWidthDp("W 12' 3 3/16\"") >= 44.0)
    }

    @Test
    fun resizeCommand_should_KeepTheVisibleTopLeftOfARotatedBox() {
        val rect = RectElement(ElementBase(ElementId("r")), su(0), su(0), su(100), su(100), angle = PI / 2)
        val resized = assertIs<RectElement>(apply(rect, mapOf(PillKind.Width to 200L)))
        // Rotated a quarter turn about the centre, the visible top-left is the original (100, 0).
        val cx = resized.x.value + resized.width.value / 2
        val cy = resized.y.value + resized.height.value / 2
        val tlx = cx - (-resized.width.value / 2) * 0.0 - (-resized.height.value / 2) * 1.0 + 0.0
        val tly = cy + (-resized.width.value / 2) * 1.0
        assertEquals(100.0, tlx, 0.001)
        assertEquals(0.0, tly, 0.001)
    }

    @Test
    fun layout_should_ClearARotatedBox_ByItsSupportDistance() {
        val rect = RectElement(ElementBase(ElementId("r")), su(100), su(200), su(160), su(80), angle = PI / 2)
        val width = DimensionPillModel.layout(rect, canvas(w = 600.0, h = 800.0)).of(PillKind.Width)
        // Centre (180, 240); the shape's screen half-width is 40, so the pill's right edge must be left of 140.
        assertTrue(width.right <= 180.0 - 40.0, "pill overlaps the rotated shape: ${width.right}")
    }

    @Test
    fun edit_should_OnlyWriteTheFieldsThatChanged() {
        val rect = RectElement(ElementBase(ElementId("r")), su(0), su(0), su(160), su(80))
        val done = assertIs<PillStep.Done>(DimensionPillEdit(rect, PillKind.Height).submit("6\""))
        assertEquals(96.0, assertNotNull(done.command).height)
    }

    @Test
    fun resizeCommand_should_TakeARadiusAsHalfTheDiameter() {
        val resized = assertIs<EllipseElement>(apply(ellipse("c", 100, 100, 40, 40), mapOf(PillKind.Radius to 30L)))
        assertEquals(30.0, resized.rx.value)
        assertEquals(60.0, resized.cx.value - resized.rx.value)
    }

    @Test
    fun resizeCommand_should_KeepALinesStartPointFixed_WhateverEndItStartsAt() {
        val reversed = line("l", 30, 40, 0, 0)
        val scaled = assertIs<LineElement>(apply(reversed, mapOf(PillKind.Length to 100L)))
        val (a, b) = scaled.points
        assertEquals(30.0, a.x.value, 0.01)
        assertEquals(40.0, a.y.value, 0.01)
        assertEquals(100.0, hypot(b.x.value - a.x.value, b.y.value - a.y.value), 0.5)
    }

    @Test
    fun layout_should_GiveACircleADiameterPillBelowAndARadiusPillBesideIt() {
        val pills = DimensionPillModel.layout(ellipse("c", 150, 200, 40, 40), canvas())
        assertEquals(listOf(PillKind.Diameter, PillKind.Radius), pills.map { it.kind })
        assertTrue(pills.of(PillKind.Diameter).top >= 240.0)
        assertTrue(pills.of(PillKind.Radius).left >= 190.0)
        assertTrue(!pills[0].overlaps(pills[1]))
    }

    @Test
    fun edit_should_BeASingleFieldForACirclesDiameterOrRadius() {
        val circle = ellipse("c", 50, 50, 40, 40)
        val radius = DimensionPillEdit(circle, PillKind.Radius)
        assertEquals(listOf(PillKind.Radius), radius.kinds)
        val done = assertIs<PillStep.Done>(radius.submit("2\""))
        assertEquals(64.0, assertNotNull(done.command).width)
    }

    @Test
    fun edit_should_GoBackAKeepingWhatWasTyped_AndStayPutOnTheFirstField() {
        val edit = DimensionPillEdit(filledRectEl("r", 0, 0, 160, 80), PillKind.Width)
        assertTrue(!edit.back("1\""))
        edit.submit("20\"")
        assertEquals(PillKind.Height, edit.current)
        assertTrue(edit.back("6\""))
        assertEquals(PillKind.Width, edit.current)
        assertEquals(lengthTextOf(320), edit.textOf(PillKind.Width))
        assertEquals(lengthTextOf(96), edit.textOf(PillKind.Height))
    }

    private val shape = ShapeRect(100.0, 100.0, 260.0, 190.0)

    @Test
    fun placement_should_PutTheFieldBelowTheShape_WhenThePillIsBelow_AndNeverOverTheShape() {
        val pill = PlacedPill(PillKind.Width, "W", 180.0, 215.0, 80.0, 44.0)
        val plan = PillEditorPlacement.plan(shape, pill, 360.0, 600.0, 232.0, 128.0)
        assertTrue(plan.top >= shape.bottom, "field below the shape: ${plan.top}")
        assertEquals(0.0, plan.panDy)
    }

    @Test
    fun placement_should_ChooseASideThatKeepsTheShapeVisible_WhenTheRightHasNoRoom() {
        val pill = PlacedPill(PillKind.Height, "H", 300.0, 145.0, 80.0, 44.0)
        val plan = PillEditorPlacement.plan(shape, pill, 360.0, 600.0, 232.0, 128.0)
        val box = ShapeRect(plan.left, plan.top, plan.left + 232.0, plan.top + 128.0)
        assertTrue(!box.intersects(shape), "field covers the shape: $plan")
    }

    @Test
    fun placement_should_StayInsideTheCanvasTheKeyboardLeaves_AndPanAShapeThatIsUnderIt() {
        val low = ShapeRect(100.0, 500.0, 260.0, 590.0)
        val visible = PillEditorPlacement.visibleHeight(700.0, 320.0)
        assertEquals(380.0, visible)
        val plan = PillEditorPlacement.plan(low, null, 360.0, visible, 232.0, 128.0)
        assertTrue(plan.panDy < 0.0, "a shape under the keyboard is panned up: ${plan.panDy}")
        val shifted = low.shiftedY(plan.panDy)
        assertTrue(shifted.top < visible - 56.0)
        val box = ShapeRect(plan.left, plan.top, plan.left + 232.0, plan.top + 128.0)
        assertTrue(box.bottom <= visible, "field above the keyboard: ${box.bottom}")
        assertTrue(!box.intersects(shifted), "field off the shape")
    }

    @Test
    fun placement_should_NotPan_When_TheShapeIsAlreadyVisible() {
        assertEquals(0.0, PillEditorPlacement.plan(shape, null, 360.0, 380.0, 232.0, 128.0).panDy)
    }

    @Test
    fun placement_should_FallBackToTheBottomOfTheVisibleArea_When_TheShapeFillsTheView() {
        val full = ShapeRect(0.0, 0.0, 360.0, 380.0)
        val plan = PillEditorPlacement.plan(full, null, 360.0, 380.0, 232.0, 128.0)
        assertEquals(380.0 - 128.0 - DimensionPillModel.EDGE_DP, plan.top, 0.001)
    }

    @Test
    fun shapeBounds_should_CoverARotatedBoxByItsCorners() {
        val rect = RectElement(ElementBase(ElementId("r")), su(100), su(200), su(160), su(80), angle = PI / 2)
        val b = assertNotNull(PillEditorPlacement.shapeBounds(rect, canvas()))
        assertEquals(40.0, b.right - b.centreX, 0.001)
        assertEquals(80.0, b.bottom - b.centreY, 0.001)
    }
}
