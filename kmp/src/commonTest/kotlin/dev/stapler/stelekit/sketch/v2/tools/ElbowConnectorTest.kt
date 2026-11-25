// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.binding.ElbowMid
import dev.stapler.stelekit.sketch.v2.binding.ElbowRouting
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.HistoryV2
import dev.stapler.stelekit.sketch.v2.commands.MoveElements
import dev.stapler.stelekit.sketch.v2.commands.SetElbowMid
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.StyleArrowType
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.routing.RouteRect
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowType
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.reconciled
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** E11: elbow arrows route between bound shapes, follow them live, snap to ports and keep a dragged middle segment. */
class ElbowConnectorTest {
    private val eps = 1e-6
    private val left = filledRectEl("a", 0, 0, 100, 60)
    private val right = filledRectEl("b", 300, 200, 100, 60)

    private fun elbow(): ArrowElement = ArrowElement(
        ElementBase(elId("e")), listOf(sp(100, 30), sp(300, 230)),
        startBinding = Binding(elId("a"), 1.0, 0.5), endBinding = Binding(elId("b"), 0.0, 0.5), elbowed = true,
    )

    private fun docWith(arrow: ArrowElement): SketchDocumentV2 = SketchDocumentV2(sceneOfElements(left, right, arrow).reconciled(), sheetUuid = "11111111-1111-4111-8111-111111111111")

    private fun routed(doc: SketchDocumentV2): SketchDocumentV2 {
        val arrow = doc.scene[elId("e")] as ArrowElement
        val points = assertNotNull(ElbowRouting.pointsOf(doc.scene, arrow))
        return doc.copy(scene = doc.scene.replace(arrow.copy(points = points)).getOrNull()!!)
    }

    private fun points(doc: SketchDocumentV2) = (doc.scene[elId("e")] as ArrowElement).points.map { it.x.value to it.y.value }

    private fun orthogonal(pts: List<Pair<Double, Double>>) = pts.zipWithNext().all { (p, q) -> abs(p.first - q.first) < eps || abs(p.second - q.second) < eps }

    private fun clearOf(pts: List<Pair<Double, Double>>, vararg shapes: RectElement) = shapes.all { s ->
        val r = RouteRect(s.x.value, s.y.value, s.x.value + s.width.value, s.y.value + s.height.value)
        pts.zipWithNext().none { (p, q) -> r.interiorCrossedBy(p.first, p.second, q.first, q.second) }
    }

    @Test
    fun elbowArrowBetweenBoundShapes_should_BeOrthogonalAndClearOfBoth() {
        val pts = points(routed(docWith(elbow())))
        assertTrue(orthogonal(pts) && pts.size >= 3, "$pts")
        assertTrue(clearOf(pts, left, right), "$pts")
        assertEquals(100.0 to 30.0, pts.first())
        assertEquals(300.0 to 230.0, pts.last())
    }

    @Test
    fun movingABoundShape_should_RerouteTheArrowLive_InOneUndoStep() {
        val doc = routed(docWith(elbow()))
        val committed = Transaction.begin(doc).apply(MoveElements(listOf(elId("b")), -250.0, -150.0)).getOrNull()?.commit()?.getOrNull() ?: error("move")
        val history = HistoryV2.of(doc).commit(committed).getOrNull() ?: error("history")
        val pts = points(history.present)
        val moved = history.present.scene[elId("b")] as RectElement
        assertTrue(orthogonal(pts), "$pts")
        assertEquals(moved.x.value to moved.y.value + 30.0, pts.last())
        assertTrue(clearOf(pts, left, moved), "$pts")
        assertEquals(1, history.undoDepth)
        assertEquals(doc, history.undo().present)
    }

    @Test
    fun elbowArrowDrawnOntoShapes_should_BindToPortsAndBeRouted() {
        val ctx = testToolContext(sceneOfElements(left, right))
        val tool = ArrowTool(ArrowOptions(type = ArrowType.Elbow))
        // From just off the middle of A's right side to just off the middle of B's left side.
        val result = runScenario(tool, ctx) { down(101, 36); move(299, 226); up(299, 226) }
        val arrow = assertIs<ArrowElement>(assertIs<AddElementCommand>(result.commands.single()).element)
        assertTrue(arrow.elbowed)
        assertEquals(1.0, arrow.startBinding?.fixedX)
        assertEquals(0.5, arrow.startBinding?.fixedY)
        assertEquals(0.0, arrow.endBinding?.fixedX)
        assertEquals(0.5, arrow.endBinding?.fixedY)
        val pts = arrow.points.map { it.x.value to it.y.value }
        assertTrue(orthogonal(pts) && pts.size >= 3, "$pts")
        assertTrue(clearOf(pts, left, right), "$pts")
    }

    @Test
    fun changingAnArrowToElbow_should_RouteItBetweenItsBoundShapes() {
        val straight = elbow().copy(elbowed = false)
        val doc = docWith(straight)
        val after = Transaction.begin(doc).apply(StyleArrowType(listOf(elId("e")), ArrowType.Elbow)).getOrNull()?.doc ?: error("style")
        val pts = points(after)
        assertTrue(orthogonal(pts) && pts.size >= 3, "$pts")
    }

    @Test
    fun anElbowArrowWithNoBoundEnd_should_KeepItsDrawnPoints() {
        val free = elbow().copy(startBinding = null, endBinding = null)
        assertEquals(null, ElbowRouting.pointsOf(docWith(free).scene, free))
    }

    @Test
    fun draggingTheMiddleHandle_should_MoveTheMiddleSegment_AndSurviveTheCodec() {
        val doc = routed(docWith(elbow()))
        val ctx = testToolContext(doc.scene)
        val tool = SelectTool(selection = listOf(elId("e")))
        val handle = tool.vertexPoints(ctx).single()
        val hx = handle.x.value.toInt()
        val hy = handle.y.value.toInt()
        val result = runScenario(tool, ctx) { down(hx, hy); move(hx - 60, hy); up(hx - 60, hy) }
        val cmd = assertIs<SetElbowMidCommand>(result.commands.single())
        assertEquals(handle.x.value - 60.0, cmd.coord, eps)
        val history = commitToolCommand(doc, cmd)
        val pts = points(history.present)
        assertTrue(orthogonal(pts) && clearOf(pts, left, right), "$pts")
        assertTrue(pts.any { abs(it.first - cmd.coord) < eps }, "the middle segment sits at ${cmd.coord}: $pts")
        val arrow = history.present.scene[elId("e")] as ArrowElement
        assertEquals(cmd.coord, ElbowMid.read(arrow.base.extras))
        val back = SheetCodecV2.decode(SheetCodecV2.encode(history.present).getOrNull() ?: error("encode")).getOrNull() ?: error("decode")
        assertEquals(history.present.scene.elementsInOrder, back.scene.elementsInOrder)
        assertEquals(doc, history.undo().present)
        // A later move of a bound shape keeps the dragged segment where the user put it while it stays clear.
        val again = Transaction.begin(history.present).apply(MoveElements(listOf(elId("b")), 0.0, 20.0)).getOrNull()?.doc ?: error("move")
        assertTrue(points(again).any { abs(it.first - cmd.coord) < eps }, "${points(again)}")
        assertEquals(null, (SetElbowMid(elId("e"), null).apply(history.present).getOrNull()!!.doc.scene[elId("e")] as ArrowElement).base.extras[ElbowMid.KEY])
    }
}
