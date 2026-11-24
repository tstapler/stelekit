// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.snap.PointSnapKind
import dev.stapler.stelekit.sketch.v2.snap.PointSnapKinds
import dev.stapler.stelekit.sketch.v2.snap.SnapSettings
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Point snapping through the real tools: a drawn line, a dragged rectangle and a dragged vertex lock onto the drawing's features. */
class PointSnapToolsTest {
    private val diagonalA = LineElement(ElementBase(elId("a")), listOf(sp(0, 0), sp(200, 200)))
    private val diagonalB = LineElement(ElementBase(elId("b")), listOf(sp(0, 200), sp(200, 0)))
    private val snapAll = SnapSettings(points = PointSnapKinds.All)

    private fun ctx(scene: SketchSceneV2, snap: SnapSettings = snapAll, zoom: Double = 1.0, pxPerDp: Double = 1.0) =
        testToolContext(scene, zoom).copy(snap = snap, pxPerDp = pxPerDp)

    private fun committedLine(r: ScenarioResult) = assertIs<LineElement>(assertIs<AddElementCommand>(r.commands.single()).element)

    private fun drawLine(ctx: ToolContext, mods: Modifiers = Modifiers.None, release: Pair<Int, Int> = 103 to 98): ScenarioResult =
        runScenario(LineTool(), ctx) { down(300, 300); move(release.first, release.second, mods = mods); up(release.first, release.second, mods = mods) }

    // ---- LineTool --------------------------------------------------------------------------------------------------

    @Test
    fun line_should_EndOnTheCrossingOfTwoLines_AndShowTheMarker() {
        val c = ctx(sceneOfElements(diagonalA, diagonalB))
        val r = drawLine(c)
        assertEquals(listOf(sp(300, 300), sp(100, 100)), committedLine(r).points)
        val shown = assertNotNull(r.previews[1]?.point, "the drag preview carries the snapped point")
        assertEquals(PointSnapKind.Intersection, shown.kind)
        assertEquals(sp(100, 100), r.previews[1]?.cursor, "the loupe centres on the snapped point")
    }

    @Test
    fun line_should_StartOnAnEndpoint_WhenPressedNearOne() {
        val c = ctx(sceneOfElements(diagonalA))
        val r = runScenario(LineTool(), c) { down(3, -4); move(300, 40); up(300, 40) }
        assertEquals(sp(0, 0), committedLine(r).points.first())
    }

    @Test
    fun line_should_StayWhereDrawn_When_CtrlIsHeld_OrSnapIsNone_OrPaused() {
        val scene = sceneOfElements(diagonalA, diagonalB)
        val raw = listOf(sp(300, 300), sp(103, 98))
        assertEquals(raw, committedLine(drawLine(ctx(scene), mods = Modifiers(ctrl = true))).points)
        assertEquals(raw, committedLine(drawLine(ctx(scene, SnapSettings.None))).points)
        assertEquals(raw, committedLine(drawLine(ctx(scene, snapAll.whileHeld(true)))).points)
    }

    @Test
    fun line_should_SkipAKind_That_IsSwitchedOff() {
        val scene = sceneOfElements(diagonalA, diagonalB)
        val noCrossing = SnapSettings(points = PointSnapKinds.All.with(PointSnapKind.Intersection, false).with(PointSnapKind.Midpoint, false).with(PointSnapKind.Perpendicular, false))
        assertEquals(sp(103, 98), committedLine(drawLine(ctx(scene, noCrossing))).points.last())
    }

    @Test
    fun line_should_UseAFingerSizedReach_ForTouch_ScaledByTheZoom() {
        val scene = sceneOfElements(diagonalA, diagonalB)
        val c = ctx(scene, pxPerDp = 3.0)
        val far = 120 to 100
        val mouse = runScenario(LineTool(), c) { down(300, 300); up(far.first, far.second) }
        assertEquals(sp(120, 100), committedLine(mouse).points.last(), "20 units is outside the mouse reach")
        val touch = runScenario(LineTool(), c) { down(300, 300, kind = PointerKind.Touch); up(far.first, far.second, kind = PointerKind.Touch) }
        assertEquals(sp(100, 100), committedLine(touch).points.last(), "a finger reaches 12 dp = 36 px")
        val zoomedIn = runScenario(LineTool(), ctx(scene, zoom = 4.0)) { down(300, 300); up(104, 100) }
        assertEquals(sp(104, 100), committedLine(zoomedIn).points.last(), "6 px at 4x is 1.5 units: 4 units away is out of reach")
    }

