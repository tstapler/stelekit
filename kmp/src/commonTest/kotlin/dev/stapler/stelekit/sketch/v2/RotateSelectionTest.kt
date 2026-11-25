// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.HistoryV2
import dev.stapler.stelekit.sketch.v2.commands.RotateSelection
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.OpeningElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import dev.stapler.stelekit.sketch.v2.scene.reconciled
import io.kotest.property.Arb
import io.kotest.property.arbitrary.numericDouble
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** E9: rotating any selection about a pivot is one rigid, undoable step for every element kind. */
class RotateSelectionTest {
    private fun su(d: Double) = SceneUnit.of(d).getOrNull() ?: error("out of range: $d")

    private fun pt(x: Double, y: Double) = ScenePoint(su(x), su(y))

    private fun id(s: String) = ElementId(s)

    private fun rect(s: String, x: Double, y: Double, w: Double, h: Double, angle: Double = 0.0) =
        RectElement(ElementBase(id(s)), su(x), su(y), su(w), su(h), angle)

    private fun docOf(vararg elements: SceneElement): SketchDocumentV2 =
        SketchDocumentV2(elements.fold(SketchSceneV2.empty()) { s, e -> s.add(e).getOrNull() ?: error("add ${e.id}") }.reconciled())

    private fun run(doc: SketchDocumentV2, command: RotateSelection): HistoryV2 {
        val committed = Transaction.begin(doc).apply(command).getOrNull()?.commit()?.getOrNull() ?: error("command failed or changed nothing")
        return HistoryV2.of(doc).commit(committed).getOrNull() ?: error("history refused")
    }

    private val quarter = PI / 2.0

    private fun centre(r: RectElement) = (r.x.value + r.width.value / 2.0) to (r.y.value + r.height.value / 2.0)

    @Test
    fun rect_should_TurnAboutItsOwnCentre_And_MoveRoundTheOutsidePivot() {
        val doc = docOf(rect("r", 100.0, 0.0, 40.0, 20.0))
        val turned = run(doc, RotateSelection(listOf(id("r")), quarter, 0.0, 0.0)).present.scene[id("r")] as RectElement
        // Centre (120, 10) a quarter turn clockwise on screen about the origin lands on (-10, 120).
        assertEquals(-10.0, centre(turned).first, 1e-9)
        assertEquals(120.0, centre(turned).second, 1e-9)
        assertEquals(quarter, turned.angle, 1e-9)
        assertEquals(40.0, turned.width.value)
    }

    @Test
    fun everyElementKind_should_RotateRigidly_InOneUndoStep() {
        val doc = docOf(
            rect("r", 0.0, 0.0, 40.0, 20.0),
            EllipseElement(ElementBase(id("e")), su(100.0), su(0.0), su(20.0), su(10.0)),
            LineElement(ElementBase(id("l")), listOf(pt(0.0, 50.0), pt(100.0, 50.0))),
            ArrowElement(ElementBase(id("a")), listOf(pt(0.0, 80.0), pt(60.0, 80.0))),
            FreedrawElement(ElementBase(id("f")), listOf(pt(0.0, 90.0), pt(10.0, 95.0), pt(20.0, 90.0))),
            PointElement(ElementBase(id("p")), su(10.0), su(10.0)),
            ArcElement(ElementBase(id("c")), su(0.0), su(0.0), su(10.0), 0.0, 1.0),
            TextElement(ElementBase(id("t")), su(0.0), su(120.0), su(30.0), su(10.0), text = "hi"),
        )
        val ids = doc.scene.elementsInOrder.map { it.id }
        val history = run(doc, RotateSelection(ids, quarter, 0.0, 0.0))
        assertEquals(1, history.undoDepth)
        val line = history.present.scene[id("l")] as LineElement
        assertEquals(-50.0, line.points[0].x.value, 1e-9)
        assertEquals(0.0, line.points[0].y.value, 1e-9)
        assertEquals(-50.0, line.points[1].x.value, 1e-9)
        assertEquals(100.0, line.points[1].y.value, 1e-9)
        assertEquals(quarter, (history.present.scene[id("e")] as EllipseElement).angle, 1e-9)
        val arc = history.present.scene[id("c")] as ArcElement
        assertEquals(quarter, arc.startAngle, 1e-9)
        assertEquals(1.0 + quarter, arc.endAngle, 1e-9)
        assertEquals(quarter, (history.present.scene[id("t")] as TextElement).angle, 1e-9)
        val p = history.present.scene[id("p")] as PointElement
        assertEquals(-10.0, p.x.value, 1e-9)
        assertEquals(10.0, p.y.value, 1e-9)
        assertEquals(doc, history.undo().present)
    }

