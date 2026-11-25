package dev.stapler.stelekit.table

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Randomized model for Story 3.2 (pre-mortem P1-1). The feed is ordered but may conflate (drop an emission that a
 * newer one supersedes); sheet actions interleave arbitrarily. Shape taken from emission-trace.md (a), (b2), (c2):
 * the undo/redo emission arrives before its Job completes, so those steps flush the feed first. Not generated
 * (`knownLimit_*` in TableSyncPolicyTest): an undo, or a `markDone`, before the commit's own emission was delivered.
 * Invariants: an `Echo` is only ever reported for the displayed content; the display is never moved by an emission older than the latest write (unless it carries the latest
 * content), and once the feed is drained the display equals the DB (no committed content lost).
 */
class TableSyncPolicyModelTest {
    private class Sim {
        val policy = TableSyncPolicy()
        var counter = 0
        var truth = table("0")
        var display = truth
        val feed = ArrayDeque<Pair<Int, String>>()
        var latestWrite = 0
        val undo = ArrayList<String>()
        val redo = ArrayList<String>()
        val pendingDone = ArrayList<Pair<Long, Int>>()
        var handled = 0
        val failures = ArrayList<String>()

        init { policy.registerLoaded(truth) }

        fun write(content: String) {
            truth = content
            latestWrite++
            feed.addLast(latestWrite to content)
        }

        fun commit() {
            val c = table("${++counter}")
            undo.add(truth); redo.clear()
            display = c
            write(c)
            pendingDone.add(policy.registerCommit(c) to latestWrite)
        }

        fun deliverNext() {
            val (idx, content) = feed.removeFirstOrNull() ?: return
            handled = idx
            val before = display
            val decision = policy.classify(content)
            if (decision == SyncDecision.Echo && content != display) {
                failures.add("Echo for $content while the host displays $display")
            }
            when (val d = decision) {
                is SyncDecision.Adopt -> display = d.content
                is SyncDecision.Rebuild -> display = content
                else -> Unit
            }
            if (idx < latestWrite && content != truth && display != before) {
                failures.add("older emission #$idx ($content) moved display $before -> $display, truth=$truth")
            }
        }

        fun dropNext() { if (feed.size > 1) handled = feed.removeFirst().first }

        fun markOne(i: Int) {
            val ready = pendingDone.filter { it.second <= handled }
            if (ready.isEmpty()) return
            val pick = ready[i % ready.size]
            pendingDone.remove(pick)
            policy.markDone(pick.first)
        }

        fun flush() { while (feed.isNotEmpty()) deliverNext() }

        fun joinAll() { pendingDone.forEach { policy.markDone(it.first) }; pendingDone.clear() }

        fun undoOrRedo(isUndo: Boolean) {
            val from = if (isUndo) undo else redo
            val to = if (isUndo) redo else undo
            if (from.isEmpty()) return
            flush()
            joinAll()
            val target = from.removeAt(from.size - 1)
            to.add(truth)
            policy.expectAdopt(target)
            write(target)
            flush()
        }

        fun foreign() {
            flush(); joinAll()
            undo.clear(); redo.clear()
            write(table("F${++counter}"))
            flush()
        }
    }

    private companion object {
        fun table(v: String) = "| A | B |\n| --- | --- |\n| $v | x |"
    }

    @Test
    fun feedInterleavings_should_NeverRegressOrLoseContent_When_ActionsAndEmissionsInterleave() = runTest {
        checkAll(Arb.list(Arb.int(0..99), 1..60)) { ops ->
            val sim = Sim()
            for (op in ops) {
                when (op % 8) {
                    0, 1 -> sim.commit()
                    2, 3 -> sim.deliverNext()
                    4 -> sim.dropNext()
                    5 -> sim.markOne(op)
                    6 -> sim.undoOrRedo(isUndo = op % 3 != 0)
                    else -> if (op % 5 == 0) sim.foreign() else sim.deliverNext()
                }
                assertEquals(emptyList<String>(), sim.failures)
            }
            sim.flush()
            assertEquals(emptyList<String>(), sim.failures)
            assertEquals(sim.truth, sim.display, "display must equal the DB once the feed is drained")
        }
    }
}
