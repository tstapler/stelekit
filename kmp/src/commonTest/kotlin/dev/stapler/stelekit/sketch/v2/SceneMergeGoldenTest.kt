// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.merge.ConflictKind
import dev.stapler.stelekit.sketch.v2.merge.MergeRefusal
import dev.stapler.stelekit.sketch.v2.merge.MergeRepair
import dev.stapler.stelekit.sketch.v2.merge.MergeResult
import dev.stapler.stelekit.sketch.v2.merge.SceneMerge
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.OpeningElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.ReconcileReason
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tricky cases of ADR-008, one scenario each, with the rule they pin. */
class SceneMergeGoldenTest {
    private val s = SceneMergeSupport
    private fun id(v: String) = ElementId(v)

    private fun merge(base: SketchDocumentV2, ours: SketchDocumentV2, theirs: SketchDocumentV2): MergeResult {
        val forward = SceneMerge.merge(base, ours, theirs).getOrNull()!!
        val backward = SceneMerge.merge(base, theirs, ours).getOrNull()!!
        assertEquals(s.bytes(forward.doc), s.bytes(backward.doc), "the merge must not depend on which device computes it")
        return forward
    }

    private fun rect(doc: SketchDocumentV2, v: String) = s.byId(doc, v) as RectElement

    @Test
    fun merge_should_TakeBothChanges_When_DevicesEditDifferentFieldsOfOneElement() {
        val base = s.doc(s.rect("a"))
        val moved = s.withElements(base, listOf(s.rect("a", x = 50, y = 60, ver = 2)))
        val restyled = s.withElements(base, listOf(s.rect("a", ver = 2).let { it.withBase(it.base.copy(opacity = 40)) }))

        val result = merge(base, moved, restyled)

        val a = rect(result.doc, "a")
        assertEquals(50.0, a.x.value)
        assertEquals(60.0, a.y.value)
        assertEquals(40, a.base.opacity)
        assertEquals(emptyList(), result.report.conflicts)
        assertEquals(3, a.base.version, "a merged element gets a version no side had, so caches redraw it")
    }

    @Test
    fun merge_should_NotMixCoordinates_When_BothDevicesMovedTheSameElement() {
        val base = s.doc(s.rect("a"))
        val one = s.withElements(base, listOf(s.rect("a", x = 100, y = 1, ver = 2)))
        val two = s.withElements(base, listOf(s.rect("a", x = 5, y = 200, ver = 2)))

        val result = merge(base, one, two)

        val a = rect(result.doc, "a")
        assertTrue((a.x.value == 100.0 && a.y.value == 1.0) || (a.x.value == 5.0 && a.y.value == 200.0), "x and y come from one side")
        assertEquals(listOf(ConflictKind.SameField), result.report.conflicts.map { it.kind })
        assertEquals(listOf("x"), result.report.conflicts.single().fields)
    }

    @Test
    fun merge_should_KeepTheEdit_When_OneDeviceDeletedAnElementTheOtherEdited() {
        val base = s.doc(s.rect("a"), s.rect("b", z = "a2"))
        val deleted = s.withElements(base, listOf(s.rect("b", z = "a2")))
        val edited = s.withElements(base, listOf(s.rect("a", x = 7, ver = 2), s.rect("b", z = "a2")))

        val result = merge(base, deleted, edited)

        assertEquals(7.0, rect(result.doc, "a").x.value)
        assertEquals(listOf(ConflictKind.DeleteVsEdit), result.report.conflicts.map { it.kind })
    }

    @Test
    fun merge_should_Delete_When_TheOtherDeviceLeftTheElementUntouched() {
        val base = s.doc(s.rect("a"), s.rect("b", z = "a2"))
        val deleted = s.withElements(base, listOf(s.rect("b", z = "a2")))
        val touchedOnlyByVersion = s.withElements(base, listOf(s.rect("a", ver = 9), s.rect("b", z = "a2", x = 3, ver = 2)))

        val result = merge(base, deleted, touchedOnlyByVersion)

        assertEquals(setOf("b"), s.ids(result.doc))
        assertEquals(emptyList(), result.report.conflicts)
        assertEquals(3.0, rect(result.doc, "b").x.value)
    }

    @Test
    fun merge_should_ReportAnAddAdd_When_BothDevicesCreatedTheSameIdDifferently() {
        val base = s.doc()
        val one = s.doc(s.rect("n", x = 1))
        val two = s.doc(s.rect("n", x = 2))

        val result = merge(base, one, two)

        assertEquals(1, result.doc.scene.size)
        assertEquals(2.0, rect(result.doc, "n").x.value, "the greater canonical value wins, on either device")
        assertEquals(ConflictKind.AddAdd, result.report.conflicts.single().kind)
    }

