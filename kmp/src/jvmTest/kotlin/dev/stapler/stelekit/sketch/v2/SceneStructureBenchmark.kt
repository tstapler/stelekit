// jvmTest-reason: desktop-only
package dev.stapler.stelekit.sketch.v2

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spike 0.1.6: persistent-map vs copy snapshots, and flat scan vs grid vs R-tree, at 5,000 elements.
 * Prototype structures live here (throwaway); the winner is re-implemented in Stories 1.2.1 / 1.3.3.
 * Results: ADR-002 "Spike 0.1.6 decision". Numbers are JVM-only, one host, one run; see [report].
 */

/** Minimal stand-in for a v2 `RectElement` (shape fields only; the real one carries more). */
data class BenchRect(
    val id: String,
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val angle: Double,
    val strokeColor: Int,
    val fillColor: Int,
    val seed: Int,
    val version: Int,
) {
    fun moved(dx: Double) = copy(x = x + dx, version = version + 1)
}

/** Path-copying 32-way hash trie: the smallest persistent map that gives structural sharing. */
class PTrie<V> private constructor(private val root: Array<Any?>, val size: Int) {
    private class Leaf<V>(val key: String, val value: V, val next: Leaf<V>?)

    fun put(key: String, value: V): PTrie<V> {
        val added = BooleanArray(1)
        val newRoot = putRec(root, key, value, key.hashCode(), 0, added)
        return PTrie(newRoot, if (added[0]) size + 1 else size)
    }

    @Suppress("UNCHECKED_CAST")
    private fun putRec(node: Array<Any?>, key: String, value: V, hash: Int, shift: Int, added: BooleanArray): Array<Any?> {
        val copy = node.copyOf()
        val idx = (hash ushr shift) and 31
        val slot = node[idx]
        copy[idx] = when {
            slot == null -> { added[0] = true; Leaf(key, value, null) }
            slot is Array<*> -> putRec(slot as Array<Any?>, key, value, hash, shift + 5, added)
            else -> {
                val leaf = slot as Leaf<V>
                when {
                    leaf.key == key -> Leaf(key, value, leaf.next)
                    shift + 5 >= 32 -> chainPut(leaf, key, value, added)
                    else -> { // split: push existing leaf one level down, then insert
                        val child = arrayOfNulls<Any?>(32)
                        child[(leaf.key.hashCode() ushr (shift + 5)) and 31] = Leaf(leaf.key, leaf.value, leaf.next)
                        putRec(child, key, value, hash, shift + 5, added)
                    }
                }
            }
        }
        return copy
    }

    private fun chainPut(head: Leaf<V>, key: String, value: V, added: BooleanArray): Leaf<V> {
        var l: Leaf<V>? = head
        while (l != null) { if (l.key == key) return Leaf(key, value, head) ; l = l.next }
        added[0] = true
        return Leaf(key, value, head)
    }

    @Suppress("UNCHECKED_CAST")
    operator fun get(key: String): V? {
        var node: Array<Any?> = root
        var shift = 0
        val hash = key.hashCode()
        while (true) {
            val slot = node[(hash ushr shift) and 31] ?: return null
            if (slot is Array<*>) { node = slot as Array<Any?>; shift += 5; continue }
            var l: Leaf<V>? = slot as Leaf<V>
            while (l != null) { if (l.key == key) return l.value; l = l.next }
            return null
        }
    }

    companion object {
        fun <V> empty() = PTrie<V>(arrayOfNulls(32), 0)
    }
}

