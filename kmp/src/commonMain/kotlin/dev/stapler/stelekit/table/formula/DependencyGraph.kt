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

/**
 * Formula dependencies with range precedents stored as rectangles (ADR-002). Single-cell
 * precedents are hash-indexed; multi-cell rectangles are scanned per lookup, which stays cheap
 * because only a handful of formulas (totals) use real ranges. Patch with [setFormula]/[removeFormula]
 * on a cell edit; rebuild a new graph on structural change.
 */
class DependencyGraph {
    private val precedents = HashMap<CellAddress, List<CellRange>>()
    private val pointDependents = HashMap<CellAddress, MutableSet<CellAddress>>()
    private val rectOwners = HashMap<CellAddress, List<CellRange>>()

    val formulaCells: Set<CellAddress> get() = precedents.keys

    fun isFormula(cell: CellAddress): Boolean = precedents.containsKey(cell)

    fun setFormula(cell: CellAddress, reads: List<CellRange>) {
        removeFormula(cell)
        precedents[cell] = reads
        val rects = ArrayList<CellRange>()
        for (r in reads) {
            if (r.rows == 1 && r.cols == 1) pointDependents.getOrPut(r.topLeft) { HashSet() }.add(cell) else rects.add(r)
        }
        if (rects.isNotEmpty()) rectOwners[cell] = rects
    }

    fun removeFormula(cell: CellAddress) {
        val old = precedents.remove(cell) ?: return
        rectOwners.remove(cell)
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
        for ((owner, rects) in rectOwners) if (rects.any { cell in it }) out.add(owner)
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
        for (n in nodes) indegree.putIfAbsent(n, 0)
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
