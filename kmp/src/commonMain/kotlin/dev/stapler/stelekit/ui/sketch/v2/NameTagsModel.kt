// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.ElementNames
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.tools.HandleLayout
import dev.stapler.stelekit.sketch.v2.tools.HandleTier
import dev.stapler.stelekit.sketch.v2.view.SceneBounds
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import kotlin.math.max

/** One chip on the canvas: the element's name at its screen anchor. */
data class NameTag(val id: ElementId, val text: String, val x: Double, val y: Double, val width: Double)

/** Overlapping tags collapsed into one count badge (UX-106); [ids] are the elements it stands for. */
data class NameBadge(val count: Int, val ids: List<ElementId>, val x: Double, val y: Double) {
    val description: String get() = "$count element names here, zoom in to read them"
}

/** Which elements get a tag: [selected], those a message [named], or every element in view when [showAll] ("Show names"). */
data class NameTagSubjects(val selected: Set<ElementId> = emptySet(), val named: Set<ElementId> = emptySet(), val showAll: Boolean = false)

/** What the canvas overlay draws: lone tags and badges. */
data class NameTagLayout(val tags: List<NameTag>, val badges: List<NameBadge>)

/**
 * Which names the canvas shows and where (Story 3.5.7b, UX-110). By default a tag appears for the selected elements and
 * for those a message names (constraint conflict, Go to element, Pick target); with "Show names" on, for every element
 * in view. Tags that would overlap collapse into a count badge by the same cell rule the constraint glyphs use.
 */
object NameTagsModel {
    /** Estimated chip size in screen pixels; the real chip hugs its text, the model only needs a stable collision box. */
    const val CHAR_WIDTH_PX = 7.0
    const val CHIP_PADDING_PX = 12.0
    const val CHIP_HEIGHT_PX = 22.0

    /** Gap between a chip and the element it names. */
    private const val ANCHOR_GAP_PX = 2.0

    /** "Show names" defaults ON on scaled/technical and converted sheets, OFF on diagram sheets (ux.md section 8). */
    /**
     * True when the lone selected shape's name chip would sit under its drawn corner handles: only the touch-only Compact tier
     * puts handles that close, and a line, arrow or point has no corners to cover. The caller names it for screen readers instead.
     */
    fun hiddenByCompactHandles(scene: SketchSceneV2, selection: Set<ElementId>, handles: HandleLayout?): Boolean {
        val only = selection.singleOrNull()?.let { scene[it] } ?: return false
        if (only is LineElement || only is ArrowElement || only is PointElement) return false
        return handles?.tier == HandleTier.Compact
    }

    fun defaultShowNames(technicalOrConverted: Boolean): Boolean = technicalOrConverted

    fun layout(
        scene: SketchSceneV2,
        viewport: SketchViewport,
        widthPx: Double,
        heightPx: Double,
        subjects: NameTagSubjects,
    ): NameTagLayout {
        val hidden = scene.layers.filter { !it.visible }.map { it.id }.toSet()
        val candidates = scene.elementsInOrder.filter {
            it.base.layerId !in hidden && (subjects.showAll || it.id in subjects.selected || it.id in subjects.named)
        }
        val tags = candidates.mapNotNull { e -> SceneBounds.of(e)?.let { tagFor(e.id, ElementNames.nameOf(e), it, viewport) } }
            .filter { it.x + it.width >= 0.0 && it.y + CHIP_HEIGHT_PX >= 0.0 && it.x <= widthPx && it.y <= heightPx }
        return collapse(tags)
    }

    private fun tagFor(id: ElementId, name: String, bounds: SceneBounds, viewport: SketchViewport): NameTag {
        val width = name.length * CHAR_WIDTH_PX + CHIP_PADDING_PX
        val x = bounds.minX * viewport.zoom + viewport.panX
        val y = bounds.minY * viewport.zoom + viewport.panY - CHIP_HEIGHT_PX - ANCHOR_GAP_PX
        return NameTag(id, name, x, y, width)
    }

    /** Greedy clustering in reading order: a tag that overlaps a cluster's box joins it; a cluster of one stays a tag. */
    private fun collapse(tags: List<NameTag>): NameTagLayout {
        class Cluster(var left: Double, var top: Double, var right: Double, var bottom: Double, val members: MutableList<NameTag>)
        val clusters = ArrayList<Cluster>()
        for (t in tags.sortedWith(compareBy({ it.y }, { it.x }, { it.id.value }))) {
            val hit = clusters.firstOrNull { c -> t.x < c.right && t.x + t.width > c.left && t.y < c.bottom && t.y + CHIP_HEIGHT_PX > c.top }
            if (hit == null) {
                clusters += Cluster(t.x, t.y, t.x + t.width, t.y + CHIP_HEIGHT_PX, mutableListOf(t))
            } else {
                hit.members += t
                hit.right = max(hit.right, t.x + t.width)
                hit.bottom = max(hit.bottom, t.y + CHIP_HEIGHT_PX)
            }
        }
        val single = clusters.filter { it.members.size == 1 }.map { it.members.single() }
        val badges = clusters.filter { it.members.size > 1 }.map { c -> NameBadge(c.members.size, c.members.map { it.id }, c.left, c.top) }
        return NameTagLayout(single, badges)
    }
}