    @Test
    fun zeroTurn_should_ChangeNothing() {
        val doc = docOf(rect("r", 0.0, 0.0, 40.0, 20.0))
        val tx = Transaction.begin(doc).apply(RotateSelection(listOf(id("r")), 0.0, 5.0, 5.0)).getOrNull()
        assertIs<CommandError.NoChange>(tx?.commit()?.leftOrNull())
    }

    @Test
    fun boundArrowEnd_should_FollowATurnedShape_And_AStrandedArrowIsUnbound() {
        val shape = rect("r", 0.0, 0.0, 100.0, 50.0)
        val bound = ArrowElement(ElementBase(id("a")), listOf(pt(300.0, 300.0), pt(100.0, 25.0)), endBinding = Binding(id("r"), 1.0, 0.5))
        val doc = docOf(shape, bound)
        val shapeOnly = run(doc, RotateSelection(listOf(id("r")), quarter, 50.0, 25.0)).present
        val end = (shapeOnly.scene[id("a")] as ArrowElement).points.last()
        assertEquals(50.0, end.x.value, 1e-6)
        assertEquals(75.0, end.y.value, 1e-6)
        val arrowOnly = run(doc, RotateSelection(listOf(id("a")), quarter, 0.0, 0.0)).present
        assertEquals(null, (arrowOnly.scene[id("a")] as ArrowElement).endBinding)
    }

    @Test
    fun wallAndOpening_should_TurnTogether_And_TheOpeningStaysOnTheWall() {
        val wall = WallElement(ElementBase(id("w")), listOf(pt(0.0, 0.0), pt(400.0, 0.0)), su(72.0))
        val door = OpeningElement(ElementBase(id("d")), id("w"), 0, su(100.0), su(100.0))
        val doc = docOf(wall, door)
        val turned = run(doc, RotateSelection(listOf(id("w")), quarter, 0.0, 0.0)).present
        val w = turned.scene[id("w")] as WallElement
        assertEquals(0.0, w.points[1].x.value, 1e-9)
        assertEquals(400.0, w.points[1].y.value, 1e-9)
        val d = turned.scene[id("d")] as OpeningElement
        assertEquals(quarter, abs(d.angle), 1e-6)
        // The opening's footprint centre lies on the turned wall (x = 0, y between 100 and 200).
        val cx = d.x.value + d.width.value / 2.0
        val cy = d.y.value + d.height.value / 2.0
        assertEquals(0.0, cx, 1e-6)
        assertEquals(150.0, cy, 1e-6)
    }

    @Test
    fun textLabel_should_TurnWithItsContainer() {
        val container = rect("r", 0.0, 0.0, 100.0, 50.0)
        val label = TextElement(ElementBase(id("t")), su(40.0), su(20.0), su(20.0), su(10.0), text = "x", containerId = id("r"))
        val turned = run(docOf(container, label), RotateSelection(listOf(id("r")), quarter, 50.0, 25.0)).present
        assertEquals(quarter, (turned.scene[id("t")] as TextElement).angle, 1e-9)
    }

    @Test
    fun rotationProperty_should_PreserveDistances_And_InvertWithTheOppositeTurn() = runTest {
        val coord = Arb.numericDouble(-5_000.0, 5_000.0)
        checkAll(Arb.list(coord, 4..12), Arb.numericDouble(0.01, PI), coord, coord) { values, delta, px, py ->
            val points = values.chunked(2).filter { it.size == 2 }.map { pt(it[0], it[1]) }
            val doc = docOf(LineElement(ElementBase(id("l")), points))
            val turned = run(doc, RotateSelection(listOf(id("l")), delta, px, py)).present
            val back = run(turned, RotateSelection(listOf(id("l")), -delta, px, py)).present.scene[id("l")] as LineElement
            val mid = (turned.scene[id("l")] as LineElement).points
            for (i in 1 until points.size) {
                val before = hypot(points[i].x.value - points[i - 1].x.value, points[i].y.value - points[i - 1].y.value)
                val after = hypot(mid[i].x.value - mid[i - 1].x.value, mid[i].y.value - mid[i - 1].y.value)
                assertEquals(before, after, 1e-6)
            }
            points.indices.forEach { i ->
                assertEquals(points[i].x.value, back.points[i].x.value, 1e-6)
                assertEquals(points[i].y.value, back.points[i].y.value, 1e-6)
            }
            // A turn by delta about the pivot is the matrix the tools use.
            val c = cos(delta)
            val s = sin(delta)
            assertTrue(abs(mid[0].x.value - (px + (points[0].x.value - px) * c - (points[0].y.value - py) * s)) < 1e-6)
        }
    }
}
