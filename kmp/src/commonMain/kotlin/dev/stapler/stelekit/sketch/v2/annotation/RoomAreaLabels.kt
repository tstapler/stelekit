// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.annotation

import dev.stapler.stelekit.sketch.v2.scene.ElementNames
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import dev.stapler.stelekit.sketch.v2.scene.WallGeometry
import dev.stapler.stelekit.units.LengthFormat

/**
 * The floor-area label of each closed wall: the clear area inside its inner face ([RoomArea] text, in the active unit) centred on
 * the room. A label is dropped when its text does not fit inside the room on screen, when its centre is not inside the room (an
 * L-shaped room whose centroid falls in the notch), or when it would sit on another annotation. It carries no dimension line.
 */
internal object RoomAreaLabels {
    private const val HALF = 2.0

    fun layout(
        candidates: List<SceneElement>,
        canvas: AnnotationCanvas,
        fontScale: Double,
        format: LengthFormat,
        taken: List<AnnotationRect>,
    ): List<PlacedDimension> {
        val out = ArrayList<PlacedDimension>()
        val blocked = ArrayList(taken)
        for (wall in candidates.filterIsInstance<WallElement>().filter { it.closed }) {
            val label = label(wall, canvas, fontScale, format, blocked) ?: continue
            blocked += label.textRect
            out += label
        }
        return out
    }

    private fun label(wall: WallElement, canvas: AnnotationCanvas, fontScale: Double, format: LengthFormat, blocked: List<AnnotationRect>): PlacedDimension? {
        val room = WallGeometry.interiorPolygon(wall) ?: return null
        val area = WallGeometry.area(room)
        val screen = room.map { canvas.toScreenDp(it.x, it.y) }.map { AnnotationPoint(it.first, it.second) }
        val centre = centroid(screen) ?: return null
        val text = RoomArea.text(area, format)
        val (tw, th) = DimensionAnnotationEngine.textSize(text, fontScale)
        val rect = AnnotationRect(centre.x - tw / HALF, centre.y - th / HALF, centre.x + tw / HALF, centre.y + th / HALF)
        val width = screen.maxOf { it.x } - screen.minOf { it.x }
        val alpha = ((width / tw - DimensionAnnotationEngine.FADE_START_RATIO) / (DimensionAnnotationEngine.FADE_FULL_RATIO - DimensionAnnotationEngine.FADE_START_RATIO)).coerceIn(0.0, 1.0)
        val corners = listOf(AnnotationPoint(rect.left, rect.top), AnnotationPoint(rect.right, rect.top), AnnotationPoint(rect.right, rect.bottom), AnnotationPoint(rect.left, rect.bottom))
        val fits = alpha >= DimensionAnnotationEngine.MIN_ALPHA && corners.all { inside(it, screen) }
        val onScreen = centre.x in 0.0..canvas.width && centre.y in 0.0..canvas.height
        val clear = blocked.none { it.overlaps(rect.inflated(DimensionAnnotationEngine.TEXT_GAP_DP)) }
        if (!fits || !onScreen || !clear) return null
        val at = AnnotationSegment(centre, centre)
        return PlacedDimension(
            wall.id, ElementNames.nameOf(wall), MeasureKind.Length, area.toLong(), text, at, emptyList(), rect, rect, alpha, DimensionAnnotationEngine.AREA_TAG,
        )
    }

    /** The area-weighted centre of a simple polygon; null for a degenerate one. */
    private fun centroid(p: List<AnnotationPoint>): AnnotationPoint? {
        var a = 0.0
        var cx = 0.0
        var cy = 0.0
        for (i in p.indices) {
            val q = p[i]
            val r = p[(i + 1) % p.size]
            val cross = q.x * r.y - r.x * q.y
            a += cross
            cx += (q.x + r.x) * cross
            cy += (q.y + r.y) * cross
        }
        if (a == 0.0) return null
        return AnnotationPoint(cx / (3.0 * a), cy / (3.0 * a))
    }

    /** Even-odd test: whether [pt] is inside the polygon [poly]. */
    private fun inside(pt: AnnotationPoint, poly: List<AnnotationPoint>): Boolean {
        var odd = false
        var j = poly.lastIndex
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[j]
            if ((a.y > pt.y) != (b.y > pt.y) && pt.x < (b.x - a.x) * (pt.y - a.y) / (b.y - a.y) + a.x) odd = !odd
            j = i
        }
        return odd
    }
}