    @Test
    fun line_should_LeaveFromThePerpendicularFoot_OfTheAnchor() {
        val horizontal = LineElement(ElementBase(elId("h")), listOf(sp(0, 0), sp(200, 0)))
        val r = runScenario(LineTool(), ctx(sceneOfElements(horizontal))) { down(50, 100); move(52, 3); up(52, 3) }
        assertEquals(listOf(sp(50, 100), sp(50, 0)), committedLine(r).points)
        assertEquals(PointSnapKind.Perpendicular, r.previews[1]?.point?.kind)
    }

    @Test
    fun line_should_ReachTheTangentPoint_OfACircle() {
        val circle = EllipseElement(ElementBase(elId("c")), su(0), su(0), su(50), su(50))
        val r = runScenario(LineTool(), ctx(sceneOfElements(circle))) { down(200, 0); move(13, 47); up(13, 47) }
        val end = committedLine(r).points.last()
        val angle = atan2(0.0, 200.0) + acos(50.0 / 200.0)
        assertEquals(50 * cos(angle), end.x.value, 1e-6)
        assertEquals(50 * sin(angle), end.y.value, 1e-6)
        val radialDotTangent = end.x.value * (end.x.value - 200.0) + end.y.value * end.y.value
        assertEquals(0.0, radialDotTangent, 1e-6)
        assertEquals(PointSnapKind.Tangent, r.previews[1]?.point?.kind)
    }

    @Test
    fun polyline_should_SnapEachClickedVertex() {
        val c = ctx(sceneOfElements(diagonalA, diagonalB))
        val r = runScenario(LineTool(), c) { down(101, 99); up(101, 99); down(300, 300); up(300, 300); down(300, 300); up(300, 300) }
        assertEquals(listOf(sp(100, 100), sp(300, 300)), committedLine(r).points)
    }

    // ---- shape drags -----------------------------------------------------------------------------------------------

    @Test
    fun rectangle_should_StartOnAnEndpoint_AndEndOnAnIntersection() {
        val c = ctx(sceneOfElements(diagonalA, diagonalB))
        val r = runScenario(RectangleTool(), c) { down(2, 3); move(299, 311); move(101, 99); up(101, 99) }
        val rect = assertIs<RectElement>(assertIs<AddElementCommand>(r.commands.single()).element)
        assertEquals(listOf(0.0, 0.0, 100.0, 100.0), listOf(rect.x.value, rect.y.value, rect.width.value, rect.height.value))
        assertEquals(PointSnapKind.Intersection, r.previews[2]?.point?.kind)
        assertNull(r.previews[1]?.point, "no feature near (299, 311)")
        assertEquals(PointSnapKind.Endpoint, r.previews[0]?.point?.kind, "the press shows what it locked onto")
    }

    @Test
    fun rectangle_should_NeverOfferPerpendicularOrTangent_BecauseItHasNoAnchor() {
        val horizontal = LineElement(ElementBase(elId("h")), listOf(sp(0, 0), sp(200, 0)))
        val r = runScenario(RectangleTool(), ctx(sceneOfElements(horizontal))) { down(60, 100); move(52, 3); up(52, 3) }
        val rect = assertIs<RectElement>(assertIs<AddElementCommand>(r.commands.single()).element)
        assertEquals(52.0, rect.x.value, 1e-9)
        assertEquals(3.0, rect.y.value, 1e-9)
    }