/** Static AABB grid index. */
class GridIndex(private val cell: Double, items: List<BenchRect>) {
    private val cells = HashMap<Long, MutableList<BenchRect>>()
    init { items.forEach { insert(it) } }
    private fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
    fun insert(r: BenchRect) = forCells(r.x, r.y, r.x + r.w, r.y + r.h) { cells.getOrPut(it) { ArrayList() }.add(r) }
    fun remove(r: BenchRect) = forCells(r.x, r.y, r.x + r.w, r.y + r.h) { cells[it]?.remove(r) }
    private inline fun forCells(x0: Double, y0: Double, x1: Double, y1: Double, f: (Long) -> Unit) {
        val cx0 = Math.floorDiv(x0.toLong(), cell.toLong()).toInt(); val cx1 = Math.floorDiv(x1.toLong(), cell.toLong()).toInt()
        val cy0 = Math.floorDiv(y0.toLong(), cell.toLong()).toInt(); val cy1 = Math.floorDiv(y1.toLong(), cell.toLong()).toInt()
        for (cx in cx0..cx1) for (cy in cy0..cy1) f(key(cx, cy))
    }
    /** Returns candidate count (AABB tests performed) and hits. */
    fun query(x0: Double, y0: Double, x1: Double, y1: Double, hits: MutableSet<String>): Int {
        var candidates = 0
        forCells(x0, y0, x1, y1) { k ->
            cells[k]?.forEach { r -> candidates++; if (overlaps(r, x0, y0, x1, y1)) hits.add(r.id) }
        }
        return candidates
    }
}

/** STR bulk-loaded static R-tree (node capacity 16). Update = rebuild (measured as the upper bound). */
class StrRTree(items: List<BenchRect>, private val cap: Int = 16) {
    private class Node(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val kids: List<Node>?, val item: BenchRect?)
    private val root: Node?
    init { root = build(items.map { leaf(it) }) }
    private fun leaf(r: BenchRect) = Node(r.x, r.y, r.x + r.w, r.y + r.h, null, r)
    private fun wrap(ns: List<Node>) = Node(ns.minOf { it.x0 }, ns.minOf { it.y0 }, ns.maxOf { it.x1 }, ns.maxOf { it.y1 }, ns, null)
    private fun build(level: List<Node>): Node? {
        if (level.isEmpty()) return null
        var cur = level
        while (cur.size > 1) {
            val slices = Math.ceil(Math.sqrt(Math.ceil(cur.size / cap.toDouble()))).toInt()
            val sliceSize = slices * cap
            val sorted = cur.sortedBy { (it.x0 + it.x1) / 2 }
            val next = ArrayList<Node>()
            sorted.chunked(sliceSize).forEach { s -> s.sortedBy { (it.y0 + it.y1) / 2 }.chunked(cap).forEach { next.add(wrap(it)) } }
            cur = next
        }
        return cur[0]
    }
    /** Returns node + leaf AABB tests performed (the work an index does) and fills hits. */
    fun query(x0: Double, y0: Double, x1: Double, y1: Double, hits: MutableSet<String>): Int {
        var tests = 0
        fun rec(n: Node) {
            tests++
            if (n.x1 < x0 || n.x0 > x1 || n.y1 < y0 || n.y0 > y1) return
            if (n.item != null) { hits.add(n.item.id); return }
            n.kids!!.forEach { rec(it) }
        }
        root?.let { rec(it) }
        return tests
    }
}

fun overlaps(r: BenchRect, x0: Double, y0: Double, x1: Double, y1: Double) =
    !(r.x + r.w < x0 || r.x > x1 || r.y + r.h < y0 || r.y > y1)

object SceneStructureBench {
    const val N = 5_000
    const val STEPS = 100

    fun scene(spread: Double, seed: Int = 42): List<BenchRect> {
        val rnd = Random(seed)
        return List(N) {
            BenchRect("e$it", rnd.nextDouble(spread), rnd.nextDouble(spread), 16 + rnd.nextDouble(184.0), 16 + rnd.nextDouble(184.0),
                0.0, rnd.nextInt(), rnd.nextInt(), rnd.nextInt(), 1)
        }
    }

    private fun usedHeap(): Long {
        repeat(5) { System.gc() }
        val rt = Runtime.getRuntime(); return rt.totalMemory() - rt.freeMemory()
    }

    data class SnapshotResult(val name: String, val medianUs: Double, val p95Us: Double, val retainedKbPer100: Double)

