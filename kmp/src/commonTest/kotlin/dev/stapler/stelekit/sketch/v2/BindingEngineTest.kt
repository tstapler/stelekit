// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.binding.BindArrowEnd
import dev.stapler.stelekit.sketch.v2.binding.BindingGeometry
import dev.stapler.stelekit.sketch.v2.binding.UnbindArrowEnd
import dev.stapler.stelekit.sketch.v2.clipboard.SceneClipboard
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.DuplicateElements
import dev.stapler.stelekit.sketch.v2.commands.HistoryV2
import dev.stapler.stelekit.sketch.v2.commands.MoveElements
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.BindingEnd
import dev.stapler.stelekit.sketch.v2.scene.BoundKind
import dev.stapler.stelekit.sketch.v2.scene.BoundRef
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.reconcile
import dev.stapler.stelekit.sketch.v2.scene.reconciled
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.3.2: rebinding and unbinding, an arrow dragged alone, and the copy, paste and duplicate matrix. */
class BindingEngineTest {
    private fun su(d: Double) = SceneUnit.of(d).getOrNull() ?: error("out of range: $d")

    private fun pt(x: Double, y: Double) = ScenePoint(su(x), su(y))

    private fun rect(id: String, x: Double, y: Double, w: Double = 100.0, h: Double = 50.0, angle: Double = 0.0) =
        RectElement(ElementBase(ElementId(id)), su(x), su(y), su(w), su(h), angle)

    private fun arrow(id: String, points: List<ScenePoint>, start: Binding? = null, end: Binding? = null) =
        ArrowElement(ElementBase(ElementId(id)), points, start, end)

    private fun docOf(vararg elements: SceneElement): SketchDocumentV2 =
        SketchDocumentV2(elements.fold(SketchSceneV2.empty()) { s, e -> s.add(e).getOrNull() ?: error("add ${e.id}") }.reconciled())

    private fun run(doc: SketchDocumentV2, command: Command): HistoryV2 {
        val committed = Transaction.begin(doc).apply(command).getOrNull()?.commit()?.getOrNull() ?: error("command failed or changed nothing")
        return HistoryV2.of(doc).commit(committed).getOrNull() ?: error("history refused")
    }

    private fun arrowOf(doc: SketchDocumentV2, id: String) = assertIs<ArrowElement>(doc.scene[ElementId(id)])

    private fun refsOf(doc: SketchDocumentV2, id: String): List<ElementId> =
        assertIs<RectElement>(doc.scene[ElementId(id)]).boundElements.map { it.id }

    private val r1Right = Binding(ElementId("r1"), 1.0, 0.5)
    private val r2Left = Binding(ElementId("r2"), 0.0, 0.5)

    /** r1 at x 0..100, r2 at x 300..400 (both y 0..50), and an arrow bound at both ends. */
    private fun linked() = docOf(
        rect("r1", 0.0, 0.0), rect("r2", 300.0, 0.0),
        arrow("a1", listOf(pt(100.0, 25.0), pt(300.0, 25.0)), start = r1Right, end = r2Left),
    )

    @Test
    fun arrow_should_UnbindBothEnds_When_DraggedAlone() {
        val history = run(linked(), MoveElements(listOf(ElementId("a1")), 0.0, 40.0))
        val a = arrowOf(history.present, "a1")
        assertNull(a.startBinding)
        assertNull(a.endBinding)
        assertEquals(65.0, a.points.first().y.value)
        assertEquals(1, history.undoDepth)
        assertEquals(linked(), history.undo().present)
    }

    @Test
    fun targets_should_ForgetTheArrow_When_ItIsUnboundByADrag() {
        val after = run(linked(), MoveElements(listOf(ElementId("a1")), 5.0, 5.0)).present
        assertTrue(refsOf(after, "r1").isEmpty())
        assertTrue(refsOf(after, "r2").isEmpty())
        assertTrue(reconcile(after.scene).isClean)
    }