    @Test
    fun merge_should_KeepBothElements_When_DevicesAddedDifferentElementsAtTheSameZKey() {
        val base = s.doc(s.rect("a"))
        val one = s.doc(s.rect("a"), s.rect("p", z = "a2"))
        val two = s.doc(s.rect("a"), s.rect("q", z = "a2"))

        val result = merge(base, one, two)

        assertEquals(listOf("a", "p", "q"), result.doc.scene.elementsInOrder.map { it.id.value })
        assertEquals(listOf(id("q")), result.report.added.filter { it.value == "q" })
    }

    @Test
    fun merge_should_NotMixTextAndOriginalText_When_BothDevicesRewroteTheText() {
        val base = s.doc(s.text("t", "old"))
        fun typed(body: String) = s.withElements(base, listOf((s.text("t", "old", ver = 2) as TextElement).copy(text = body, originalText = body)))

        val result = merge(base, typed("alpha"), typed("beta"))

        val t = s.byId(result.doc, "t") as TextElement
        assertEquals(t.text, t.originalText)
        assertEquals("beta", t.text)
    }

    @Test
    fun merge_should_ClearTheBinding_When_TheOtherDeviceDeletedTheBoundElement() {
        val box = s.rect("box").let { it.copy(boundElements = listOf(dev.stapler.stelekit.sketch.v2.scene.BoundRef(id("arrow"), dev.stapler.stelekit.sketch.v2.scene.BoundKind.Arrow))) }
        val arrow = ArrowElement(
            ElementBase(id("arrow"), zKey = ZKey("a2")),
            listOf(ScenePoint(s.u(0), s.u(0)), ScenePoint(s.u(50), s.u(0))),
            startBinding = Binding(id("box"), 0.5, 0.5),
        )
        val base = s.doc(box, arrow)
        val deleted = s.withElements(base, listOf(arrow))
        val moved = s.withElements(base, listOf(box, arrow.copy(points = listOf(ScenePoint(s.u(0), s.u(0)), ScenePoint(s.u(90), s.u(0))), base = arrow.base.copy(version = 2))))

        val result = merge(base, deleted, moved)

        val merged = s.byId(result.doc, "arrow") as ArrowElement
        assertNull(merged.startBinding)
        assertEquals(90.0, merged.points.last().x.value, "the arrow keeps the edit that was made on the other device")
        assertTrue(result.report.repairs.any { it is MergeRepair.Reconciled && it.reason is ReconcileReason.DanglingBinding })
    }

    @Test
    fun merge_should_DropTheOpening_When_TheOtherDeviceDeletedItsWall() {
        val wall = WallElement(ElementBase(id("wall")), listOf(ScenePoint(s.u(0), s.u(0)), ScenePoint(s.u(1000), s.u(0))), s.u(WALL))
        val door = OpeningElement(ElementBase(id("door"), zKey = ZKey("a2")), id("wall"), 0, s.u(100), s.u(DOOR))
        val other = s.rect("other", z = "a3")
        val base = s.doc(wall, door, other)
        val wallGone = s.withElements(base, listOf(door, other))
        val unrelated = s.withElements(base, listOf(wall, door, s.rect("other", z = "a3", x = 9, ver = 2)))

        val result = merge(base, wallGone, unrelated)

        assertEquals(setOf("other"), s.ids(result.doc))
        assertEquals(listOf<MergeRepair>(MergeRepair.OpeningDropped(id("door"), id("wall"))), result.report.repairs.filterIsInstance<MergeRepair.OpeningDropped>())
    }

    @Test
    fun merge_should_DropTheConstraint_When_ItsOperandWasDeletedOnTheOtherDevice() {
        val horizontal = Constraint(ConstraintId("c1"), ConstraintKind.Fixed, listOf(ConstraintTarget(id("b"))))
        val base = s.doc(s.rect("a"), s.rect("b", z = "a2")).copy(constraints = listOf(horizontal))
        val bGone = s.withElements(base, listOf(s.rect("a"))).copy(constraints = listOf(horizontal))
        val aMoved = s.withElements(base, listOf(s.rect("a", x = 4, ver = 2), s.rect("b", z = "a2"))).copy(constraints = listOf(horizontal))

        val result = merge(base, bGone, aMoved)

        assertEquals(emptyList(), result.doc.constraints)
        assertEquals(listOf(ConstraintId("c1")), result.report.repairs.filterIsInstance<MergeRepair.ConstraintDropped>().map { it.id })
    }

    @Test
    fun merge_should_UnionConstraints_When_BothDevicesAddedDifferentOnes() {
        val base = s.doc(s.rect("a"), s.rect("b", z = "a2"))
        val c1 = Constraint(ConstraintId("c1"), ConstraintKind.Fixed, listOf(ConstraintTarget(id("a"))))
        val c2 = Constraint(ConstraintId("c2"), ConstraintKind.Fixed, listOf(ConstraintTarget(id("b"))))

        val result = merge(base, base.copy(constraints = listOf(c1)), base.copy(constraints = listOf(c2)))

        assertEquals(listOf("c1", "c2"), result.doc.constraints.map { it.id.value })
    }

