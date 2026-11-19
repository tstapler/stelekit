// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import arrow.core.Either
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.AddLayer
import dev.stapler.stelekit.sketch.v2.commands.AssignToFrame
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.DeleteElements
import dev.stapler.stelekit.sketch.v2.commands.DeleteLayer
import dev.stapler.stelekit.sketch.v2.commands.DuplicateElements
import dev.stapler.stelekit.sketch.v2.commands.GroupElements
import dev.stapler.stelekit.sketch.v2.commands.HistoryV2
import dev.stapler.stelekit.sketch.v2.commands.LockElements
import dev.stapler.stelekit.sketch.v2.commands.MoveElements
import dev.stapler.stelekit.sketch.v2.commands.MoveElementsToLayer
import dev.stapler.stelekit.sketch.v2.commands.MoveLayer
import dev.stapler.stelekit.sketch.v2.commands.PasteElements
import dev.stapler.stelekit.sketch.v2.commands.RemoveFromFrame
import dev.stapler.stelekit.sketch.v2.commands.RenameLayer
import dev.stapler.stelekit.sketch.v2.commands.ReorderElements
import dev.stapler.stelekit.sketch.v2.commands.ResizeElement
import dev.stapler.stelekit.sketch.v2.commands.RotateElement
import dev.stapler.stelekit.sketch.v2.commands.SetLayerLocked
import dev.stapler.stelekit.sketch.v2.commands.SetLayerOpacity
import dev.stapler.stelekit.sketch.v2.commands.SetLayerVisible
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.StyleElements
import dev.stapler.stelekit.sketch.v2.commands.StylePatch
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.commands.UngroupElements
import dev.stapler.stelekit.sketch.v2.commands.ZKeys
import dev.stapler.stelekit.sketch.v2.commands.ZOrderMove
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.BoundKind
import dev.stapler.stelekit.sketch.v2.scene.BoundRef
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.GroupId
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.ValueError
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import dev.stapler.stelekit.sketch.v2.scene.reconcile
import dev.stapler.stelekit.sketch.v2.tools.AddElementCommand
import dev.stapler.stelekit.sketch.v2.tools.ToolCommand
import dev.stapler.stelekit.sketch.v2.tools.toCommand
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElementCommandsTest {
    private fun <L, R> Either<L, R>.right(): R = fold({ error("unexpected Left: $it") }, { it })

    private fun <L, R> Either<L, R>.left(): L = fold({ it }, { error("unexpected Right: $it") })

    private fun u(i: Int) = SceneUnit.ofInt(i)

    private fun id(s: String) = ElementId(s)

    private fun rect(s: String, x: Int = 0, y: Int = 0, w: Int = 10, h: Int = 10, layer: LayerId = LayerId.Default) =
        RectElement(ElementBase(id = id(s), layerId = layer), u(x), u(y), u(w), u(h))

    private fun docOf(vararg elements: SceneElement): SketchDocumentV2 = run(SketchDocumentV2(), AddElements(elements.toList())).present

    /** Applies [commands] as one transaction and commits it to a history that starts at [doc]. */
    private fun run(doc: SketchDocumentV2, vararg commands: Command, history: HistoryV2 = HistoryV2.of(doc)): HistoryV2 {
        var tx = Transaction.begin(history.present)
        for (c in commands) tx = tx.apply(c).right()
        return history.commit(tx.commit().right()).right()
    }

    private fun orderOf(doc: SketchDocumentV2, layer: LayerId = LayerId.Default) =
        doc.scene.elementsInOrder.filter { it.base.layerId == layer }.sortedBy { it.base.zKey }.map { it.id.value }

    @Test
    fun delete_should_UnbindArrowInSameStep_And_UndoRestoresBoth() {
        val container = rect("r1").copy(boundElements = listOf(BoundRef(id("a1"), BoundKind.Arrow)))
        val arrow = ArrowElement(ElementBase(id = id("a1")), emptyList(), endBinding = Binding(id("r1"), 0.5, 0.5))
        val base = docOf(container, arrow)
        assertTrue(reconcile(base.scene).isClean)

        val h = run(base, DeleteElements(listOf(id("r1"))))
        assertNull(h.present.scene[id("r1")])
        assertNull((h.present.scene[id("a1")] as ArrowElement).endBinding)
        assertEquals(1, h.undoDepth)
        assertEquals(base, h.undo().present)
        assertEquals(id("r1"), (h.undo().present.scene[id("a1")] as ArrowElement).endBinding?.elementId)
    }

    @Test
    fun delete_should_CorrectStandalone_When_NotRunThroughATransaction() {
        val container = rect("r1").copy(boundElements = listOf(BoundRef(id("a1"), BoundKind.Arrow)))
        val arrow = ArrowElement(ElementBase(id = id("a1")), emptyList(), endBinding = Binding(id("r1"), 0.5, 0.5))
        val applied = DeleteElements(listOf(id("r1"))).apply(docOf(container, arrow)).right()
        assertNull((applied.doc.scene[id("a1")] as ArrowElement).endBinding)
        assertTrue(reconcile(applied.doc.scene).isClean)
        assertEquals(setOf(id("r1"), id("a1")), applied.changedIds)
    }

    @Test
    fun delete_should_TakeBoundLabelsAlong() {
        val label = TextElement(ElementBase(id = id("t1")), u(0), u(0), u(5), u(5), text = "x", containerId = id("r1"))
        val doc = docOf(rect("r1"), label)
        assertEquals(setOf(id("r1"), id("t1")), DeleteElements(listOf(id("r1"))).apply(doc).right().changedIds)
        assertEquals(0, run(doc, DeleteElements(listOf(id("r1")))).present.scene.size)
        assertEquals(CommandError.UnknownElement("nope"), DeleteElements(listOf(id("nope"))).apply(doc).left())
    }

    @Test
    fun duplicate_should_RemapIds_And_Bindings_When_ContainerAndArrowDuplicatedTogether() {
        val container = rect("r1").copy(boundElements = listOf(BoundRef(id("a1"), BoundKind.Arrow)))
        val arrow = ArrowElement(ElementBase(id = id("a1")), emptyList(), endBinding = Binding(id("r1"), 0.5, 0.5))
        val base = docOf(container, arrow)
        val map = mapOf(id("r1") to id("r2"), id("a1") to id("a2"))

        val doc = run(base, DuplicateElements(listOf(id("r1"), id("a1")), map, dx = 20.0, dy = 0.0)).present
        val copyArrow = doc.scene[id("a2")] as ArrowElement
        val copyRect = doc.scene[id("r2")] as RectElement
        assertEquals(id("r2"), copyArrow.endBinding?.elementId)
        assertEquals(listOf(BoundRef(id("a2"), BoundKind.Arrow)), copyRect.boundElements)
        assertEquals(20.0, copyRect.x.value)
        assertEquals(4, doc.scene.size)
        assertEquals(base.scene[id("r1")], doc.scene[id("r1")])
        assertTrue(reconcile(doc.scene).isClean)
    }

    @Test
    fun duplicate_should_DropReferencesOutsideTheCopiedSet_And_RemapGroups() {
        val arrow = ArrowElement(ElementBase(id = id("a1")), emptyList(), endBinding = Binding(id("r1"), 0.5, 0.5))
        val g = GroupId("g")
        val a = rect("r1").let { it.copy(base = it.base.copy(groupIds = listOf(g))) }
        val b = rect("r2").let { it.copy(base = it.base.copy(groupIds = listOf(g))) }
        val base = docOf(a, b, arrow)

        val doc = run(base, DuplicateElements(listOf(id("a1")), mapOf(id("a1") to id("a2")))).present
        assertNull((doc.scene[id("a2")] as ArrowElement).endBinding)
        assertEquals(listOf(id("r1")), listOf((doc.scene[id("a1")] as ArrowElement).endBinding!!.elementId))

        val grouped = run(base, DuplicateElements(listOf(id("r1"), id("r2")), mapOf(id("r1") to id("c1"), id("r2") to id("c2")))).present
        val copyGroup = (grouped.scene[id("c1")] as RectElement).base.groupIds
        assertEquals(copyGroup, (grouped.scene[id("c2")] as RectElement).base.groupIds)
        assertTrue(copyGroup.single() != g)
        assertEquals(CommandError.MissingRemap("r2"), DuplicateElements(listOf(id("r1"), id("r2")), mapOf(id("r1") to id("c1"))).apply(base).left())
        assertEquals(CommandError.DuplicateElement("r2"), DuplicateElements(listOf(id("r1")), mapOf(id("r1") to id("r2"))).apply(base).left())
    }

    @Test
    fun paste_should_FallBackToDefaultLayer_When_LayerIsUnknownOnTheTargetSheet() {
        val foreign = rect("x", layer = LayerId("elsewhere"))
        val doc = run(SketchDocumentV2(), PasteElements(listOf(foreign), mapOf(id("x") to id("y")))).present
        assertEquals(LayerId.Default, doc.scene[id("y")]!!.base.layerId)
    }

    @Test
    fun move_should_TranslateEveryKind_And_CarryFrameMembersAndLabels() {
        val frame = FrameElement(ElementBase(id = id("fr")), u(0), u(0), u(100), u(100))
        val member = rect("m").let { it.copy(base = it.base.copy(frameId = id("fr"))) }
        val label = TextElement(ElementBase(id = id("t")), u(1), u(1), u(5), u(5), text = "l", containerId = id("m"))
        val line = LineElement(ElementBase(id = id("ln")), listOf(point(0, 0), point(3, 4)))
        val ellipse = EllipseElement(ElementBase(id = id("el")), u(5), u(5), u(2), u(3))
        val doc = docOf(frame, member, label, line, ellipse)

        val moved = run(doc, MoveElements(listOf(id("fr"), id("ln"), id("el")), 10.0, -2.0)).present
        assertEquals(10.0, (moved.scene[id("fr")] as FrameElement).x.value)
        assertEquals(10.0, (moved.scene[id("m")] as RectElement).x.value)
        assertEquals(11.0, (moved.scene[id("t")] as TextElement).x.value)
        assertEquals(-2.0, (moved.scene[id("ln")] as LineElement).points[0].y.value)
        assertEquals(15.0, (moved.scene[id("el")] as EllipseElement).cx.value)
        assertTrue(moved.scene[id("m")]!!.base.version >= 2)
        assertEquals(doc, run(doc, MoveElements(listOf(id("fr")), 5.0, 5.0)).undo().present)
    }

    private fun point(x: Int, y: Int) = dev.stapler.stelekit.sketch.v2.scene.ScenePoint(u(x), u(y))

    @Test
    fun move_should_BeRefused_When_ResultLeavesTheCoordinateRange() {
        val doc = docOf(rect("r"))
        val err = MoveElements(listOf(id("r")), 1.0e12, 0.0).apply(doc).left()
        assertIs<CommandError.ValueRejected>(err)
        assertIs<ValueError.OutOfRange>(err.error)
        assertIs<ValueError.NonFinite>((MoveElements(listOf(id("r")), Double.NaN, 0.0).apply(doc).left() as CommandError.ValueRejected).error)
    }

    @Test
    fun resize_should_FitBoxEllipseAndPolyline() {
        val line = LineElement(ElementBase(id = id("ln")), listOf(point(0, 0), point(10, 20), point(20, 0)))
        val doc = docOf(rect("r"), EllipseElement(ElementBase(id = id("e")), u(5), u(5), u(2), u(2)), line)
        val out = run(doc, ResizeElement(id("r"), 1.0, 2.0, 30.0, 40.0), ResizeElement(id("e"), 0.0, 0.0, 20.0, 10.0), ResizeElement(id("ln"), 100.0, 100.0, 40.0, 40.0)).present
        val r = out.scene[id("r")] as RectElement
        assertEquals(listOf(1.0, 2.0, 30.0, 40.0), listOf(r.x.value, r.y.value, r.width.value, r.height.value))
        val e = out.scene[id("e")] as EllipseElement
        assertEquals(listOf(10.0, 5.0, 10.0, 5.0), listOf(e.cx.value, e.cy.value, e.rx.value, e.ry.value))
        assertEquals(listOf(120.0, 140.0), (out.scene[id("ln")] as LineElement).points[1].let { listOf(it.x.value, it.y.value) })
        assertIs<CommandError.ValueRejected>(ResizeElement(id("r"), 0.0, 0.0, -1.0, 5.0).apply(doc).left())
        assertIs<CommandError.ValueRejected>(ResizeElement(id("e"), 0.0, 0.0, 0.0, 5.0).apply(doc).left())
    }

    @Test
    fun rotate_should_SetAngle_And_RefuseLines() {
        val doc = docOf(rect("r"), LineElement(ElementBase(id = id("ln")), emptyList()))
        assertEquals(0.5, (run(doc, RotateElement(id("r"), 0.5)).present.scene[id("r")] as RectElement).angle)
        assertIs<CommandError.Unsupported>(RotateElement(id("ln"), 0.5).apply(doc).left())
        assertIs<CommandError.ValueRejected>(RotateElement(id("r"), Double.POSITIVE_INFINITY).apply(doc).left())
    }

    @Test
    fun style_should_ApplyPartialPatch_And_RefuseInvalidValues() {
        val doc = docOf(rect("r"), rect("s"))
        val out = run(doc, StyleElements(listOf(id("r")), StylePatch(strokeColor = "#abcdef", strokeWidth = 4.0, opacity = 50))).present
        assertEquals("#abcdef", out.scene[id("r")]!!.base.style.strokeColor)
        assertEquals(50, out.scene[id("r")]!!.base.opacity)
        assertEquals(doc.scene[id("s")], out.scene[id("s")])
        assertIs<CommandError.ValueRejected>(StyleElements(listOf(id("r")), StylePatch(opacity = 101)).apply(doc).left())
        assertIs<CommandError.ValueRejected>(StyleElements(listOf(id("r")), StylePatch(strokeWidth = -1.0)).apply(doc).left())
        assertEquals(CommandError.NoChange, Transaction.begin(doc).apply(StyleElements(listOf(id("r")), StylePatch())).right().commit().left())
    }

    @Test
    fun add_should_PutNewElementOnTopOfItsLayer_And_RefuseUnknownLayerOrDuplicates() {
        val doc = docOf(rect("a"), rect("b"), rect("c"))
        assertEquals(listOf("a", "b", "c"), orderOf(doc))
        assertEquals(listOf("a", "b", "c", "d"), orderOf(run(doc, AddElement(rect("d"))).present))
        assertEquals(CommandError.UnknownLayer("nope"), AddElement(rect("e", layer = LayerId("nope"))).apply(doc).left())
        assertEquals(CommandError.DuplicateElement("a"), AddElement(rect("a")).apply(doc).left())
    }

    @Test
    fun reorder_should_MoveWithinLayer_When_FrontBackForwardBackward() {
        val doc = docOf(rect("a"), rect("b"), rect("c"), rect("d"))
        fun order(move: ZOrderMove, vararg ids: String) = orderOf(ReorderElements(ids.map(::id), move).apply(doc).right().doc)
        assertEquals(listOf("b", "c", "d", "a"), order(ZOrderMove.ToFront, "a"))
        assertEquals(listOf("d", "a", "b", "c"), order(ZOrderMove.ToBack, "d"))
        assertEquals(listOf("a", "c", "b", "d"), order(ZOrderMove.Forward, "b"))
        assertEquals(listOf("b", "a", "c", "d"), order(ZOrderMove.Backward, "b"))
        assertEquals(listOf("a", "b", "c", "d"), order(ZOrderMove.Backward, "a"))
        assertEquals(listOf("a", "b", "c", "d"), order(ZOrderMove.Forward, "d"))
    }

    @Test
    fun reorder_should_KeepRelativeOrderOfTheMovedSet_And_OnlyRewriteMovedKeys() {
        val doc = docOf(rect("a"), rect("b"), rect("c"), rect("d"))
        val h = run(doc, ReorderElements(listOf(id("b"), id("c")), ZOrderMove.ToFront))
        assertEquals(listOf("a", "d", "b", "c"), orderOf(h.present))
        for (keep in listOf("a", "d")) assertEquals(doc.scene[id(keep)]!!.base.zKey, h.present.scene[id(keep)]!!.base.zKey)
        assertEquals(doc, h.undo().present)
    }

    @Test
    fun reorder_should_RenumberLayer_When_NeighbourKeysTie() {
        val tied = listOf("a", "b", "c").map { rect(it).let { r -> r.copy(base = r.base.copy(zKey = ZKey("m"))) } }
        val doc = SketchDocumentV2(tied.fold(SketchDocumentV2().scene) { s, e -> s.add(e).right() })
        val out = run(doc, ReorderElements(listOf(id("a")), ZOrderMove.ToFront)).present
        assertEquals(listOf("b", "c", "a"), orderOf(out))
    }

    @Test
    fun zKeys_should_GenerateStrictlyBetween_When_RandomNeighbours() = runTest {
        checkAll(PropTestConfig(iterations = 300), Arb.long()) { seed ->
            val r = Random(seed)
            val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
            fun key() = ZKey(CharArray(1 + r.nextInt(4)) { alphabet[r.nextInt(alphabet.length)] }.concatToString())
            var lo: ZKey? = if (r.nextInt(5) == 0) null else key()
            var hi: ZKey? = if (r.nextInt(5) == 0) null else key()
            if (lo != null && hi != null && lo > hi) { val t = lo; lo = hi; hi = t }
            val mid = ZKeys.between(lo, hi) ?: return@checkAll
            assertTrue(lo == null || lo < mid, "lo=$lo mid=$mid")
            assertTrue(hi == null || mid < hi, "hi=$hi mid=$mid")
            val up = ZKeys.after(lo)
            assertTrue(lo == null || lo < up)
        }
    }

    @Test
    fun zKeys_should_StayShort_When_AppendingManyElements() {
        var key: ZKey? = null
        repeat(TOP_APPENDS) { key = ZKeys.after(key) }
        assertTrue(key!!.value.length <= KEY_LENGTH_BOUND, key!!.value)
        val seq = ZKeys.sequence(TOP_APPENDS)
        assertEquals(seq.sorted(), seq)
        assertEquals(TOP_APPENDS, seq.toSet().size)
    }

    @Test
    fun reorder_should_MatchModel_When_RandomMoves() = runTest {
        checkAll(PropTestConfig(iterations = 60), Arb.long()) { seed ->
            val r = Random(seed)
            var doc = docOf(*Array(8) { rect("e$it") })
            var model = orderOf(doc)
            repeat(30) {
                val picks = model.filter { r.nextInt(3) == 0 }.ifEmpty { listOf(model[r.nextInt(model.size)]) }
                val move = ZOrderMove.entries[r.nextInt(ZOrderMove.entries.size)]
                val applied = ReorderElements(picks.map(::id), move).apply(doc).right()
                doc = applied.doc
                model = modelMove(model, picks.toSet(), move)
                assertEquals(model, orderOf(doc), "seed $seed $move $picks")
            }
        }
    }

    private fun modelMove(order: List<String>, sel: Set<String>, move: ZOrderMove): List<String> = when (move) {
        ZOrderMove.ToFront -> order.filter { it !in sel } + order.filter { it in sel }
        ZOrderMove.ToBack -> order.filter { it in sel } + order.filter { it !in sel }
        ZOrderMove.Forward -> order.toMutableList().also { l ->
            for (i in l.size - 2 downTo 0) if (l[i] in sel && l[i + 1] !in sel) { val t = l[i]; l[i] = l[i + 1]; l[i + 1] = t }
        }
        ZOrderMove.Backward -> order.toMutableList().also { l ->
            for (i in 1 until l.size) if (l[i] in sel && l[i - 1] !in sel) { val t = l[i]; l[i] = l[i - 1]; l[i - 1] = t }
        }
    }

    @Test
    fun lock_should_SetFlag_And_BeNoChangeWhenAlreadySet() {
        val doc = docOf(rect("a"))
        assertTrue(run(doc, LockElements(listOf(id("a")), true)).present.scene[id("a")]!!.base.locked)
        assertEquals(CommandError.NoChange, Transaction.begin(doc).apply(LockElements(listOf(id("a")), false)).right().commit().left())
    }

    @Test
    fun group_should_NestOutermostLast_And_UngroupPeelsOnlyTheSelectedElementsOutermostLevel() {
        val doc = docOf(rect("a"), rect("b"), rect("c"))
        val inner = run(doc, GroupElements(listOf(id("a"), id("b")), GroupId("g1")))
        val outer = run(inner.present, GroupElements(listOf(id("a"), id("b"), id("c")), GroupId("g2")), history = inner)
        assertEquals(listOf(GroupId("g1"), GroupId("g2")), outer.present.scene[id("a")]!!.base.groupIds)
        assertEquals(listOf(GroupId("g2")), outer.present.scene[id("c")]!!.base.groupIds)

        val afterUngroup = run(outer.present, UngroupElements(listOf(id("c"))), history = outer)
        val peeled = afterUngroup.present
        assertEquals(listOf(GroupId("g1"), GroupId("g2")), peeled.scene[id("a")]!!.base.groupIds, "an unselected member keeps its groups")
        assertEquals(emptyList(), peeled.scene[id("c")]!!.base.groupIds)
        assertEquals(outer.present, afterUngroup.undo().present)

        assertEquals(CommandError.GroupTooSmall(1), GroupElements(listOf(id("a")), GroupId("x")).apply(doc).left())
        assertEquals(CommandError.DuplicateGroup("g1"), GroupElements(listOf(id("a"), id("b")), GroupId("g1")).apply(inner.present).left())
    }

    @Test
    fun ungroup_should_RemoveEachSelectedElementsOwnOutermostGroupOnly_When_TheSelectionIsMixed() {
        val doc = docOf(rect("a"), rect("b"), rect("c"), rect("d"), rect("e"))
        val g1 = run(doc, GroupElements(listOf(id("a"), id("b"), id("c")), GroupId("g1")))
        val g2 = run(g1.present, GroupElements(listOf(id("a"), id("d"), id("e")), GroupId("g2")), history = g1)
        // a is in [g1, g2], b in [g1]; select a and b.
        val out = run(g2.present, UngroupElements(listOf(id("a"), id("b"))), history = g2).present.scene
        assertEquals(listOf(GroupId("g1")), out[id("a")]!!.base.groupIds, "a loses g2 only")
        assertEquals(emptyList(), out[id("b")]!!.base.groupIds, "b loses g1, its own outermost")
        assertEquals(listOf(GroupId("g1")), out[id("c")]!!.base.groupIds, "unselected c is untouched")
        assertEquals(listOf(GroupId("g2")), out[id("d")]!!.base.groupIds, "unselected d is untouched")
    }

    @Test
    fun ungroup_should_PeelOnlyTheSelectedElementsOutermostGroup_Property() = runTest {
        checkAll(Arb.list(Arb.int(0..3), 4..4), Arb.list(Arb.boolean(), 4..4)) { depths, picked ->
            val names = listOf("a", "b", "c", "d")
            var doc = docOf(*names.map { rect(it) }.toTypedArray())
            for (level in 0 until 3) { // element i joins g0..g(depth-1), innermost first
                val members = names.filterIndexed { i, _ -> depths[i] > level }
                if (members.size >= 2) doc = run(doc, GroupElements(members.map(::id), GroupId("g$level"))).present
            }
            val selected = names.filterIndexed { i, _ -> picked[i] }
            if (selected.isEmpty()) return@checkAll
            val before = names.associateWith { doc.scene[id(it)]!!.base.groupIds }
            val peeled = before.mapValues { (n, ids) -> if (n in selected) ids.dropLast(1) else ids }
            val sizes = peeled.values.flatten().groupingBy { it }.eachCount()
            val result = UngroupElements(selected.map(::id)).apply(doc).fold({ error("unexpected Left: $it") }, { it.doc })
            for (n in names) {
                // reconcile drops a group left with a single member; nothing else may differ
                assertEquals(peeled.getValue(n).filter { sizes.getValue(it) >= 2 }, result.scene[id(n)]!!.base.groupIds, n)
            }
        }
    }

    @Test
    fun ungroup_should_DropSingletonGroups_Via_Reconcile() {
        val doc = docOf(rect("a"), rect("b"), rect("c"))
        val grouped = run(doc, GroupElements(listOf(id("a"), id("b")), GroupId("g"))).present
        val out = run(grouped, DeleteElements(listOf(id("b")))).present
        assertEquals(emptyList(), out.scene[id("a")]!!.base.groupIds)
    }

    @Test
    fun frame_should_AssignAndRemoveMembers_And_RefuseNonFrames() {
        val frame = FrameElement(ElementBase(id = id("fr")), u(0), u(0), u(100), u(100))
        val doc = docOf(frame, rect("a"), rect("b"))
        val assigned = run(doc, AssignToFrame(id("fr"), listOf(id("a"), id("b")))).present
        assertEquals(id("fr"), assigned.scene[id("a")]!!.base.frameId)
        assertNull(run(assigned, RemoveFromFrame(listOf(id("a")))).present.scene[id("a")]!!.base.frameId)
        assertEquals(CommandError.NotAFrame("a"), AssignToFrame(id("a"), listOf(id("b"))).apply(doc).left())
        assertEquals(CommandError.NotAFrame("fr"), AssignToFrame(id("fr"), listOf(id("fr"))).apply(doc).left())
        assertNull(run(assigned, DeleteElements(listOf(id("fr")))).present.scene[id("a")]!!.base.frameId)
    }

    @Test
    fun layers_should_AddRenameHideLockFadeAndReorder_As_UndoableSteps() {
        val l2 = LayerId("l2")
        val l3 = LayerId("l3")
        var h = run(SketchDocumentV2(), AddLayer(l2, "Two"), AddLayer(l3, "Three"))
        assertEquals(listOf(LayerId.Default, l2, l3), h.present.scene.layers.map { it.id })
        h = run(h.present, RenameLayer(l2, "Walls"), SetLayerVisible(l2, false), SetLayerLocked(l3, true), SetLayerOpacity(l3, 0.25), history = h)
        assertEquals("Walls", h.present.scene.layer(l2)!!.name)
        assertEquals(false, h.present.scene.layer(l2)!!.visible)
        assertEquals(0.25, h.present.scene.layer(l3)!!.opacity)
        h = run(h.present, MoveLayer(l3, 0), history = h)
        assertEquals(listOf(l3, LayerId.Default, l2), h.present.scene.layers.sortedBy { it.order }.map { it.id })
        assertEquals(2, h.undo().undoDepth)
        assertEquals(listOf(LayerId.Default, l2, l3), h.undo().present.scene.layers.sortedBy { it.order }.map { it.id })
    }

    @Test
    fun layers_should_RefuseBadInput() {
        val doc = SketchDocumentV2()
        assertEquals(CommandError.DuplicateLayer("default"), AddLayer(LayerId.Default, "x").apply(doc).left())
        assertEquals(CommandError.InvalidLayerId(" "), AddLayer(LayerId(" "), "x").apply(doc).left())
        assertIs<CommandError.NameTooLong>(AddLayer(LayerId("n"), "x".repeat(81)).apply(doc).left())
        assertEquals(CommandError.UnknownLayer("zzz"), SetLayerVisible(LayerId("zzz"), true).apply(doc).left())
        assertIs<CommandError.ValueRejected>(SetLayerOpacity(LayerId.Default, 1.5).apply(doc).left())
        assertEquals(CommandError.DefaultLayerProtected, DeleteLayer(LayerId.Default).apply(doc).left())
        var full = doc
        for (i in 1 until 64) full = AddLayer(LayerId("l$i"), "n$i").apply(full).right().doc
        assertEquals(64, full.scene.layers.size)
        assertEquals(CommandError.TooManyLayers(64), AddLayer(LayerId("one-too-many"), "n").apply(full).left())
    }

    @Test
    fun deleteLayer_should_MoveItsElementsToTheDefaultLayerOnTop() {
        val l2 = LayerId("l2")
        val base = run(SketchDocumentV2(), AddLayer(l2, "Two"), AddElement(rect("a")), AddElement(rect("b", layer = l2)), AddElement(rect("c", layer = l2)))
        val out = run(base.present, DeleteLayer(l2), history = base)
        assertNull(out.present.scene.layer(l2))
        assertEquals(listOf("a", "b", "c"), orderOf(out.present))
        assertEquals(base.present, out.undo().present)
        assertEquals(CommandError.UnknownLayer("l2"), MoveElementsToLayer(listOf(id("a")), l2).apply(out.present).left())
    }

    @Test
    fun toolCommand_should_MapToTheUndoableAddCommand() {
        val tool = AddElementCommand(rect("a"))
        val doc = run(SketchDocumentV2(), tool.toCommand().right()).present
        assertEquals(1, doc.scene.size)
        val unknown = object : ToolCommand {}
        assertIs<CommandError.Unsupported>(unknown.toCommand().left())
    }

    @Test
    fun document_should_KeepSheetFields_When_CommandsRun() {
        val extras = kotlinx.serialization.json.JsonObject(mapOf("k" to kotlinx.serialization.json.JsonPrimitive("v")))
        val doc = SketchDocumentV2(sheetUuid = "uuid", extras = extras)
        val h = run(doc, AddElement(rect("a")))
        assertEquals("uuid", h.present.sheetUuid)
        assertEquals(extras, h.undo().present.extras)
        assertEquals("uuid", h.undo().present.sheetUuid)
    }

    private companion object {
        const val TOP_APPENDS = 5_000
        const val KEY_LENGTH_BOUND = 6
    }
}
