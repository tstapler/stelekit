package dev.stapler.stelekit.table

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Randomized model for Story 3.2 (pre-mortem P1-1, P5-5). The feed is ordered but may conflate (drop an emission that a
 * newer one supersedes); sheet actions interleave arbitrarily: `markDone` may precede an older echo, foreign writes land
 * while commits are `InFlight`, and an undo or redo may be issued before older emissions were delivered. Undo/redo
 * emissions still arrive before their Job completes (emission-trace.md (b), (c2)). Invariants: an `Echo` is only ever
 * reported for the displayed content; an `Adopt` never moves the display to a write older than the one it shows (unless it is a
 * registered undo/redo target; a foreign `Rebuild` that follows `markDone` may flash older content before the commit's own
 * emission re-adopts it, accepted), and once the feed is drained and every Job joined the
 * display equals the DB (no committed content lost).
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
        var lastObserved = truth
        var lastObservedIdx = 0
        var displayIdx = 0
        val expected = HashSet<String>()
        val failures = ArrayList<String>()
        val log = ArrayList<String>()
        val history = ArrayList<String>()
        val writes = HashMap<Int, String>()
        var ownIdx = 0
        var ownDelivered = true
        var ownAmbiguous = false
        val ownWriteIdxs = HashSet<Int>()
        val outsideWriteIdxs = HashSet<Int>()
        val pool = listOf("0", "1", "2")
        fun short(c: String) = c.lines().last().filter { it.isLetterOrDigit() }

        // The feed delivers the loaded state first, possibly late (after commits were already registered).
        init { policy.registerLoaded(truth); history.add(truth); feed.addLast(0 to truth) }

        /** Content seen before while an earlier own emission is still due cannot tell its old emission from its new one. */
        private fun beginOwnWrite(ambiguous: Boolean) {
            ownAmbiguous = ambiguous
            ownDelivered = false
            ownIdx = latestWrite
            ownWriteIdxs.add(latestWrite)
        }

        fun write(content: String) {
            truth = content
            history.add(content)
            writes[latestWrite + 1] = content
            latestWrite++
            feed.addLast(latestWrite to content)
        }

        fun commit(pick: Int) {
            val c = table(pool[pick % pool.size])
            if (c == display || c == truth) return
            // Same content already due on the feed from an outside write: its emission cannot be told from this commit's.
            if (feed.any { it.first in outsideWriteIdxs && it.second == c }) return
            undo.add(truth); redo.clear()
            display = c
            val ambiguousBefore = c in history && !ownDelivered
            write(c)
            displayIdx = latestWrite
            beginOwnWrite(ambiguousBefore)
            pendingDone.add(policy.registerCommit(c) to latestWrite)
            log.add("commit #$latestWrite ${short(c)}")
        }

        fun deliverNext(dup: Boolean = false) {
            val (idx, content) = feed.removeFirstOrNull() ?: return
            if (dup) feed.addFirst(idx to content)
            lastObserved = content
            lastObservedIdx = idx
            if (idx == ownIdx && !ownAmbiguous) ownDelivered = true
            val before = display
            val d = policy.classify(content)
            log.add("deliver #$idx ${short(content)} -> ${d::class.simpleName} display=${short(display)}")
            apply(d, content, idx, before)
            if (d is SyncDecision.Rebuild && idx in ownWriteIdxs) failures.add("own emission #$idx ${short(content)} treated as an external edit")
        }

        fun apply(decision: SyncDecision?, content: String, idx: Int, before: String) {
            if (decision == null) return
            if (decision == SyncDecision.Echo && content != display) {
                failures.add("Echo for $content while the host displays $display")
            }
            when (val d = decision) {
                is SyncDecision.Adopt -> display = d.content
                is SyncDecision.Rebuild -> display = content
                else -> Unit
            }
            if (display != before) {
                if (idx < displayIdx && content !in expected && decision is SyncDecision.Adopt) {
                    failures.add("older emission #$idx ($content) moved display $before -> $display (shown #$displayIdx)")
                }
                displayIdx = idx
            }
        }

        fun dropNext() { if (feed.size > 1) feed.removeFirst() }

        fun markOne(i: Int) {
            if (pendingDone.isEmpty()) return
            val pick = pendingDone[i % pendingDone.size]
            pendingDone.remove(pick)
            val released = policy.markDone(pick.first)
            log.add("markDone #${pick.second} -> ${released?.let { it::class.simpleName }}")
            if (released is SyncDecision.Rebuild && lastObservedIdx in ownWriteIdxs && lastObservedIdx < ownIdx && lastObserved != truth) {
                failures.add("markDone released the older own echo #$lastObservedIdx")
            }
            apply(released, lastObserved, lastObservedIdx, display)
        }

        fun flush() { while (feed.isNotEmpty()) deliverNext() }

        fun joinAll() {
            while (pendingDone.isNotEmpty()) markOne(0)
        }

        fun undoOrRedo(isUndo: Boolean) {
            val from = if (isUndo) undo else redo
            val to = if (isUndo) redo else undo
            if (from.isEmpty()) return
            // An undo target that equals a still-undelivered outside write's content, or an older own write's content whose
            // emission is still due, cannot be told apart on the feed (content is all the policy sees).
            if (!ownDelivered || feed.any { it.first in outsideWriteIdxs }) return
            val target = from.removeAt(from.size - 1)
            to.add(truth)
            policy.expectAdopt(target)
            expected.add(target)
            val ambiguousBefore = target in history && !ownDelivered
            write(target)
            beginOwnWrite(ambiguousBefore)
            log.add("undo/redo #$latestWrite -> ${short(target)}")
        }

        /** A genuine external edit restoring older content; only legal once the newest own emission was observed (ordered feed). */
        fun revert(pick: Int) {
            if (!ownDelivered) return
            // Content equal to a still-InFlight own commit stays Stale by design (last writer wins on the next commit).
            val inFlight = pendingDone.map { it.second }.mapNotNull { writes[it] }.toSet()
            val older = history.filter { it != truth && it !in inFlight }.distinct()
            if (older.isEmpty()) return
            undo.clear(); redo.clear()
            val target = older[pick % older.size]
            write(target)
            outsideWriteIdxs.add(latestWrite)
            log.add("revert #$latestWrite -> ${short(target)}")
        }

        fun foreign() {
            undo.clear(); redo.clear()
            write(table("F${++counter}"))
            outsideWriteIdxs.add(latestWrite)
            log.add("foreign #$latestWrite F$counter")
        }
    }

    private companion object {
        fun table(v: String) = "| A | B |\n| --- | --- |\n| $v | x |"
    }

    /** Pins the documented limit: content-only classification cannot tell an outside write's emission from an own commit's of the same content. */
    @Test
    fun classify_should_ReadOwnEmissionAsExternal_When_OutsideWriteOfSameContentIsStillDue() {
        val policy = TableSyncPolicy()
        val zero = table("0")
        val one = table("1")
        policy.registerLoaded(zero)
        policy.classify(zero)
        val first = policy.registerCommit(one)
        assertEquals(SyncDecision.Echo, policy.classify(one))
        policy.markDone(first)
        assertEquals(SyncDecision.Rebuild(null), policy.classify(zero)) // outside revert, delivered
        // outside writes 1 and 0 are still due when the user commits 1 then 0
        policy.registerCommit(one)
        assertEquals(SyncDecision.Echo, policy.classify(one)) // the outside 1's emission, read as the commit's echo
        val last = policy.registerCommit(zero)
        policy.markDone(last)
        assertEquals(SyncDecision.Echo, policy.classify(zero)) // the outside 0's emission lifts the floor
        assertEquals(SyncDecision.Rebuild(null), policy.classify(one)) // the commit's own emission, now read as external
    }

    @Test
    fun feedInterleavings_should_NeverRegressOrLoseContent_When_ActionsAndEmissionsInterleave() = runTest {
        checkAll(Arb.list(Arb.int(0..99), 1..60)) { ops ->
            val sim = Sim()
            for (op in ops) {
                when (op % 8) {
                    0, 1 -> sim.commit(op / 8)
                    2, 3 -> sim.deliverNext(dup = op % 4 == 0)
                    4 -> sim.dropNext()
                    5 -> sim.markOne(op)
                    6 -> sim.undoOrRedo(isUndo = op % 3 != 0)
                    else -> when {
                        op % 5 == 0 -> sim.foreign()
                        op % 5 <= 2 -> sim.revert(op)
                        else -> sim.deliverNext()
                    }
                }
                assertEquals(emptyList<String>(), sim.failures, sim.log.joinToString("\n"))
            }
            sim.flush()
            sim.joinAll()
            assertEquals(emptyList<String>(), sim.failures, sim.log.joinToString("\n"))
            assertEquals(sim.truth, sim.display, "display must equal the DB once the feed is drained\n" + sim.log.joinToString("\n"))
        }
    }
}