    @Test
    fun merge_should_MoveElementsToTheDefaultLayer_When_TheirLayerWasDeleted() {
        val onSecond = s.rect("a").let { it.withBase(it.base.copy(layerId = SceneMergeSupport.EXTRA_LAYER)) }
        val base = s.doc(onSecond)
        val layerGone = base.copy(scene = s.scene(emptyList(), listOf(Layer.DefaultLayer)))
        val edited = s.withElements(base, listOf(onSecond.copy(x = s.u(8), base = onSecond.base.copy(version = 2))))

        val result = merge(base, layerGone, edited)

        assertEquals(LayerId.Default, s.byId(result.doc, "a").base.layerId)
        assertEquals(listOf(id("a")), result.report.repairs.filterIsInstance<MergeRepair.MovedToDefaultLayer>().map { it.id })
    }

    @Test
    fun merge_should_TakeTheKindChangeWhole_When_DevicesConvertedAnElementDifferently() {
        val base = s.doc(s.rect("a"))
        val ellipse = EllipseElement(ElementBase(id("a"), version = 2), s.u(5), s.u(5), s.u(3), s.u(3))
        val text = s.text("a", "hi", ver = 2)

        val result = merge(base, s.withElements(base, listOf(ellipse)), s.withElements(base, listOf(text)))

        assertIs<EllipseElement>(s.byId(result.doc, "a"), "the greater canonical JSON wins whole, on either device (the helper checks both orders)")
        assertEquals(ConflictKind.TypeChange, result.report.conflicts.single().kind)
    }

    @Test
    fun merge_should_MergeSheetExtrasPerKey_And_KeepTheHigherNameCounter() {
        val base = s.doc(s.rect("a")).copy(extras = JsonObject(mapOf("units" to JsonPrimitive("in"))))
        val one = base.copy(extras = JsonObject(mapOf("units" to JsonPrimitive("in"), "grid" to JsonPrimitive(true))))
        val two = base.copy(extras = JsonObject(mapOf("units" to JsonPrimitive("mm"))))

        val result = merge(base, one, two)

        assertEquals(JsonObject(mapOf("units" to JsonPrimitive("mm"), "grid" to JsonPrimitive(true))), result.doc.extras)
    }

    @Test
    fun merge_should_ReturnTheOtherSideUnchanged_When_OneSideEqualsTheBase() {
        val base = s.doc(s.rect("a"))
        val theirs = s.withElements(base, listOf(s.rect("a", x = 2, ver = 2), s.rect("n", z = "a2")))

        val result = merge(base, base, theirs)

        assertEquals(s.bytes(theirs), s.bytes(result.doc), "a fast-forward is byte-identical to the file on disk")
        assertEquals(listOf(id("n")), result.report.added)
        assertEquals(listOf(id("a")), result.report.changed)
    }

    @Test
    fun merge_should_Refuse_When_TheSheetsDiffer() {
        val one = s.doc(s.rect("a"))
        val other = one.copy(sheetUuid = "other")

        assertEquals(MergeRefusal.SheetMismatch, SceneMerge.merge(one, one, other).leftOrNull())
    }

    @Test
    fun merge_should_Refuse_When_TheResultWouldExceedTheElementCap() {
        val tiny = SceneLimits(maxElements = 2)
        val base = SketchDocumentV2(dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2.empty(tiny), SceneMergeSupport.SHEET)
        fun with(vararg e: RectElement) = base.copy(scene = e.fold(base.scene) { sc, el -> sc.add(el).getOrNull()!! })

        val refusal = SceneMerge.merge(base, with(s.rect("a"), s.rect("b", z = "a2")), with(s.rect("c", z = "a3"))).leftOrNull()

        assertEquals(MergeRefusal.CapacityExceeded("elements"), refusal)
    }

    @Test
    fun merge_should_GiveTheSameBytes_When_RunTwiceOnTheSameInputs() {
        val base = s.doc(s.rect("a"), s.rect("b", z = "a2"))
        val one = s.withElements(base, listOf(s.rect("a", x = 1, ver = 2), s.rect("b", z = "a2")))
        val two = s.withElements(base, listOf(s.rect("a", y = 1, ver = 2), s.rect("b", z = "a2", w = 99, ver = 2)))

        assertEquals(s.bytes(merge(base, one, two).doc), s.bytes(merge(base, one, two).doc))
    }

    private companion object {
        const val WALL = 72
        const val DOOR = 576
    }
}
