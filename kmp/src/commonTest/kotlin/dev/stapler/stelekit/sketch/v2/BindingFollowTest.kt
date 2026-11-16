// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.binding.BindingGeometry
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.DeleteElements
import dev.stapler.stelekit.sketch.v2.commands.HistoryV2
import dev.stapler.stelekit.sketch.v2.commands.MoveElements
import dev.stapler.stelekit.sketch.v2.commands.ResizeElement
import dev.stapler.stelekit.sketch.v2.commands.RotateElement
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.reconciled
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 3.3.1 (the follow half of the binding stories, ux.md section 5 flow step 3): an arrow end bound to a shape stays on
 * it when the shape moves, resizes or rotates, inside the same undo step. Also the geometry the tool and the follow share.
 */
class BindingFollowTest {
    private fun su(d: Double) = SceneUnit.of(d).getOrNull() ?: error("out of range: $d")

    private fun pt(x: Double, y: Double) = ScenePoint(su(x), su(y))

    private fun rect(id: String, x: Double, y: Double, w: Double, h: Double, angle: Double = 0.0) =
        RectElement(ElementBase(ElementId(id)), su(x), su(y), su(w), su(h), angle)

    private fun arrow(id: String, points: List<ScenePoint>, start: Binding? = null, end: Binding? = null) =
        ArrowElement(ElementBase(ElementId(id)), points, start, end)

    private fun docOf(vararg elements: SceneElement): SketchDocumentV2 =
        SketchDocumentV2(elements.fold(dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2.empty()) { s, e -> s.add(e).getOrNull() ?: error("add ${e.id}") }.reconciled())

    private fun run(doc: SketchDocumentV2, command: Command): HistoryV2 {
        val committed = Transaction.begin(doc).apply(command).getOrNull()?.commit()?.getOrNull() ?: error("command failed or changed nothing")
        return HistoryV2.of(doc).commit(committed).getOrNull() ?: error("history refused")
    }

    private fun end(doc: SketchDocumentV2, id: String) = assertIs<ArrowElement>(doc.scene[ElementId(id)]).points.last()

    private fun start(doc: SketchDocumentV2, id: String) = assertIs<ArrowElement>(doc.scene[ElementId(id)]).points.first()

    private val rightMiddle = Binding(ElementId("r1"), 1.0, 0.5)

    private fun bound() = docOf(rect("r1", 0.0, 0.0, 100.0, 50.0), arrow("a1", listOf(pt(300.0, 300.0), pt(100.0, 25.0)), end = rightMiddle))

    @Test
    fun arrowEnd_should_FollowTheShape_InTheSameUndoStep() {
        val history = run(bound(), MoveElements(listOf(ElementId("r1")), 200.0, 80.0))
        assertEquals(300.0, end(history.present, "a1").x.value, 1e-6)
        assertEquals(105.0, end(history.present, "a1").y.value, 1e-6)
        assertEquals(300.0, start(history.present, "a1").x.value)
        assertEquals(1, history.undoDepth)
        assertEquals(bound(), history.undo().present)
    }

    @Test
    fun arrowStart_should_FollowTheShape_Too() {
        val doc = docOf(rect("r1", 0.0, 0.0, 100.0, 50.0), arrow("a1", listOf(pt(0.0, 25.0), pt(400.0, 25.0)), start = Binding(ElementId("r1"), 0.0, 0.5)))
        val moved = run(doc, MoveElements(listOf(ElementId("r1")), 10.0, 40.0)).present
        assertEquals(10.0, start(moved, "a1").x.value, 1e-6)
        assertEquals(65.0, start(moved, "a1").y.value, 1e-6)
        assertEquals(400.0, end(moved, "a1").x.value)
    }

    @Test
    fun arrowEnd_should_KeepItsFixedPoint_When_TheShapeResizes() {
        val resized = run(bound(), ResizeElement(ElementId("r1"), 0.0, 0.0, 200.0, 100.0)).present
        assertEquals(200.0, end(resized, "a1").x.value, 1e-6)
        assertEquals(50.0, end(resized, "a1").y.value, 1e-6)
    }

    @Test
    fun arrowEnd_should_TurnWithTheShape_When_ItRotates() {
        val rotated = run(bound(), RotateElement(ElementId("r1"), PI / 2.0)).present
        assertEquals(50.0, end(rotated, "a1").x.value, 1e-6)
        assertEquals(75.0, end(rotated, "a1").y.value, 1e-6)
    }

    @Test
    fun arrow_should_KeepItsBytes_When_MovedTogetherWithItsShape() {
        val doc = bound()
        val together = run(doc, MoveElements(listOf(ElementId("r1"), ElementId("a1")), 7.0, 9.0)).present
        assertEquals(107.0, end(together, "a1").x.value, 1e-9)
        assertEquals(34.0, end(together, "a1").y.value, 1e-9)
        assertEquals(307.0, start(together, "a1").x.value, 1e-9)
    }

    @Test
    fun arrow_should_StayWhereItWas_When_ItsShapeIsDeleted() {
        val after = run(bound(), DeleteElements(listOf(ElementId("r1")))).present
        val a1 = assertIs<ArrowElement>(after.scene[ElementId("a1")])
        assertNull(a1.endBinding)
        assertEquals(100.0, a1.points.last().x.value)
    }

