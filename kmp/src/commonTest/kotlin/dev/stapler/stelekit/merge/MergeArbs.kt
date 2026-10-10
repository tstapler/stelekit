package dev.stapler.stelekit.merge

import dev.stapler.stelekit.model.GraphId
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import kotlin.random.Random

/** One generated scenario; every derived page comes from the same seed so axes stay correlated. */
data class MergeCase(
    val target: MergePage,
    val source: MergePage,
    /** merge(target, source) with a random subset of its `src-id` blocks edited afterwards. */
    val editedTarget: MergePage,
    /** Edited blocks that have no edited ancestor: each yields exactly one conflict sibling. */
    val editedRoots: Int,
    /** [source] with a random subset of contents changed. */
    val drifted: MergePage,
    val policy: MergePolicy,
)

object MergeArbs {
    private val graph = GraphId("g")
    private val uuidPool = (1..12).map { "u$it" }

    // Collision-biased: duplicates, short content, whitespace variants, ref/embed syntax.
    private val contentPool = listOf(
        "A", "B", "TODO", "a", "A ", " A", "x  y", "x y", "dup", "dup",
        "see ((u1))", "see ((u2))", "((u3))", "{{embed ((u1))}}", "((OUT))",
    )
    private val propPool = listOf(
        mapOf("tags" to "a"), mapOf("tags" to "b"), mapOf("tags" to "a, b"),
        mapOf("status" to "open"), mapOf("status" to "done"), emptyMap(),
    )

    private val policies = listOf(
        MergePolicy(graph, "Work"),
        MergePolicy(graph, "Work", blockKey = ::exactTrimmedBlockContent),
    )

    private const val EDIT_SUFFIX = " ~edit"
    private const val DRIFT_SUFFIX = " ~drift"

    private fun genBlocks(r: Random, depth: Int, budget: IntArray, uuids: ArrayDeque<String>, unlabeled: Double): List<MergeBlock> {
        val count = if (depth >= 4) 0 else r.nextInt(0, if (depth == 0) 4 else 3)
        val out = ArrayList<MergeBlock>()
        repeat(count) {
            if (budget[0] <= 0) return@repeat
            budget[0]--
            val uuid = if (r.nextDouble() < unlabeled || uuids.isEmpty()) null else uuids.removeFirst()
            val content = contentPool[r.nextInt(contentPool.size)]
            out += MergeBlock(uuid, content, emptyMap(), genBlocks(r, depth + 1, budget, uuids, unlabeled))
        }
        return out
    }

    private fun genPage(r: Random, unlabeled: Double): MergePage {
        val uuids = ArrayDeque(uuidPool.shuffled(r))
        return MergePage("P", properties = propPool[r.nextInt(propPool.size)], blocks = genBlocks(r, 0, intArrayOf(10), uuids, unlabeled))
    }

    /** Appends [suffix] to blocks chosen by [pick]; returns the page and how many edited blocks have no edited ancestor. */
    private fun edit(page: MergePage, r: Random, suffix: String, pick: (MergeBlock) -> Boolean): Pair<MergePage, Int> {
        var roots = 0
        fun go(bs: List<MergeBlock>, ancestorEdited: Boolean): List<MergeBlock> = bs.map { b ->
            val hit = pick(b) && r.nextDouble() < 0.4
            if (hit && !ancestorEdited) roots++
            b.copy(content = if (hit) b.content + suffix else b.content, children = go(b.children, ancestorEdited || hit))
        }
        return page.copy(blocks = go(page.blocks, false)) to roots
    }

    fun merge(t: MergePage?, s: MergePage, p: MergePolicy): MergePage = when (val o = mergePage(t, s, p)) {
        is MergeOutcome.New -> o.page
        is MergeOutcome.Merged -> o.page
        MergeOutcome.Unchanged -> t ?: s
    }

    fun cases(unlabeledTargetFraction: Double): Arb<MergeCase> = arbitrary { rs ->
        val r = rs.random
        val policy = policies[r.nextInt(policies.size)]
        val t0 = genPage(r, unlabeledTargetFraction)
        val s = genPage(r, 0.0)
        val t = merge(t0, s, policy)
        val (edited, roots) = edit(t, r, EDIT_SUFFIX) {
            MergePropertyKeys.SRC_ID in it.properties && it.properties[MergePropertyKeys.CONFLICT] != "true"
        }
        val (drifted, _) = edit(s, r, DRIFT_SUFFIX) { true }
        MergeCase(t0, s, edited, roots, drifted, policy)
    }

    /** Disjoint uuids and pairwise-distinct contents, so no matching of any kind can occur. */
    fun disjointPairs(): Arb<Triple<MergePage, MergePage, MergePolicy>> = arbitrary { rs ->
        val r = rs.random
        var n = 0
        fun page(prefix: String): MergePage {
            fun go(depth: Int): List<MergeBlock> = List(if (depth >= 3) 0 else r.nextInt(0, 3)) {
                n++
                MergeBlock(if (r.nextBoolean()) "$prefix$n" else null, "$prefix-content-$n", emptyMap(), go(depth + 1))
            }
            return MergePage("P", blocks = go(0))
        }
        Triple(page("t"), page("s"), policies[r.nextInt(policies.size)])
    }
}