    /** Moves one element and snapshots, [STEPS] times, retaining every snapshot. */
    fun <S> snapshotRun(name: String, build: () -> S, step: (S, BenchRect, Int) -> S, id: (Int) -> String, base: List<BenchRect>): SnapshotResult {
        // warm-up
        run { var s = build(); repeat(300) { s = step(s, base[it % N], it) } }
        val before = usedHeap()
        var s = build()
        val kept = ArrayList<S>(STEPS)
        val times = LongArray(STEPS)
        val baseHeap = usedHeap()
        for (i in 0 until STEPS) {
            val t0 = System.nanoTime()
            s = step(s, base[(i * 37) % N], i)
            times[i] = System.nanoTime() - t0
            kept.add(s)
        }
        val after = usedHeap()
        check(kept.size == STEPS)
        times.sort()
        // retained = growth caused by the 100 retained snapshots on top of the initial structure
        return SnapshotResult(name, times[STEPS / 2] / 1000.0, times[(STEPS * 95) / 100] / 1000.0, (after - baseHeap).coerceAtLeast(0) / 1024.0 + 0.0 * before)
    }

    fun snapshots(): List<SnapshotResult> {
        val base = scene(16_000.0)
        val out = ArrayList<SnapshotResult>()
        out += snapshotRun("HashMap copy (id->element)", { base.associateByTo(HashMap(N * 2)) { it.id } },
            { m, r, i -> HashMap(m).also { it[r.id] = m[r.id]!!.moved(i + 1.0) } }, { "" }, base)
        out += snapshotRun("LinkedHashMap copy (id->element, keeps order)", { base.associateByTo(LinkedHashMap(N * 2)) { it.id } },
            { m, r, i -> LinkedHashMap(m).also { it[r.id] = m[r.id]!!.moved(i + 1.0) } }, { "" }, base)
        out += snapshotRun("List copy (ArrayList.toList + set)", { base.toList() },
            { l, r, i -> val idx = r.id.substring(1).toInt(); l.toMutableList().also { it[idx] = l[idx].moved(i + 1.0) } }, { "" }, base)
        out += snapshotRun("Persistent 32-way hash trie (structural sharing)", { base.fold(PTrie.empty<BenchRect>()) { t, r -> t.put(r.id, r) } },
            { t, r, i -> t.put(r.id, t[r.id]!!.moved(i + 1.0)) }, { "" }, base)
        return out
    }

    data class QueryResult(val layout: String, val kind: String, val structure: String, val avgCandidates: Double, val avgUs: Double, val extra: String)

    fun queries(): List<QueryResult> {
        val out = ArrayList<QueryResult>()
        for ((layout, spread) in listOf("sparse (16000 u square)" to 16_000.0, "dense (2500 u square)" to 2_500.0)) {
            val items = scene(spread)
            val rnd = Random(7)
            // point/hit query: tolerance 8 px at zoom 1 -> 16-unit square window; snap query: 24 px -> 48 unit window
            for ((kind, half) in listOf("hit (8u radius)" to 8.0, "snap (24u radius)" to 24.0)) {
                val pts = List(2_000) { rnd.nextDouble(spread) to rnd.nextDouble(spread) }
                // flat
                fun flat(): Pair<Double, Int> { var hitsTotal = 0; for ((px, py) in pts) { items.forEach { if (overlaps(it, px - half, py - half, px + half, py + half)) hitsTotal++ } }; return N.toDouble() to hitsTotal }
                repeat(3) { flat() }
                var t0 = System.nanoTime(); val (flatC, flatHits) = flat(); val flatUs = (System.nanoTime() - t0) / 1000.0 / pts.size
                out += QueryResult(layout, kind, "flat scan", flatC, flatUs, "hits/query=${"%.2f".format(flatHits / pts.size.toDouble())}")
                // grid
                for (cell in listOf(128.0, 256.0, 512.0)) {
                    val g = GridIndex(cell, items)
                    fun run(): Pair<Long, Int> { var c = 0L; var h = 0; for ((px, py) in pts) { val s = HashSet<String>(); c += g.query(px - half, py - half, px + half, py + half, s); h += s.size }; return c to h }
                    repeat(3) { run() }
                    t0 = System.nanoTime(); val (c, h) = run(); val us = (System.nanoTime() - t0) / 1000.0 / pts.size
                    check(h == flatHits) { "grid disagrees with flat scan: $h vs $flatHits" }
                    out += QueryResult(layout, kind, "grid cell=${cell.toInt()}", c / pts.size.toDouble(), us, "hits match flat scan")
                }
                // r-tree
                val rt = StrRTree(items)
                fun runR(): Pair<Long, Int> { var c = 0L; var h = 0; for ((px, py) in pts) { val s = HashSet<String>(); c += rt.query(px - half, py - half, px + half, py + half, s); h += s.size }; return c to h }
                repeat(3) { runR() }
                t0 = System.nanoTime(); val (c, h) = runR(); val us = (System.nanoTime() - t0) / 1000.0 / pts.size
                check(h == flatHits) { "rtree disagrees with flat scan: $h vs $flatHits" }
                out += QueryResult(layout, kind, "STR R-tree cap=16", c / pts.size.toDouble(), us, "candidates = node+leaf AABB tests")
            }
        }
        return out
    }

