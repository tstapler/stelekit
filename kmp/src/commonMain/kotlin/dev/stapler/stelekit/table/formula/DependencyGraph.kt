package dev.stapler.stelekit.table.formula

import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.CellRange

/** Every cell or range a formula reads, as rectangles (unclamped; a lone cell is a 1x1 rectangle). Iterative walk. */
fun Expr.precedentRanges(): List<CellRange> {
    val out = ArrayList<CellRange>()
    val stack = ArrayList<Expr>()
    stack.add(this)
    while (stack.isNotEmpty()) {
        when (val e = stack.removeAt(stack.size - 1)) {
            is Expr.Ref -> out.add(CellRange.of(CellAddress(e.row, e.col), CellAddress(e.row, e.col)))
            is Expr.Range -> out.add(
                CellRange.of(CellAddress(e.from.row, e.from.col), CellAddress(e.to.row, e.to.col)),
            )
            is Expr.Bin -> {
                stack.add(e.right)
                stack.add(e.left)
            }
            is Expr.Neg -> stack.add(e.operand)
            is Expr.Pct -> stack.add(e.operand)
            is Expr.Call -> for (i in e.args.indices.reversed()) stack.add(e.args[i])
            is Expr.Num, is Expr.Str, is Expr.RefError -> Unit
        }
    }
    return out
}

/**
 * Cells to recompute after an edit, in order. [cyclic] members must be assigned `#CYCLE!` before
 * [order] is evaluated; [order] holds every other dirty formula cell, precedents first.
 */
data class EvalPlan(val order: List<CellAddress>, val cyclic: Set<CellAddress>) {
    val size: Int get() = order.size + cyclic.size
}

/** Work done maintaining column indexes (rebuilds and removals); tests bound it. */
internal var indexOps = 0L

/** One rectangle's rows within a column, owned by the formula cell that reads it; [dead] spans await the next rebuild. */
private class RowSpan(val r1: Int, val r2: Int, val owner: CellAddress) {
    var dead = false
}

/**
 * Stabbing index over the [RowSpan]s of one column: an indexed part (sorted by start row plus a max-end segment tree)
 * and a small unindexed [extra] list that new spans join. Removal only tombstones; the indexed part is rebuilt when the
 * extras or the tombstones outgrow a fraction of it, so n updates cost O(n log n) in total, not O(n) each.
 */
private class ColumnIndex {
    private var main: List<RowSpan> = emptyList()
    private val extra = ArrayList<RowSpan>()
    private var dead = 0
    private var starts = IntArray(0)
    private var tree = IntArray(0)
    private var size = 0
    private var stale = false

    fun add(span: RowSpan) {
        extra.add(span)
        if (extra.size > maxOf(MIN_SLACK, main.size / 16)) stale = true
    }

    fun kill(span: RowSpan) {
        if (span.dead) return
        span.dead = true
        dead++
        indexOps++
        if (dead > maxOf(MIN_SLACK, (main.size + extra.size) / 2)) stale = true
    }

    private fun rebuild() {
        val live = ArrayList<RowSpan>(main.size + extra.size)
        for (sp in main) if (!sp.dead) live.add(sp)
        for (sp in extra) if (!sp.dead) live.add(sp)
        indexOps += main.size + extra.size
        main = live.sortedBy { it.r1 }
        extra.clear()
        dead = 0
        starts = IntArray(main.size) { main[it].r1 }
        size = 1
        while (size < main.size) size *= 2
        tree = IntArray(2 * size) { -1 }
        for (i in main.indices) tree[size + i] = main[i].r2
        for (n in size - 1 downTo 1) tree[n] = maxOf(tree[2 * n], tree[2 * n + 1])
        stale = false
    }

    /** Adds the owner of every live span containing [row] to [out]; returns the number of nodes and spans examined. */
    fun stab(row: Int, out: MutableSet<CellAddress>): Long {
        if (stale) rebuild()
        var work = 0L
        for (sp in extra) {
            work++
            if (!sp.dead && sp.r1 <= row && row <= sp.r2) out.add(sp.owner)
        }
        if (main.isEmpty()) return work
        var limit = 0 // spans [0, limit) start at or before row
        var hi = main.size
        while (limit < hi) {
            val mid = (limit + hi) ushr 1
            if (starts[mid] <= row) limit = mid + 1 else hi = mid
        }
        return work + collect(1, 0, size, limit, row, out)
    }