    @Test
    fun arrow_should_KeepItsBindings_When_MovedWithBothShapes() {
        val ids = listOf("r1", "r2", "a1").map(::ElementId)
        val after = run(linked(), MoveElements(ids, 12.0, 7.0)).present
        val a = arrowOf(after, "a1")
        assertEquals(r1Right, a.startBinding)
        assertEquals(r2Left, a.endBinding)
        assertEquals(listOf(ElementId("a1")), refsOf(after, "r1"))
    }

    @Test
    fun onlyTheStrandedEnd_should_Unbind_When_ArrowMovesWithOneOfItsShapes() {
        val after = run(linked(), MoveElements(listOf(ElementId("r1"), ElementId("a1")), 0.0, 30.0)).present
        val a = arrowOf(after, "a1")
        assertEquals(r1Right, a.startBinding)
        assertNull(a.endBinding)
        assertTrue(refsOf(after, "r2").isEmpty())
        assertEquals(listOf(ElementId("a1")), refsOf(after, "r1"))
    }

    @Test
    fun arrow_should_BindBothEndsToTheSameShape() {
        val doc = docOf(rect("r1", 0.0, 0.0), arrow("a1", listOf(pt(100.0, 10.0), pt(100.0, 40.0))))
        val first = run(doc, BindArrowEnd(ElementId("a1"), BindingEnd.Start, ElementId("r1"))).present
        val both = run(first, BindArrowEnd(ElementId("a1"), BindingEnd.End, ElementId("r1"))).present
        val a = arrowOf(both, "a1")
        assertEquals(ElementId("r1"), a.startBinding?.elementId)
        assertEquals(ElementId("r1"), a.endBinding?.elementId)
        assertEquals(listOf(ElementId("a1")), refsOf(both, "r1"))
        assertTrue(reconcile(both.scene).isClean)
    }

    @Test
    fun bind_should_SnapTheEndOntoTheOutline_ForARotatedTarget() {
        val doc = docOf(rect("r1", 0.0, 0.0, 100.0, 50.0, angle = PI / 2.0), arrow("a1", listOf(pt(300.0, 300.0), pt(80.0, 25.0))))
        val bound = arrowOf(run(doc, BindArrowEnd(ElementId("a1"), BindingEnd.End, ElementId("r1"))).present, "a1")
        val target = assertNotNull(doc.scene[ElementId("r1")])
        val expected = assertNotNull(BindingGeometry.pointOf(target, assertNotNull(bound.endBinding)))
        assertEquals(expected.first, bound.points.last().x.value, 1e-6)
        assertEquals(expected.second, bound.points.last().y.value, 1e-6)
    }

    @Test
    fun rebind_should_MoveTheReciprocalEntryToTheNewTarget() {
        val after = run(linked(), BindArrowEnd(ElementId("a1"), BindingEnd.End, ElementId("r1"))).present
        assertEquals(ElementId("r1"), arrowOf(after, "a1").endBinding?.elementId)
        assertTrue(refsOf(after, "r2").isEmpty())
        assertEquals(listOf(ElementId("a1")), refsOf(after, "r1"))
        assertTrue(reconcile(after.scene).isClean)
    }

    @Test
    fun unbindEnd_should_LeaveTheEndWhereItIs_And_DropTheEntry() {
        val after = run(linked(), UnbindArrowEnd(ElementId("a1"), BindingEnd.End)).present
        val a = arrowOf(after, "a1")
        assertNull(a.endBinding)
        assertEquals(300.0, a.points.last().x.value)
        assertTrue(refsOf(after, "r2").isEmpty())
        assertEquals(r1Right, a.startBinding)
    }

    @Test
    fun bind_should_Refuse_When_TheTargetIsAnArrow() {
        val doc = docOf(arrow("a1", listOf(pt(0.0, 0.0), pt(10.0, 0.0))), arrow("a2", listOf(pt(0.0, 5.0), pt(10.0, 5.0))))
        assertTrue(BindArrowEnd(ElementId("a1"), BindingEnd.End, ElementId("a2")).apply(doc).isLeft())
    }