    @Test
    fun otherArrows_should_NotMove_When_AnUnrelatedShapeMoves() {
        val doc = docOf(rect("r1", 0.0, 0.0, 100.0, 50.0), rect("r2", 500.0, 0.0, 10.0, 10.0), arrow("a1", listOf(pt(300.0, 300.0), pt(100.0, 25.0)), end = rightMiddle))
        val moved = run(doc, MoveElements(listOf(ElementId("r2")), 5.0, 5.0)).present
        assertEquals(doc.scene[ElementId("a1")], moved.scene[ElementId("a1")])
    }

    @Test
    fun arrowEnd_should_FollowAnEllipseAndADiamond() {
        val ellipse = EllipseElement(ElementBase(ElementId("e1")), su(50.0), su(25.0), su(50.0), su(25.0))
        val diamond = DiamondElement(ElementBase(ElementId("d1")), su(200.0), su(0.0), su(100.0), su(50.0))
        val doc = docOf(
            ellipse, diamond,
            arrow("a1", listOf(pt(0.0, 300.0), pt(100.0, 25.0)), end = Binding(ElementId("e1"), 1.0, 0.5)),
            arrow("a2", listOf(pt(0.0, 300.0), pt(300.0, 25.0)), end = Binding(ElementId("d1"), 1.0, 0.5)),
        )
        val moved = run(doc, MoveElements(listOf(ElementId("e1"), ElementId("d1")), 10.0, 20.0)).present
        assertEquals(110.0, end(moved, "a1").x.value, 1e-6)
        assertEquals(45.0, end(moved, "a1").y.value, 1e-6)
        assertEquals(310.0, end(moved, "a2").x.value, 1e-6)
    }

    @Test
    fun move_should_BeRefused_When_ItWouldLeaveTheCoordinateRange() {
        val limit = dev.stapler.stelekit.sketch.v2.scene.SceneLimits.MAX_COORD
        val doc = bound()
        val tooFar = MoveElements(listOf(ElementId("r1")), limit, 0.0)
        assertTrue(Transaction.begin(doc).apply(tooFar).isLeft())
    }

    // ---- geometry ---------------------------------------------------------------------------------------------------

    @Test
    fun bindingAt_should_PushAnInsidePointToTheNearestEdge() {
        val r = rect("r1", 0.0, 0.0, 100.0, 50.0)
        val b = assertIsBinding(BindingGeometry.bindingAt(r, 90.0, 20.0))
        assertEquals(1.0, b.fixedX, 1e-9)
        assertEquals(0.4, b.fixedY, 1e-9)
    }

    private fun assertIsBinding(b: Binding?): Binding = b ?: error("not bindable")

    @Test
    fun anArrow_should_NotBeBindable() {
        val a = arrow("a1", listOf(pt(0.0, 0.0), pt(1.0, 1.0)))
        assertTrue(!BindingGeometry.isBindable(a))
        assertNull(BindingGeometry.bindingAt(a, 0.0, 0.0))
        assertNull(BindingGeometry.pointOf(a, Binding(ElementId("a1"), 0.0, 0.0)))
    }

    @Test
    fun projectingAnOutlinePointAgain_should_ChangeNothing_ForEveryBindableShape() = runTest {
        val coord = Arb.double(-400.0, 400.0, includeNaNs = false)
        val size = Arb.double(8.0, 300.0, includeNaNs = false)
        val angle = Arb.double(-PI, PI, includeNaNs = false)
        checkAll(coord, coord, size, size, angle, coord, coord) { x, y, w, h, a, px, py ->
            val shapes = listOf(
                rect("s", x, y, w, h, a),
                DiamondElement(ElementBase(ElementId("s")), su(x), su(y), su(w), su(h), a),
                EllipseElement(ElementBase(ElementId("s")), su(x + w / 2.0), su(y + h / 2.0), su(w / 2.0), su(h / 2.0), a),
            )
            for (shape in shapes) {
                val first = assertIsBinding(BindingGeometry.bindingAt(shape, px, py))
                val (ox, oy) = BindingGeometry.pointOf(shape, first) ?: error("no point")
                val second = assertIsBinding(BindingGeometry.bindingAt(shape, ox, oy))
                assertTrue(abs(first.fixedX - second.fixedX) < 1e-6 && abs(first.fixedY - second.fixedY) < 1e-6, "$shape: $first vs $second")
            }
        }
    }

    @Test
    fun aBoundEnd_should_TranslateExactlyWithItsShape_ForAnyMove() = runTest {
        val delta = Arb.double(-1000.0, 1000.0, includeNaNs = false)
        val fraction = Arb.double(0.0, 1.0, includeNaNs = false)
        checkAll(delta, delta, fraction, fraction) { dx, dy, fx, fy ->
            val doc = docOf(rect("r1", 10.0, 20.0, 100.0, 50.0), arrow("a1", listOf(pt(0.0, 0.0), pt(0.0, 0.0)), end = Binding(ElementId("r1"), fx, fy)))
            val first = run(doc, MoveElements(listOf(ElementId("r1")), 0.0, 0.0)).present
            val before = end(first, "a1")
            val moved = Transaction.begin(first).apply(MoveElements(listOf(ElementId("r1")), dx, dy)).getOrNull()?.doc ?: error("move failed")
            assertEquals(before.x.value + dx, end(moved, "a1").x.value, 1e-6)
            assertEquals(before.y.value + dy, end(moved, "a1").y.value, 1e-6)
        }
    }
}
