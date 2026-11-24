// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.index

import dev.stapler.stelekit.sketch.v2.scene.WallGeometry
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.BoxElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** An axis-aligned rectangle in scene units. */
data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    fun intersects(o: Bounds): Boolean = minX <= o.maxX && maxX >= o.minX && minY <= o.maxY && maxY >= o.minY

    fun inflated(d: Double): Bounds = Bounds(minX - d, minY - d, maxX + d, maxY + d)

    companion object {
        fun around(x: Double, y: Double, d: Double): Bounds = Bounds(x - d, y - d, x + d, y + d)

        fun ofPoints(points: List<ScenePoint>): Bounds {
            if (points.isEmpty()) return Bounds(0.0, 0.0, 0.0, 0.0)
            return Bounds(points.minOf { it.x.value }, points.minOf { it.y.value }, points.maxOf { it.x.value }, points.maxOf { it.y.value })
        }
    }
}

/** The one definition of where an element is: its geometry's box, rotation applied, grown by half its stroke width. */
object ElementBounds {
    fun of(e: SceneElement): Bounds = geometry(e).inflated(e.base.style.strokeWidth / HALF)

    private const val HALF = 2.0

    /** The element's box with rotation applied and no stroke allowance: what transforms scale and align. */
    fun geometry(e: SceneElement): Bounds = when (e) {
        is BoxElement -> rotatedBox(e.x.value, e.y.value, e.width.value, e.height.value, e.angle)
        is EllipseElement -> {
            val c = cos(e.angle)
            val s = sin(e.angle)
            val hx = sqrt(square(e.rx.value * c) + square(e.ry.value * s))
            val hy = sqrt(square(e.rx.value * s) + square(e.ry.value * c))
            Bounds(e.cx.value - hx, e.cy.value - hy, e.cx.value + hx, e.cy.value + hy)
        }
        is LineElement -> Bounds.ofPoints(e.points)
        is ArrowElement -> Bounds.ofPoints(e.points)
        is FreedrawElement -> Bounds.ofPoints(e.points)
        is PointElement -> Bounds(e.x.value, e.y.value, e.x.value, e.y.value)
        is ArcElement -> Bounds.around(e.cx.value, e.cy.value, e.radius.value)
        is WallElement -> WallGeometry.bounds(e).let { Bounds(it[0], it[1], it[2], it[3]) }
    }

    private fun square(v: Double) = v * v

    private fun rotatedBox(x: Double, y: Double, w: Double, h: Double, angle: Double): Bounds {
        if (angle == 0.0) return Bounds(x, y, x + w, y + h)
        val cx = x + w / HALF
        val cy = y + h / HALF
        val c = cos(angle)
        val s = sin(angle)
        val xs = DoubleArray(CORNERS)
        val ys = DoubleArray(CORNERS)
        listOf(x to y, x + w to y, x + w to y + h, x to y + h).forEachIndexed { i, (px, py) ->
            xs[i] = cx + (px - cx) * c - (py - cy) * s
            ys[i] = cy + (px - cx) * s + (py - cy) * c
        }
        return Bounds(xs.min(), ys.min(), xs.max(), ys.max())
    }

    private const val CORNERS = 4
}

/**
 * Uniform grid over element bounds (Spike 0.1.6: 256-unit cells beat the flat scan 20 to 50x in candidates and an
 * R-tree on dense scenes). Mutable and incremental: [update] re-files only the ids a command changed. An element that
 * would span more than [OVERSIZE_CELLS] cells (a huge frame, a far-flung line) is kept in a short side list and
 * checked on every query instead of being filed in thousands of cells. A query returns exactly the ids whose
 * [ElementBounds] intersect the rectangle; the grid only decides which ones are looked at.
 */
class SpatialIndex(private val cellSize: Double = CELL_SIZE) {
    private val cells = HashMap<Long, MutableSet<ElementId>>()
    private val placed = HashMap<ElementId, Placement>()
    private val oversized = LinkedHashSet<ElementId>()

    private class Placement(val bounds: Bounds, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val filed: Boolean)

    val size: Int get() = placed.size

    /** Files every element of [scene] afresh. */
    fun rebuild(scene: SketchSceneV2) {
        cells.clear()
        placed.clear()
        oversized.clear()
        for (e in scene.elementsInOrder) insert(e.id, ElementBounds.of(e))
    }

    /** Re-files [changedIds] (added, moved, resized or removed) against [scene]. */
    fun update(scene: SketchSceneV2, changedIds: Collection<ElementId>) {
        for (id in changedIds) {
            remove(id)
            scene[id]?.let { insert(id, ElementBounds.of(it)) }
        }
    }

    /** Ids whose bounds intersect [rect]. */
    fun query(rect: Bounds): Set<ElementId> {
        val x0 = cell(rect.minX)
        val x1 = cell(rect.maxX)
        val y0 = cell(rect.minY)
        val y1 = cell(rect.maxY)
        val span = (x1.toLong() - x0 + 1) * (y1.toLong() - y0 + 1)
        val candidates: Collection<ElementId> = if (span > placed.size) {
            placed.keys
        } else {
            val seen = LinkedHashSet<ElementId>()
            for (cx in x0..x1) for (cy in y0..y1) cells[key(cx, cy)]?.let { seen.addAll(it) }
            seen.addAll(oversized)
            seen
        }
        return candidates.filterTo(LinkedHashSet()) { id -> placed.getValue(id).bounds.intersects(rect) }
    }

    private fun insert(id: ElementId, bounds: Bounds) {
        val x0 = cell(bounds.minX)
        val x1 = cell(bounds.maxX)
        val y0 = cell(bounds.minY)
        val y1 = cell(bounds.maxY)
        val span = (x1.toLong() - x0 + 1) * (y1.toLong() - y0 + 1)
        val filed = span <= OVERSIZE_CELLS
        placed[id] = Placement(bounds, x0, y0, x1, y1, filed)
        if (!filed) { oversized += id; return }
        for (cx in x0..x1) for (cy in y0..y1) cells.getOrPut(key(cx, cy)) { LinkedHashSet() } += id
    }

    private fun remove(id: ElementId) {
        val p = placed.remove(id) ?: return
        if (!p.filed) { oversized -= id; return }
        for (cx in p.x0..p.x1) for (cy in p.y0..p.y1) {
            val k = key(cx, cy)
            val bucket = cells[k] ?: continue
            bucket -= id
            if (bucket.isEmpty()) cells.remove(k)
        }
    }

    private fun cell(v: Double): Int = floor(v / cellSize).coerceIn(-CELL_CLAMP, CELL_CLAMP).toInt()

    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl KEY_SHIFT) xor (cy.toLong() and KEY_MASK)

    private companion object {
        const val CELL_SIZE = 256.0
        const val OVERSIZE_CELLS = 1024L
        const val CELL_CLAMP = 1.0e9
        const val KEY_SHIFT = 32
        const val KEY_MASK = 0xFFFFFFFFL
    }
}
