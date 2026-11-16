// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.BindingEnd
import dev.stapler.stelekit.sketch.v2.scene.BoundKind
import dev.stapler.stelekit.sketch.v2.scene.BoundRef
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.GroupId
import dev.stapler.stelekit.sketch.v2.scene.ReconcileReason
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.reconcile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReconcileTest {
    private fun u(i: Int) = SceneUnit.ofInt(i)

    private fun id(s: String) = ElementId(s)

    private fun base(s: String, groups: List<String> = emptyList(), frame: String? = null) =
        ElementBase(id = id(s), groupIds = groups.map(::GroupId), frameId = frame?.let(::id))

    private fun rect(s: String, bound: List<BoundRef> = emptyList(), groups: List<String> = emptyList()) =
        RectElement(base(s, groups), u(0), u(0), u(10), u(10), boundElements = bound)

    private fun arrow(s: String, start: String? = null, end: String? = null) =
        ArrowElement(base(s), emptyList(), startBinding = start?.let { Binding(id(it), 0.5, 0.5) }, endBinding = end?.let { Binding(id(it), 0.5, 0.5) })

    private fun text(s: String, container: String? = null) =
        TextElement(base(s), u(0), u(0), u(5), u(5), text = "t", containerId = container?.let(::id))

    private fun sceneOf(vararg e: SceneElement): SketchSceneV2 =
        e.fold(SketchSceneV2.empty()) { s, el -> s.add(el).fold({ error("$it") }, { it }) }

    @Test
    fun reconcile_should_ClearBindingAndPruneRef_When_ReferencesDangle() {
        val r1 = rect("r1", listOf(BoundRef(id("a1"), BoundKind.Arrow), BoundRef(id("a9"), BoundKind.Arrow)))
        val a1 = arrow("a1", start = "gone")
        val result = reconcile(sceneOf(r1, a1))
        assertEquals(null, (result.scene[id("a1")] as ArrowElement).startBinding)
        assertEquals(listOf(BoundRef(id("a1"), BoundKind.Arrow)), (result.scene[id("r1")] as RectElement).boundElements)
        assertEquals(
            setOf<ReconcileReason>(
                ReconcileReason.DanglingBinding(id("a1"), BindingEnd.Start, id("gone")),
                ReconcileReason.DanglingBoundElement(id("r1"), id("a9")),
            ),
            result.reasons.toSet(),
        )
        assertEquals(2, result.reasons.size)
        assertEquals(setOf(id("a1"), id("r1")), result.changed)
    }

    @Test
    fun reconcile_should_AddReciprocalRefs_When_ArrowAndLabelPointAtContainer() {
        val result = reconcile(sceneOf(rect("r1"), arrow("a1", end = "r1"), text("t1", container = "r1")))
        assertEquals(
            listOf(BoundRef(id("a1"), BoundKind.Arrow), BoundRef(id("t1"), BoundKind.Text)),
            (result.scene[id("r1")] as RectElement).boundElements,
        )
        assertEquals(2, result.reasons.count { it is ReconcileReason.MissingReciprocalRef })
    }

    @Test
    fun reconcile_should_FixKindAndDropDuplicates_When_RefsAreMalformed() {
        val r1 = rect("r1", listOf(BoundRef(id("t1"), BoundKind.Arrow), BoundRef(id("t1"), BoundKind.Text)))
        val result = reconcile(sceneOf(r1, text("t1", container = "r1")))
        assertEquals(listOf(BoundRef(id("t1"), BoundKind.Text)), (result.scene[id("r1")] as RectElement).boundElements)
        assertTrue(result.reasons.any { it is ReconcileReason.BoundKindFixed })
        assertTrue(result.reasons.any { it is ReconcileReason.DuplicateBoundElement })
    }

    @Test
    fun reconcile_should_ClearArrowToArrowAndSelfBindings() {
        val result = reconcile(sceneOf(arrow("a1", start = "a1", end = "a2"), arrow("a2")))
        val a1 = result.scene[id("a1")] as ArrowElement
        assertEquals(null, a1.startBinding)
        assertEquals(null, a1.endBinding)
        assertEquals(2, result.reasons.count { it is ReconcileReason.InvalidBindingTarget })
    }

    @Test
    fun reconcile_should_ClearContainerId_When_ContainerMissingOrCannotHoldLabels() {
        val frame = FrameElement(base("f"), u(0), u(0), u(50), u(50))
        val result = reconcile(sceneOf(frame, text("t1", container = "nope"), text("t2", container = "f"), text("t3", container = "t1")))
        listOf("t1", "t2", "t3").forEach { assertEquals(null, (result.scene[id(it)] as TextElement).containerId) }
        assertEquals(1, result.reasons.count { it is ReconcileReason.DanglingContainer })
        assertEquals(2, result.reasons.count { it is ReconcileReason.InvalidContainer })
    }

    @Test
    fun reconcile_should_ClearFrameId_When_SelfMissingNotAFrameOrNested() {
        val f1 = FrameElement(base("f1", frame = "f1"), u(0), u(0), u(50), u(50))
        val f2 = FrameElement(base("f2", frame = "f1"), u(0), u(0), u(50), u(50))
        val member = RectElement(base("m", frame = "f1"), u(0), u(0), u(5), u(5))
        val lost = RectElement(base("lost", frame = "missing"), u(0), u(0), u(5), u(5))
        val wrong = RectElement(base("wrong", frame = "m"), u(0), u(0), u(5), u(5))
        val result = reconcile(sceneOf(f1, f2, member, lost, wrong))
        assertEquals(null, result.scene[id("f1")]!!.base.frameId)
        assertEquals(null, result.scene[id("f2")]!!.base.frameId)
        assertEquals(null, result.scene[id("lost")]!!.base.frameId)
        assertEquals(null, result.scene[id("wrong")]!!.base.frameId)
        assertEquals(id("f1"), result.scene[id("m")]!!.base.frameId)
        assertEquals(listOf<ReconcileReason>(ReconcileReason.CyclicFrame(id("f1"))), result.reasons.filterIsInstance<ReconcileReason.CyclicFrame>())
        assertTrue(result.reasons.any { it is ReconcileReason.FrameInFrame })
        assertTrue(result.reasons.any { it is ReconcileReason.DanglingFrame })
        assertTrue(result.reasons.any { it is ReconcileReason.NotAFrame })
    }

    @Test
    fun reconcile_should_PruneSingletonAndDuplicateGroups() {
        val result = reconcile(sceneOf(rect("a", groups = listOf("g", "g", "solo")), rect("b", groups = listOf("g"))))
        assertEquals(listOf(GroupId("g")), result.scene[id("a")]!!.base.groupIds)
        assertEquals(listOf(GroupId("g")), result.scene[id("b")]!!.base.groupIds)
        assertEquals(1, result.reasons.count { it is ReconcileReason.DuplicateGroupId })
        assertEquals(listOf<ReconcileReason>(ReconcileReason.SingletonGroup(id("a"), GroupId("solo"))), result.reasons.filterIsInstance<ReconcileReason.SingletonGroup>())
    }

    @Test
    fun reconcile_should_BumpVersionOnlyOfChangedElements_And_BeIdempotent() {
        val scene = sceneOf(rect("r1", listOf(BoundRef(id("x"), BoundKind.Arrow))), rect("r2"))
        val first = reconcile(scene)
        assertEquals(2, first.scene[id("r1")]!!.base.version)
        assertEquals(1, first.scene[id("r2")]!!.base.version)
        val second = reconcile(first.scene)
        assertTrue(second.isClean)
        assertEquals(first.scene, second.scene)
        assertTrue(second.changed.isEmpty())
    }

    @Test
    fun reconcile_should_ReturnTheSameScene_When_AlreadyConsistent() {
        val scene = sceneOf(rect("r1", listOf(BoundRef(id("a1"), BoundKind.Arrow))), arrow("a1", end = "r1"))
        val result = reconcile(scene)
        assertTrue(result.isClean)
        assertEquals(scene, result.scene)
    }
}