    private fun collect(node: Int, lo: Int, hi: Int, limit: Int, row: Int, out: MutableSet<CellAddress>): Long {
        if (lo >= limit || tree[node] < row) return 1
        if (hi - lo == 1) {
            if (!main[lo].dead) out.add(main[lo].owner)
            return 1
        }
        val mid = (lo + hi) ushr 1
        return 1 + collect(2 * node, lo, mid, limit, row, out) + collect(2 * node + 1, mid, hi, limit, row, out)
    }

    private companion object {
        const val MIN_SLACK = 32
    }
}

/**
 * Formula dependencies with range precedents stored as rectangles (ADR-002). Single-cell precedents are
 * hash-indexed; multi-cell rectangles are indexed per column ([ColumnIndex]) so [dependentsOf] costs O(log n + hits)
 * rather than a scan of every range owner. Patch with [setFormula]/[removeFormula] on a cell edit; rebuild a new graph
 * on structural change.
 */
class DependencyGraph {
    private val precedents = HashMap<CellAddress, List<CellRange>>()
    private val pointDependents = HashMap<CellAddress, MutableSet<CellAddress>>()
    private val rectOwners = HashMap<CellAddress, List<CellRange>>()
    private val columns = HashMap<Int, ColumnIndex>()
    private val ownerSpans = HashMap<CellAddress, List<Pair<Int, RowSpan>>>()

    /** Index nodes examined by [dependentsOf] since construction; tests bound it to prove lookups are not O(owners). */
    internal var rectChecks = 0L

    val formulaCells: Set<CellAddress> get() = precedents.keys

    fun isFormula(cell: CellAddress): Boolean = precedents.containsKey(cell)

    fun setFormula(cell: CellAddress, reads: List<CellRange>) {
        removeFormula(cell)
        precedents[cell] = reads
        val rects = ArrayList<CellRange>()
        for (r in reads) {
            if (r.rows == 1 && r.cols == 1) pointDependents.getOrPut(r.topLeft) { HashSet() }.add(cell) else rects.add(r)
        }
        if (rects.isNotEmpty()) {
            rectOwners[cell] = rects
            val spans = ArrayList<Pair<Int, RowSpan>>()
            for (r in rects) for (c in r.topLeft.col..r.bottomRight.col) {
                val span = RowSpan(r.topLeft.row, r.bottomRight.row, cell)
                columns.getOrPut(c) { ColumnIndex() }.add(span)
                spans.add(c to span)
            }
            ownerSpans[cell] = spans
        }
    }

    fun removeFormula(cell: CellAddress) {
        val old = precedents.remove(cell) ?: return
        rectOwners.remove(cell)
        ownerSpans.remove(cell)?.forEach { (c, span) -> columns[c]?.kill(span) }
        for (r in old) {
            if (r.rows == 1 && r.cols == 1) {
                pointDependents[r.topLeft]?.let {
                    it.remove(cell)
                    if (it.isEmpty()) pointDependents.remove(r.topLeft)
                }
            }
        }
    }

    /** Formula cells that read [cell] directly (a cell inside its own range lists itself). */
    fun dependentsOf(cell: CellAddress): Set<CellAddress> {
        val points = pointDependents[cell]
        if (rectOwners.isEmpty()) return points ?: emptySet()
        val out = HashSet<CellAddress>()
        if (points != null) out.addAll(points)
        columns[cell.col]?.let { rectChecks += it.stab(cell.row, out) }
        return out
    }

    /** Plan for every formula cell (a full pass). */
    fun planAll(): EvalPlan = planOver(formulaCells.toHashSet())

    /**
     * Plan after [changed] cells changed value or content. Dirty = changed formula cells plus all
     * transitive dependents; a pass never touches more than that.
     */
    fun plan(changed: Collection<CellAddress>): EvalPlan {
        val dirty = HashSet<CellAddress>()
        val queue = ArrayList<CellAddress>()
        for (c in changed) {
            if (isFormula(c)) dirty.add(c)
            queue.add(c)
        }
        var i = 0
        while (i < queue.size) {
            for (d in dependentsOf(queue[i++])) if (dirty.add(d)) queue.add(d)
        }
        return planOver(dirty)
    }

    /** Kahn over the dirty subgraph; what Kahn cannot drain is split into true cycle members (Tarjan) and their downstream. */
    private fun planOver(dirty: Set<CellAddress>): EvalPlan {
        val order = ArrayList<CellAddress>(dirty.size)
        val remaining = drain(dirty, order)
        if (remaining.isEmpty()) return EvalPlan(order, emptySet())
        val cyclic = cycleMembers(remaining)
        drain(remaining - cyclic, order)
        return EvalPlan(order, cyclic)
    }

