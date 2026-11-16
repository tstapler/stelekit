// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import arrow.core.Either
import arrow.core.flatMap
import dev.stapler.stelekit.sketch.v2.commands.Applied
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.Committed
import dev.stapler.stelekit.sketch.v2.commands.DocPatch
import dev.stapler.stelekit.sketch.v2.commands.HistoryV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.BoundKind
import dev.stapler.stelekit.sketch.v2.scene.BoundRef
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.reconcile
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HistoryV2PropertyTest {
    private fun <L, R> Either<L, R>.right(): R = fold({ error("unexpected Left: $it") }, { it })

    private fun <L, R> Either<L, R>.left(): L = fold({ it }, { error("unexpected Right: $it") })

    private fun rect(id: String, x: Int = 0) =
        RectElement(ElementBase(id = ElementId(id)), SceneUnit.ofInt(x), SceneUnit.ZERO, SceneUnit.ofInt(10), SceneUnit.ofInt(10))

    private fun add(e: dev.stapler.stelekit.sketch.v2.scene.SceneElement) = Command { doc ->
        doc.scene.add(e).map { Applied(SketchDocumentV2(it), setOf(e.id)) }
    }

    private fun replace(e: dev.stapler.stelekit.sketch.v2.scene.SceneElement) = Command { doc ->
        doc.scene.replace(e).map { Applied(SketchDocumentV2(it), setOf(e.id)) }
    }

    private fun remove(id: ElementId) = Command { doc ->
        doc.scene.remove(id).map { Applied(SketchDocumentV2(it), setOf(id)) }
    }

    private fun reverse() = Command { doc ->
        doc.scene.reorder(doc.scene.order.reversed()).map { Applied(SketchDocumentV2(it), emptySet()) }
    }

    /** A random command that is valid on [doc] (a failing command would just be skipped by the caller). */
    private fun randomCommand(doc: SketchDocumentV2, r: Random, serial: Int): Command {
        val ids = doc.scene.order
        return when {
            ids.isEmpty() || r.nextInt(4) == 0 -> add(rect("n$serial", r.nextInt(100)))
            r.nextInt(3) == 0 -> remove(ids[r.nextInt(ids.size)])
            r.nextInt(4) == 0 -> reverse()
            else -> replace(rect(ids[r.nextInt(ids.size)].value, r.nextInt(100)).let { it.copy(base = it.base.copy(version = serial + 2)) })
        }
    }

    private fun commitOne(history: HistoryV2, command: Command): HistoryV2? =
        Transaction.begin(history.present).apply(command).flatMap { it.commit() }.flatMap { history.commit(it) }.getOrNull()

    @Test
    fun undoAll_should_RestoreInitialScene_And_RedoAllRestoreFinal_When_50RandomCommands() = runTest {
        checkAll(Arb.long()) { seed ->
            val r = Random(seed)
            val initial = SketchDocumentV2(SketchDocumentV2().scene.add(rect("seed0")).right())
            var history = HistoryV2.of(initial)
            var serial = 0
            var committed = 0
            while (committed < 50) {
                val next = commitOne(history, randomCommand(history.present, r, serial++))
                if (next != null) { history = next; committed++ }
            }
            val final = history.present
            assertEquals(50, history.undoDepth)
            while (history.canUndo) history = history.undo()
            assertEquals(initial, history.present, "undo all, seed $seed")
            while (history.canRedo) history = history.redo()
            assertEquals(final, history.present, "redo all, seed $seed")
        }
    }

    @Test
    fun history_should_EvictWholeOldestEntries_When_120TransactionsCommitted() {
        var history = HistoryV2.of()
        val snapshots = mutableListOf(history.present)
        repeat(120) {
            history = commitOne(history, add(rect("e$it"))) ?: error("commit failed at $it")
            snapshots += history.present
        }
        assertEquals(SceneLimits.Default.maxHistoryEntries, history.undoDepth)
        assertEquals(SceneLimits.Default.maxHistoryEntries, 100)
        while (history.canUndo) history = history.undo()
        assertEquals(snapshots[20], history.present)
        assertEquals(20, history.present.scene.size)
    }

    @Test
    fun history_should_HonourInjectedLimit_And_DropRedoBranchOnCommit() {
        var history = HistoryV2.of(limits = SceneLimits(maxHistoryEntries = 3))
        repeat(5) { history = commitOne(history, add(rect("e$it")))!! }
        assertEquals(3, history.undoDepth)
        history = history.undo().undo()
        assertTrue(history.canRedo)
        history = commitOne(history, add(rect("fresh")))!!
        assertFalse(history.canRedo)
        assertEquals(2, history.undoDepth)
    }

    @Test
    fun undoRedo_should_BeNoOps_When_NothingToUndoOrRedo() {
        val history = HistoryV2.of()
        assertEquals(history.present, history.undo().present)
        assertEquals(history.present, history.redo().present)
    }

    @Test
    fun history_should_RejectStep_When_ItDoesNotStartFromPresent() {
        val h0 = HistoryV2.of()
        val step = Transaction.begin(h0.present).apply(add(rect("a"))).right().commit().right()
        val h1 = h0.commit(step).right()
        assertIs<CommandError.PatchConflict>(h1.commit(step).left())
    }

    @Test
    fun patch_should_ReplayVerbatimForwardAndBackward_When_RandomCommands() = runTest {
        checkAll(Arb.long()) { seed ->
            val r = Random(seed)
            var doc = SketchDocumentV2(SketchDocumentV2().scene.add(rect("seed0")).right())
            repeat(30) { i ->
                val tx = Transaction.begin(doc).apply(randomCommand(doc, r, i)).getOrNull() ?: return@repeat
                val step: Committed = tx.commit().getOrNull() ?: return@repeat
                assertEquals(step.after, step.patch.forward(step.before).right(), "forward, seed $seed")
                assertEquals(step.before, step.patch.backward(step.after).right(), "backward, seed $seed")
                assertEquals(step.patch.changedIds, step.changedIds)
                doc = step.after
            }
        }
    }

    @Test
    fun patch_should_BeRefused_When_DocumentNoLongerMatches() {
        val base = SketchDocumentV2(SketchDocumentV2().scene.add(rect("a")).right())
        val step = Transaction.begin(base).apply(replace(rect("a", x = 5))).right().commit().right()
        val drifted = SketchDocumentV2(base.scene.replace(rect("a", x = 9)).right())
        assertEquals(CommandError.PatchConflict("a"), step.patch.forward(drifted).left())
        assertEquals(CommandError.PatchConflict("order"), step.patch.forward(SketchDocumentV2()).left())
    }

    @Test
    fun transaction_should_GroupCommandsIntoOneStep_And_FailAtomically() {
        val tx = Transaction.begin(SketchDocumentV2())
            .apply(add(rect("a"))).right()
            .apply(add(rect("b"))).right()
        assertEquals(setOf(ElementId("a"), ElementId("b")), tx.changedIds)
        assertEquals(CommandError.DuplicateElement("a"), tx.apply(add(rect("a"))).left())
        assertEquals(2, tx.doc.scene.size)
        val step = tx.commit().right()
        assertEquals(0, step.before.scene.size)
        assertEquals(2, step.after.scene.size)
        val h = HistoryV2.of().commit(step).right()
        assertEquals(1, h.undoDepth)
        assertEquals(0, h.undo().present.scene.size)
    }

    @Test
    fun transaction_should_ReturnNoChange_When_CommandsCancelOut() {
        val base = SketchDocumentV2(SketchDocumentV2().scene.add(rect("a")).right())
        val tx = Transaction.begin(base).apply(replace(rect("a", x = 7))).right().apply(replace(rect("a"))).right()
        assertEquals(CommandError.NoChange, tx.commit().left())
        assertEquals(CommandError.NoChange, Transaction.begin(base).commit().left())
    }

    @Test
    fun transaction_should_ReconcileInTheSameStep_When_CommandLeavesDanglingReference() {
        val container = RectElement(
            ElementBase(id = ElementId("r")), SceneUnit.ZERO, SceneUnit.ZERO, SceneUnit.ofInt(10), SceneUnit.ofInt(10),
            boundElements = listOf(BoundRef(ElementId("a"), BoundKind.Arrow)),
        )
        val arrow = ArrowElement(ElementBase(id = ElementId("a")), emptyList(), endBinding = Binding(ElementId("r"), 0.5, 0.5))
        val base = SketchDocumentV2(SketchDocumentV2().scene.add(container).right().add(arrow).right())
        assertTrue(reconcile(base.scene).isClean)

        val step = Transaction.begin(base).apply(remove(ElementId("r"))).right().commit().right()
        val survivor = step.after.scene[ElementId("a")] as ArrowElement
        assertEquals(null, survivor.endBinding)
        assertEquals(setOf(ElementId("r"), ElementId("a")), step.changedIds)
        val undone = HistoryV2.of(base).commit(step).right().undo().present
        assertEquals(base, undone)
    }
}
