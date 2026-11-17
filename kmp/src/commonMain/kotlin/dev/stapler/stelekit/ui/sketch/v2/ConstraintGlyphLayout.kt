// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.PartPoints
import dev.stapler.stelekit.sketch.v2.constraints.TargetPart
import dev.stapler.stelekit.sketch.v2.hit.PartHitTester
import dev.stapler.stelekit.sketch.v2.index.ElementBounds
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import kotlin.math.abs

/** Where one constraint's glyph wants to sit, in screen dp: [elementIds] are the elements it touches. */
data class GlyphAnchor(val id: ConstraintId, val x: Double, val y: Double, val elementIds: Set<ElementId>)

/** One thing the glyph overlay draws: a lone glyph, a count badge over several, or the count badge an element keeps at low zoom. */
sealed interface GlyphMark {
    val x: Double
    val y: Double
    val ids: List<ConstraintId>

    data class Glyph(val id: ConstraintId, override val x: Double, override val y: Double) : GlyphMark {
        override val ids: List<ConstraintId> get() = listOf(id)
    }

    /** Several glyphs collapsed into one target; activating it opens the list of [ids] (UX-106). */
    data class Badge(override val ids: List<ConstraintId>, override val x: Double, override val y: Double) : GlyphMark

    /** The count an element keeps below 50% zoom, when its glyphs are hidden. */
    data class ElementBadge(val elementId: ElementId, override val ids: List<ConstraintId>, override val x: Double, override val y: Double) : GlyphMark
}

/** [marks] are what to draw; [hidden] counts glyphs of unselected elements that sit under a selected element's fanned glyphs. */
data class GlyphLayout(val marks: List<GlyphMark>, val hidden: Int, val hitDp: Int)

/**
 * Declutters constraint glyphs on a dense sketch (Task 4.2.3c, UX-106). Every mark has a hit area of [GlyphLayout.hitDp]
 * (the touch-target floor: 24 dp mouse-only, 44 dp with a touch or pen pointer) and no two marks' areas overlap:
 * marks that would are merged into one count badge, repeatedly, until none do. A selected element's glyphs are fanned out in
 * a row with [FAN_GAP_DP] between them even where they would have collapsed. Below [HIDE_BELOW_ZOOM] the glyphs of unselected
 * elements give way to one count badge per element unless [showAll] (the "Show constraint glyphs" toggle) is on.
 */
object ConstraintGlyphLayout {
    const val FAN_GAP_DP = 4.0
    const val HIDE_BELOW_ZOOM = 0.5
    const val VISIBLE_DP = 24

    private class Item(var x: Double, var y: Double, val ids: MutableList<ConstraintId>, val single: Boolean, val element: ElementId?)

    fun layout(anchors: List<GlyphAnchor>, zoom: Double, pointer: PointerClass, selected: Set<ElementId>, showAll: Boolean): GlyphLayout {
        val hit = TouchTargetFloor.floorDp(pointer)
        val owner = { a: GlyphAnchor -> a.elementIds.firstOrNull { it in selected } }
        val pinned = fan(anchors.filter { owner(it) != null }, owner, hit)
        val free = anchors.filter { owner(it) == null }
        val items = if (zoom < HIDE_BELOW_ZOOM && !showAll) perElement(free) else free.map { Item(it.x, it.y, mutableListOf(it.id), true, null) }
        val merged = mergeOverlaps(items, hit)
        val (kept, hidden) = merged.partition { m -> pinned.none { overlaps(it.x, it.y, m.x, m.y, hit) } }
        val marks = pinned + kept.map(::markOf)
        return GlyphLayout(marks, hidden.sumOf { it.ids.size }, hit)
    }

    /** A selected element's glyphs in a row from its first anchor, `hit + FAN_GAP_DP` apart. */
    private fun fan(anchors: List<GlyphAnchor>, owner: (GlyphAnchor) -> ElementId?, hit: Int): List<GlyphMark> {
        val out = ArrayList<GlyphMark>()
        var nextFree = Double.NEGATIVE_INFINITY
        for ((_, group) in anchors.groupBy { owner(it) }) {
            val origin = group.first()
            val startX = maxOf(origin.x, nextFree)
            group.forEachIndexed { i, a -> out += GlyphMark.Glyph(a.id, startX + i * (hit + FAN_GAP_DP), origin.y) }
            nextFree = startX + group.size * (hit + FAN_GAP_DP)
        }
        return out
    }

