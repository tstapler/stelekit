// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.binding.BindArrowEnd
import dev.stapler.stelekit.sketch.v2.binding.UnbindArrowEnd
import dev.stapler.stelekit.sketch.v2.clipboard.SceneClipboard
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.DeleteElements
import dev.stapler.stelekit.sketch.v2.commands.DuplicateElements
import dev.stapler.stelekit.sketch.v2.commands.HistoryV2
import dev.stapler.stelekit.sketch.v2.commands.MoveElements
import dev.stapler.stelekit.sketch.v2.commands.ResizeElement
import dev.stapler.stelekit.sketch.v2.commands.RotateElement
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.BindingEnd
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.reconcile
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 3.3.2, Task 3.3.2c (R-18): after ANY sequence of commands every `Binding.elementId` resolves to a live element, no
 * arrow binds to an arrow or itself, `reconcile` has nothing to repair, and the reciprocal entries match both ways (the
 * engine removes the target's entry on unbind, so none outlives its binding). Undo then restores the start exactly.
 */
class BindingInvariantPropertyTest {
    private fun su(i: Int) = SceneUnit.ofInt(i)

    private fun shape(kind: Int, id: String, r: Random): SceneElement {
        val x = r.nextInt(COORD)
        val y = r.nextInt(COORD)
        val w = r.nextInt(MIN_SIDE, MAX_SIDE)
        val h = r.nextInt(MIN_SIDE, MAX_SIDE)
        return when (kind % KINDS) {
            0 -> RectElement(ElementBase(ElementId(id)), su(x), su(y), su(w), su(h))
            1 -> DiamondElement(ElementBase(ElementId(id)), su(x), su(y), su(w), su(h))
            else -> EllipseElement(ElementBase(ElementId(id)), su(x + w), su(y + h), su(w), su(h))
        }
    }

    private fun arrowAt(id: String, r: Random) =
        ArrowElement(ElementBase(ElementId(id)), listOf(ScenePoint(su(r.nextInt(COORD)), su(r.nextInt(COORD))), ScenePoint(su(r.nextInt(COORD)), su(r.nextInt(COORD)))))

    private class Ids(private val prefix: String) : ElementIdSource {
        private var n = 0
        override fun next(): ElementId = ElementId("$prefix${n++}")
    }

    private fun command(doc: SketchDocumentV2, r: Random, serial: Int): Command {
        val scene = doc.scene
        val all = scene.order
        val arrows = scene.elementsInOrder.filterIsInstance<ArrowElement>().map { it.id }
        val shapes = scene.elementsInOrder.filter { it !is ArrowElement }.map { it.id }
        fun any() = all[r.nextInt(all.size)]
        fun some() = all.filter { r.nextInt(2) == 0 }.ifEmpty { listOf(any()) }
        if (all.isEmpty() || shapes.size < 2 || r.nextInt(5) == 0) return AddElement(shape(r.nextInt(KINDS), "s$serial", r))
        if (arrows.size < 2 && r.nextInt(3) == 0) return AddElement(arrowAt("a$serial", r))
        if (arrows.isEmpty()) return DeleteElements(listOf(any()))
        val arrow = arrows[r.nextInt(arrows.size)]
        val end = BindingEnd.entries[r.nextInt(2)]
        val shapeId = shapes[r.nextInt(shapes.size)]
        return when (r.nextInt(OPERATIONS)) {
            0 -> BindArrowEnd(arrow, end, shapeId)
            1 -> MoveElements(some(), r.nextInt(-MOVE, MOVE).toDouble(), r.nextInt(-MOVE, MOVE).toDouble())
            2 -> ResizeElement(shapeId, r.nextInt(COORD).toDouble(), r.nextInt(COORD).toDouble(), r.nextInt(MIN_SIDE, MAX_SIDE).toDouble(), r.nextInt(MIN_SIDE, MAX_SIDE).toDouble())
            3 -> RotateElement(shapeId, r.nextDouble(0.0, TURN))
            4 -> DeleteElements(listOf(any()))
            5 -> SceneClipboard.paste(SceneClipboard.copy(scene, some()), r.nextInt(3), Ids("p$serial-"))
            6 -> some().let { ids -> val gen = Ids("d$serial-"); DuplicateElements(ids, ids.associateWith { gen.next() }, MOVE.toDouble(), MOVE.toDouble()) }
            else -> UnbindArrowEnd(arrow, end)
        }
    }

    private fun boundRefs(e: SceneElement) = when (e) {
        is RectElement -> e.boundElements
        is DiamondElement -> e.boundElements
        is EllipseElement -> e.boundElements
        else -> null
    }

    private fun assertInvariant(scene: SketchSceneV2, context: String) {
        assertTrue(reconcile(scene).isClean, "reconcile has nothing to repair: $context")
        for (a in scene.elementsInOrder.filterIsInstance<ArrowElement>()) for (b in listOfNotNull(a.startBinding, a.endBinding)) {
            val target = scene[b.elementId]
            assertTrue(target != null && target !is ArrowElement && target.id != a.id, "binding of ${a.id.value} resolves: $context")
            val refs = boundRefs(target!!)
            assertTrue(refs == null || refs.any { it.id == a.id }, "${b.elementId.value} lists ${a.id.value}: $context")
        }
        for (e in scene.elementsInOrder) for (ref in boundRefs(e).orEmpty()) {
            val other = scene[ref.id] as? ArrowElement ?: continue
            assertTrue(other.startBinding?.elementId == e.id || other.endBinding?.elementId == e.id, "${e.id.value} lists ${ref.id.value} without a binding: $context")
        }
    }

    @Test
    fun bindingInvariant_should_Hold_When_AnyCommandSequence() = runTest {
        checkAll(Arb.long()) { seed ->
            val r = Random(seed)
            var history = HistoryV2.of(SketchDocumentV2())
            val initial = history.present
            var committed = 0
            var attempts = 0
            while (committed < STEPS && attempts++ < ATTEMPTS) {
                val step = Transaction.begin(history.present).apply(command(history.present, r, attempts)).getOrNull()?.commit()?.getOrNull()
                val next = step?.let { history.commit(it).getOrNull() } ?: continue
                history = next
                committed++
                assertInvariant(history.present.scene, "seed $seed step $committed")
            }
            while (history.canUndo) history = history.undo()
            assertEquals(initial, history.present, "undo all, seed $seed")
        }
    }

    private companion object {
        const val COORD = 400
        const val MIN_SIDE = 20
        const val MAX_SIDE = 120
        const val MOVE = 30
        const val KINDS = 3
        const val OPERATIONS = 8
        const val STEPS = 40
        const val ATTEMPTS = 400
        const val TURN = 6.2
    }
}