    @Test
    fun rectangle_should_KeepTheTypedSize_Because_SnapOnlyActsOnPointerPositions() {
        // A typed size reaches the scene as a command with no pointer event, so there is nothing for a snap to move.
        val c = ctx(sceneOfElements(diagonalA, diagonalB))
        val r = runScenario(RectangleTool(), c) { down(500, 500); up(550, 560) }
        val rect = assertIs<RectElement>(assertIs<AddElementCommand>(r.commands.single()).element)
        assertEquals(listOf(50.0, 60.0), listOf(rect.width.value, rect.height.value))
    }

    // ---- dragging a vertex -----------------------------------------------------------------------------------------

    private val polyline = LineElement(ElementBase(elId("p")), listOf(sp(500, 500), sp(600, 500), sp(650, 560)))

    private fun selected() = SelectTool(listOf(elId("p")))

    @Test
    fun vertexDrag_should_LockOnTheCrossing_AndShowTheMarker() {
        val c = ctx(sceneOfElements(diagonalA, diagonalB, polyline))
        val r = runScenario(selected(), c) { down(650, 560); move(300, 300); move(102, 99); up(102, 99) }
        assertEquals(sp(100, 100), assertIs<SetLinePointsCommand>(r.commands.single()).points[2])
        assertEquals(PointSnapKind.Intersection, r.previews.filterNotNull().last().point?.kind)
    }

    @Test
    fun vertexDrag_should_CloseOntoTheLinesOwnFirstVertex() {
        val c = ctx(sceneOfElements(polyline))
        val r = runScenario(selected(), c) { down(650, 560); move(560, 530); up(503, 497) }
        assertEquals(sp(500, 500), assertIs<SetLinePointsCommand>(r.commands.single()).points[2])
    }

    @Test
    fun vertexDrag_should_ReadPerpendicularFromThePreviousVertex() {
        val horizontal = LineElement(ElementBase(elId("h")), listOf(sp(0, 0), sp(200, 0)))
        val c = ctx(sceneOfElements(horizontal, LineElement(ElementBase(elId("p")), listOf(sp(50, 100), sp(120, 100)))))
        val r = runScenario(selected(), c) { down(120, 100); move(80, 40); up(52, 3) }
        assertEquals(sp(50, 0), assertIs<SetLinePointsCommand>(r.commands.single()).points[1])
    }

    @Test
    fun vertexDrag_should_NotSnap_WhenCtrlIsHeld_OrAKindIsOff() {
        val scene = sceneOfElements(diagonalA, diagonalB, polyline)
        val ctrl = runScenario(selected(), ctx(scene)) { down(650, 560); move(300, 300); up(102, 99, mods = Modifiers(ctrl = true)) }
        assertEquals(sp(102, 99), assertIs<SetLinePointsCommand>(ctrl.commands.single()).points[2])
        val none = SnapSettings(objects = true, points = PointSnapKinds.None)
        val off = runScenario(selected(), ctx(scene, none)) { down(650, 560); move(300, 300); up(102, 99) }
        assertEquals(sp(102, 99), assertIs<SetLinePointsCommand>(off.commands.single()).points[2])
    }

    // ---- bounded cost ----------------------------------------------------------------------------------------------

    @Test
    fun move_should_ReadOnlyNearbyElements_When_TheSceneHas500() {
        val many = sceneOfElements(*Array(500) { i -> LineElement(ElementBase(elId("m$i")), listOf(sp((i % 25) * 100, (i / 25) * 100), sp((i % 25) * 100 + 60, (i / 25) * 100 + 40))) })
        val c = ctx(many)
        val probe = dev.stapler.stelekit.sketch.v2.snap.PointSnap.probe(c.scene, c.snapIndex, 1230.0, 410.0, 6.0, PointSnapKinds.All)
        assertTrue(probe.elements <= 6, "read ${probe.elements} of 500 elements")
        val r = runScenario(LineTool(), c) { down(5000, 5000); repeat(MOVE_FRAMES) { move(1230 + it, 410) }; up(1230, 410) }
        assertEquals(1, r.commands.size)
    }
}

private const val MOVE_FRAMES = 20