    private fun perElement(anchors: List<GlyphAnchor>): List<Item> =
        anchors.groupBy { it.elementIds.firstOrNull() }.map { (element, group) ->
            Item(group.sumOf { it.x } / group.size, group.sumOf { it.y } / group.size, group.mapTo(ArrayList()) { it.id }, single = false, element = element)
        }

    private fun overlaps(ax: Double, ay: Double, bx: Double, by: Double, hit: Int): Boolean = abs(ax - bx) < hit && abs(ay - by) < hit

    private fun mergeOverlaps(items: List<Item>, hit: Int): List<Item> {
        val work = items.toMutableList()
        while (true) {
            val (i, j) = firstOverlap(work, hit) ?: return work
            work[i] = join(work[i], work[j])
            work.removeAt(j)
        }
    }

    private fun firstOverlap(work: List<Item>, hit: Int): Pair<Int, Int>? {
        for (i in work.indices) {
            val j = (i + 1 until work.size).firstOrNull { overlaps(work[i].x, work[i].y, work[it].x, work[it].y, hit) }
            if (j != null) return i to j
        }
        return null
    }

    private fun join(a: Item, b: Item): Item {
        val na = a.ids.size.toDouble()
        val nb = b.ids.size.toDouble()
        val ids = (a.ids + b.ids).toMutableList()
        return Item((a.x * na + b.x * nb) / (na + nb), (a.y * na + b.y * nb) / (na + nb), ids, single = false, element = a.element.takeIf { it == b.element })
    }

    private fun markOf(item: Item): GlyphMark = when {
        item.single && item.ids.size == 1 -> GlyphMark.Glyph(item.ids.single(), item.x, item.y)
        item.element != null -> GlyphMark.ElementBadge(item.element, item.ids, item.x, item.y)
        else -> GlyphMark.Badge(item.ids, item.x, item.y)
    }
}

/** Where a constraint's glyph belongs in the scene: the mean of its operands' points, a line or rectangle edge at its midpoint. */
object ConstraintAnchors {
    fun sceneAnchor(doc: SketchDocumentV2, c: Constraint): Pair<Double, Double>? {
        val points = c.targets.mapNotNull { t -> doc.scene[t.elementId]?.let { pointOf(it, t.part) } }
        if (points.isEmpty()) return null
        return points.sumOf { it.first } / points.size to points.sumOf { it.second } / points.size
    }

    private fun pointOf(e: SceneElement, part: TargetPart): Pair<Double, Double> =
        PartPoints.pointOf(e, part) ?: edgeMidpoint(e, part) ?: centre(e)

    private fun edgeMidpoint(e: SceneElement, part: TargetPart): Pair<Double, Double>? {
        val edge = part as? TargetPart.Edge ?: return null
        val corners: List<Pair<Double, Double>> = when (e) {
            is LineElement -> e.points.map { it.x.value to it.y.value }
            is RectElement -> PartHitTester.cornersOf(e)
            else -> return null
        }
        val a = corners.getOrNull(edge.index) ?: return null
        val b = corners.getOrNull((edge.index + 1) % corners.size) ?: return null
        return (a.first + b.first) / 2.0 to (a.second + b.second) / 2.0
    }

    private fun centre(e: SceneElement): Pair<Double, Double> = ElementBounds.geometry(e).let { (it.minX + it.maxX) / 2.0 to (it.minY + it.maxY) / 2.0 }

    /** Anchors in screen dp for the document, given the scene-to-screen map of the current view. */
    fun anchors(doc: SketchDocumentV2, toScreen: (Double, Double) -> Pair<Double, Double>): List<GlyphAnchor> =
        doc.constraints.mapNotNull { c ->
            val at = sceneAnchor(doc, c) ?: return@mapNotNull null
            val (x, y) = toScreen(at.first, at.second)
            GlyphAnchor(c.id, x, y, c.elementIds)
        }
}