    // ---- copy, paste and duplicate ------------------------------------------------------------------------------------

    private class Ids : ElementIdSource {
        private var n = 0
        override fun next(): ElementId = ElementId("copy${n++}")
    }

    private fun pasted(doc: SketchDocumentV2, ids: List<String>): SketchDocumentV2 {
        val payload = SceneClipboard.copy(doc.scene, ids.map(::ElementId))
        return run(doc, SceneClipboard.paste(payload, 0, Ids())).present
    }

    private fun copies(before: SketchDocumentV2, after: SketchDocumentV2) = after.scene.elementsInOrder.filter { it.id !in before.scene }

    @Test
    fun paste_should_UnbindTheArrow_When_ItsTargetsWereNotCopied() {
        val doc = linked()
        val after = pasted(doc, listOf("a1"))
        val copy = assertIs<ArrowElement>(copies(doc, after).single())
        assertNull(copy.startBinding)
        assertNull(copy.endBinding)
        assertEquals(doc.scene[ElementId("a1")], after.scene[ElementId("a1")])
        assertEquals(listOf(ElementId("a1")), refsOf(after, "r1"))
    }

    @Test
    fun paste_should_LeaveTheOriginalArrowAlone_When_OnlyAShapeIsCopied() {
        val doc = linked()
        val after = pasted(doc, listOf("r1"))
        val copy = assertIs<RectElement>(copies(doc, after).single())
        assertTrue(copy.boundElements.isEmpty())
        assertEquals(r1Right, arrowOf(after, "a1").startBinding)
    }

    @Test
    fun paste_should_RebindTheCopies_When_ArrowAndBothShapesAreCopied() {
        val doc = linked()
        val after = pasted(doc, listOf("r1", "r2", "a1"))
        val copied = copies(doc, after)
        val arrowCopy = assertIs<ArrowElement>(copied.single { it is ArrowElement })
        val shapeIds = copied.filterIsInstance<RectElement>().map { it.id }.toSet()
        assertEquals(shapeIds, setOf(assertNotNull(arrowCopy.startBinding).elementId, assertNotNull(arrowCopy.endBinding).elementId))
        for (shape in copied.filterIsInstance<RectElement>()) assertEquals(listOf(BoundRef(arrowCopy.id, BoundKind.Arrow)), shape.boundElements)
        assertEquals(r1Right, arrowOf(after, "a1").startBinding)
        assertTrue(reconcile(after.scene).isClean)
    }

    @Test
    fun pastedCopies_should_FollowTheirOwnShape_NotTheOriginal() {
        val doc = linked()
        val after = pasted(doc, listOf("r1", "r2", "a1"))
        val copiedShape = copies(doc, after).filterIsInstance<RectElement>().first()
        val moved = run(after, MoveElements(listOf(copiedShape.id), 0.0, 100.0)).present
        assertEquals(arrowOf(after, "a1"), arrowOf(moved, "a1"))
    }

    @Test
    fun paste_should_KeepOnlyTheBindingWhoseShapeWasCopied() {
        val doc = linked()
        val after = pasted(doc, listOf("r1", "a1"))
        val copy = assertIs<ArrowElement>(copies(doc, after).single { it is ArrowElement })
        assertNotNull(copy.startBinding)
        assertNull(copy.endBinding)
    }

    @Test
    fun duplicate_should_RebindToTheDuplicates_When_ArrowAndShapesAreSelected() {
        val doc = linked()
        val ids = listOf("r1", "r2", "a1").map(::ElementId)
        val generator = Ids()
        val after = run(doc, DuplicateElements(ids, ids.associateWith { generator.next() }, 10.0, 10.0)).present
        val arrowCopy = assertIs<ArrowElement>(copies(doc, after).single { it is ArrowElement })
        assertEquals(setOf("copy0", "copy1"), setOf(arrowCopy.startBinding, arrowCopy.endBinding).map { it?.elementId?.value }.toSet())
        assertTrue(reconcile(after.scene).isClean)
    }
}