    data class UpdateResult(val structure: String, val medianUs: Double)

    /** Cost to keep the index current after ONE element moves. */
    fun updates(): List<UpdateResult> {
        val items = scene(16_000.0)
        val out = ArrayList<UpdateResult>()
        val g = GridIndex(256.0, items)
        val cur = items.toTypedArray()
        val times = LongArray(2_000)
        repeat(500) { i -> val r = cur[i % N]; val m = r.moved(3.0); g.remove(r); g.insert(m); cur[i % N] = m }
        for (i in times.indices) { val r = cur[i % N]; val m = r.moved(3.0); val t0 = System.nanoTime(); g.remove(r); g.insert(m); times[i] = System.nanoTime() - t0; cur[i % N] = m }
        times.sort(); out += UpdateResult("grid cell=256 remove+insert", times[times.size / 2] / 1000.0)
        val rt = LongArray(30)
        repeat(10) { StrRTree(items) }
        for (i in rt.indices) { val t0 = System.nanoTime(); StrRTree(items); rt[i] = System.nanoTime() - t0 }
        rt.sort(); out += UpdateResult("STR R-tree full rebuild (no incremental update prototyped)", rt[rt.size / 2] / 1000.0)
        return out
    }

    fun report(): String = buildString {
        appendLine("Spike 0.1.6 scene structure benchmark: N=$N, steps=$STEPS, JVM ${System.getProperty("java.version")}, ${Runtime.getRuntime().availableProcessors()} cpus")
        appendLine("--- snapshot after moving one element (median/p95 us per step, heap retained by 100 snapshots in KB)")
        snapshots().forEach { appendLine("%-55s median=%9.1f us  p95=%9.1f us  retained(100 snapshots)=%10.0f KB".format(it.name, it.medianUs, it.p95Us, it.retainedKbPer100)) }
        appendLine("--- spatial query (avg over 2000 random points; candidates = AABB tests per query)")
        queries().forEach { appendLine("%-26s %-18s %-20s candidates=%9.1f  time=%8.2f us  %s".format(it.layout, it.kind, it.structure, it.avgCandidates, it.avgUs, it.extra)) }
        appendLine("--- index maintenance after ONE element moves")
        updates().forEach { appendLine("%-60s median=%10.1f us".format(it.structure, it.medianUs)) }
    }
}

class SceneStructureBenchmark {
    @Test
    fun trieAgreesWithMap() {
        val base = SceneStructureBench.scene(16_000.0)
        val trie = base.fold(PTrie.empty<BenchRect>()) { t, r -> t.put(r.id, r) }
        assertEquals(SceneStructureBench.N, trie.size)
        base.forEach { assertEquals(it, trie[it.id]) }
        val moved = trie.put("e5", base[5].moved(9.0))
        assertEquals(base[5], trie["e5"]) // persistence: old snapshot unchanged
        assertEquals(9.0 + base[5].x, moved["e5"]!!.x)
    }

    @Test
    fun runBenchmarkAndWriteReport() {
        val text = SceneStructureBench.report()
        println(text)
        File("build/reports").also { it.mkdirs() }.resolve("scene-structure-benchmark.txt").writeText(text)
        assertTrue(text.isNotBlank())
    }
}
