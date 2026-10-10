package dev.stapler.stelekit.merge

import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MergePagePropertyTest {
    private val iterations = 500

    private fun merge(t: MergePage?, s: MergePage, p: MergePolicy) = MergeArbs.merge(t, s, p)

    private fun flatten(bs: List<MergeBlock>): List<MergeBlock> = bs.flatMap { listOf(it) + flatten(it.children) }

    private val refRegex = Regex("\\(\\(([^()\\s]+)\\)\\)")
    private fun mask(s: String) = s.replace(refRegex, "(())")

    /** Target tree embeds in [r]: same blocks, in order, unmodified, children recursively. */
    private fun embeds(t: List<MergeBlock>, r: List<MergeBlock>): Boolean {
        var cursor = 0
        for (tb in t) {
            while (cursor < r.size && !(r[cursor].uuid == tb.uuid && r[cursor].content == tb.content && r[cursor].properties == tb.properties)) cursor++
            if (cursor == r.size || !embeds(tb.children, r[cursor].children)) return false
            cursor++
        }
        return true
    }

    /** Every incoming block (ref-masked, since refs are rewritten) has an equal-content sibling, children covered recursively. */
    private fun covers(inc: List<MergeBlock>, r: List<MergeBlock>, key: (String) -> String): Boolean = inc.all { i ->
        r.any { key(mask(it.content)) == key(mask(i.content)) && covers(i.children, it.children, key) }
    }

    /** Unlabeled target blocks keep their sibling index; labeled ones are found by uuid. */
    private fun positionsStable(t: List<MergeBlock>, r: List<MergeBlock>): Boolean = t.withIndex().all { (i, tb) ->
        val rb = if (tb.uuid == null) r.getOrNull(i)?.takeIf { it.uuid == null && it.content == tb.content } else r.firstOrNull { it.uuid == tb.uuid }
        rb != null && positionsStable(tb.children, rb.children)
    }

    private fun conflictCount(p: MergePage) = flatten(p.blocks).count { it.properties[MergePropertyKeys.CONFLICT] == "true" }

    @Test
    fun idempotent() = runTest {
        checkAll(iterations, MergeArbs.cases(0.4)) { c ->
            val once = merge(c.target, c.source, c.policy)
            assertEquals(MergeOutcome.Unchanged, mergePage(once, c.source, c.policy))
        }
    }

    @Test
    fun noLossInOrderAndAdditiveOnly() = runTest {
        checkAll(iterations, MergeArbs.cases(0.4)) { c ->
            val out = merge(c.target, c.source, c.policy)
            assertTrue(embeds(c.target.blocks, out.blocks), "target blocks must survive unmodified, in order")
            assertTrue(covers(c.source.blocks, out.blocks, c.policy.blockKey), "every source block or its conflict copy must be present")
        }
    }

    @Test
    fun selfMergeAndEmptySourceAreUnchanged() = runTest {
        checkAll(iterations, MergeArbs.cases(0.4)) { c ->
            assertEquals(MergeOutcome.Unchanged, mergePage(c.target, c.target, c.policy))
            assertEquals(MergeOutcome.Unchanged, mergePage(c.target, MergePage("P"), c.policy))
        }
    }

    @Test
    fun noDuplicateUuidInResult() = runTest {
        checkAll(iterations, MergeArbs.cases(0.4)) { c ->
            for (page in listOf(merge(c.target, c.source, c.policy), merge(c.editedTarget, c.source, c.policy))) {
                val ids = flatten(page.blocks).mapNotNull { it.uuid }
                assertEquals(ids.size, ids.toSet().size, "duplicate uuid in $ids")
            }
        }
    }

    @Test
    fun disjointMergesAreBlockSetCommutative() = runTest {
        checkAll(iterations, MergeArbs.disjointPairs()) { (t, s, p) ->
            fun contents(page: MergePage) = flatten(page.blocks).map { it.content }.sorted()
            assertEquals(contents(merge(t, s, p)), contents(merge(s, t, p)))
        }
    }

    @Test
    fun editThenRecopyAddsOneConflictPerEditedBlockThenNone() = runTest {
        checkAll(iterations, MergeArbs.cases(0.0)) { c ->
            val before = conflictCount(c.editedTarget)
            val again = merge(c.editedTarget, c.source, c.policy)
            assertEquals(c.editedRoots, conflictCount(again) - before)
            assertEquals(MergeOutcome.Unchanged, mergePage(again, c.source, c.policy))
        }
    }

    @Test
    fun sourceDriftIsIdempotent() = runTest {
        checkAll(iterations, MergeArbs.cases(0.4)) { c ->
            val m2 = merge(merge(c.target, c.source, c.policy), c.drifted, c.policy)
            assertEquals(MergeOutcome.Unchanged, mergePage(m2, c.drifted, c.policy))
        }
    }

    @Test
    fun unlabeledTargetBlocksKeepTheirPositions() = runTest {
        checkAll(iterations, MergeArbs.cases(0.5)) { c ->
            assertTrue(positionsStable(c.target.blocks, merge(c.target, c.source, c.policy).blocks))
            assertTrue(positionsStable(c.editedTarget.blocks, merge(c.editedTarget, c.drifted, c.policy).blocks))
        }
    }

    @Test
    fun refsNeverDangleToRemappedUuidsOfDedupedBlocks() = runTest {
        // Fully labeled target: an unlabeled one has no uuid a ref could point at.
        checkAll(iterations, MergeArbs.cases(0.0)) { c ->
            val out = merge(c.target, c.source, c.policy)
            val present = flatten(out.blocks).mapNotNull { it.uuid }.toSet()
            val remapped = UuidRemap.compute(c.policy.sourceGraphId, c.source.blocks).values.toSet()
            flatten(out.blocks).forEach { b ->
                refRegex.findAll(b.content).map { it.groupValues[1] }.filter { it in remapped }.forEach {
                    assertTrue(it in present, "dangling ref $it in '${b.content}'")
                }
            }
        }
    }
}