    /** Appends [nodes] to [order] in dependency order and returns those that could not be ordered. */
    private fun drain(nodes: Set<CellAddress>, order: MutableList<CellAddress>): Set<CellAddress> {
        val indegree = HashMap<CellAddress, Int>(nodes.size)
        for (n in nodes) if (n !in indegree) indegree[n] = 0
        for (u in nodes) for (v in dependentsOf(u)) if (v in nodes) indegree[v] = indegree.getValue(v) + 1
        val ready = ArrayList<CellAddress>()
        for ((n, d) in indegree) if (d == 0) ready.add(n)
        ready.sortWith(ADDRESS_ORDER)
        var head = 0
        while (head < ready.size) {
            val u = ready[head++]
            order.add(u)
            for (v in dependentsOf(u)) {
                if (v !in nodes) continue
                val left = indegree.getValue(v) - 1
                indegree[v] = left
                if (left == 0) ready.add(v)
            }
        }
        val done = ready.toHashSet()
        return nodes.filterTo(HashSet()) { it !in done }
    }

    /** Nodes of [within] on a cycle: members of a strongly connected component of size > 1, or with a self edge. Iterative Tarjan. */
    private fun cycleMembers(within: Set<CellAddress>): Set<CellAddress> {
        val index = HashMap<CellAddress, Int>()
        val low = HashMap<CellAddress, Int>()
        val onStack = HashSet<CellAddress>()
        val sccStack = ArrayList<CellAddress>()
        val result = HashSet<CellAddress>()
        var counter = 0

        class Frame(val node: CellAddress, val succ: List<CellAddress>, var next: Int = 0)

        fun successors(n: CellAddress) = dependentsOf(n).filter { it in within }.sortedWith(ADDRESS_ORDER)

        for (root in within.sortedWith(ADDRESS_ORDER)) {
            if (root in index) continue
            val frames = ArrayList<Frame>()
            index[root] = counter
            low[root] = counter++
            sccStack.add(root)
            onStack.add(root)
            frames.add(Frame(root, successors(root)))
            while (frames.isNotEmpty()) {
                val f = frames.last()
                if (f.next < f.succ.size) {
                    val w = f.succ[f.next++]
                    if (w !in index) {
                        index[w] = counter
                        low[w] = counter++
                        sccStack.add(w)
                        onStack.add(w)
                        frames.add(Frame(w, successors(w)))
                    } else if (w in onStack) {
                        low[f.node] = minOf(low.getValue(f.node), index.getValue(w))
                    }
                } else {
                    frames.removeAt(frames.size - 1)
                    frames.lastOrNull()?.let { p -> low[p.node] = minOf(low.getValue(p.node), low.getValue(f.node)) }
                    if (low.getValue(f.node) == index.getValue(f.node)) {
                        val scc = ArrayList<CellAddress>()
                        do {
                            val w = sccStack.removeAt(sccStack.size - 1)
                            onStack.remove(w)
                            scc.add(w)
                        } while (w != f.node)
                        if (scc.size > 1 || scc[0] in dependentsOf(scc[0])) result.addAll(scc)
                    }
                }
            }
        }
        return result
    }

    /**
     * One concrete reference loop through [start] among [members], e.g. B2, C2, B2 for
     * `B2==C2+1`, `C2==B2+1` (each cell reads the next). Empty if [start] is not on a cycle in [members].
     */
    fun cyclePath(start: CellAddress, members: Set<CellAddress>): List<CellAddress> {
        val parent = HashMap<CellAddress, CellAddress>()
        val queue = ArrayList<CellAddress>()
        queue.add(start)
        var head = 0
        while (head < queue.size) {
            val u = queue[head++]
            for (v in members.sortedWith(ADDRESS_ORDER)) {
                if (precedents[u]?.any { v in it } != true) continue
                if (v == start) {
                    val path = ArrayList<CellAddress>()
                    var cur: CellAddress? = u
                    while (cur != null) {
                        path.add(cur)
                        cur = if (cur == start) null else parent[cur]
                    }
                    path.reverse()
                    path.add(start)
                    return path
                }
                if (v !in parent && v != start) {
                    parent[v] = u
                    queue.add(v)
                }
            }
        }
        return emptyList()
    }

    companion object {
        private val ADDRESS_ORDER = compareBy<CellAddress>({ it.row }, { it.col })

        /** Builds a graph from parsed formulas. */
        fun build(formulas: Map<CellAddress, Expr>): DependencyGraph =
            DependencyGraph().also { g -> formulas.forEach { (cell, e) -> g.setFormula(cell, e.precedentRanges()) } }
    }
}
