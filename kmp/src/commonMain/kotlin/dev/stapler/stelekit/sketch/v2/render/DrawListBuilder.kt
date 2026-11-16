// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.render

import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FillStyle
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import kotlin.math.max

/**
 * Scene to draw list (Story 2.1.1, ADR-004): `SceneElement -> DrawOp` in paint order (layer order, then z-key, then
 * array order), hidden layers omitted, effective opacity = element x layer, frame members wrapped in a [DrawOp.ClipOp].
 *
 * Text is drawn from the persisted size and line breaks (`TextLayoutPolicy.forRender`): nothing is measured here;
 * [metrics] is only asked whether a glyph is in the bundled font. Freedraw is the perfect-freehand outline of its
 * points (Story 2.1.4), filled with the stroke colour.
 *
 * Not thread-safe: it keeps one cached op list per element id, valid while `(version, effective opacity, style)`
 * match, and drops entries of elements that left the scene. Owns no scope.
 */
class DrawListBuilder(metrics: TextMetricsProvider) {
    private val staticOps = StaticOpBuilder(metrics)

    private class Cached(val version: Int, val opacity: Double, val style: RenderStyle, val ops: List<DrawOp>) {
        fun matches(version: Int, opacity: Double, style: RenderStyle): Boolean =
            this.version == version && this.opacity == opacity && this.style == style
    }

    private val cache = HashMap<ElementId, Cached>()

    /** How many elements were converted (not served from the cache) over this builder's life; tests read it. */
    var builtElementCount: Int = 0
        private set

    fun build(scene: SketchSceneV2, style: RenderStyle): List<DrawOp> {
        val layers = scene.layers.associateBy { it.id }
        val indexed = scene.elementsInOrder.withIndex().toList()
        val sorted = indexed.sortedWith(
            compareBy<IndexedValue<SceneElement>>({ (layers[it.value.base.layerId] ?: Layer.DefaultLayer).order }, { it.value.base.zKey }, { it.index }),
        )
        val out = ArrayList<DrawOp>()
        val live = HashSet<ElementId>()
        for ((_, element) in sorted) {
            val layer = layers[element.base.layerId] ?: Layer.DefaultLayer
            live.add(element.id)
            if (!layer.visible) continue
            val ops = opsFor(element, layer.opacity, style)
            val frame = element.base.frameId?.let { scene[it] } as? FrameElement
            if (frame != null && element !is FrameElement) {
                out.add(DrawOp.ClipOp(DrawRect(frame.x.value, frame.y.value, frame.width.value, frame.height.value), ops))
            } else {
                out.addAll(ops)
            }
        }
        cache.keys.retainAll(live)
        return out
    }

    private fun opsFor(element: SceneElement, layerOpacity: Double, style: RenderStyle): List<DrawOp> {
        val opacity = element.base.opacity / ElementBase.FULL_OPACITY.toDouble() * layerOpacity
        val hit = cache[element.id]
        if (hit != null && hit.matches(element.base.version, opacity, style)) return hit.ops
        val ops = convert(element, opacity, StrokeStyleProvider.forStyle(style))
        cache[element.id] = Cached(element.base.version, opacity, style, ops)
        builtElementCount++
        return ops
    }

    private fun convert(element: SceneElement, opacity: Double, strokes: StrokeStyleProvider): List<DrawOp> = when (element) {
        is RectElement -> box(element, element.x.value, element.y.value, element.width.value, element.height.value, element.angle, opacity, diamond = false, strokes)
        is DiamondElement -> box(element, element.x.value, element.y.value, element.width.value, element.height.value, element.angle, opacity, diamond = true, strokes)
        is EllipseElement -> ellipse(element, opacity, strokes)
        is LineElement -> line(element, opacity, strokes)
        is ArrowElement -> arrow(element, opacity, strokes)
        is FreedrawElement -> listOf(staticOps.freedraw(element, opacity))
        is TextElement -> listOf(staticOps.text(element, opacity))
        is ImageElement -> listOf(staticOps.image(element, opacity))
        is FrameElement -> staticOps.frame(element, opacity)
        is PointElement -> listOf(staticOps.point(element, opacity))
        is ArcElement -> listOf(staticOps.arc(element, opacity))
    }

    private fun box(e: SceneElement, x: Double, y: Double, w: Double, h: Double, angle: Double, opacity: Double, diamond: Boolean, strokes: StrokeStyleProvider): List<DrawOp> {
        val radius = BoxGeometry.cornerRadius(e.base.style.roundness, w, h)
        val corners = BoxGeometry.boxCorners(x, y, w, h, diamond)
        val clean = BoxGeometry.boxSegments(corners, radius, angle, x + w / 2.0, y + h / 2.0)
        val geometry = if (radius <= 0.0) StrokeGeometry.Polyline(corners.map { ScenePt(it.x, it.y) }, closed = true) else StrokeGeometry.Outline
        val turn = if (radius <= 0.0) angle else 0.0
        return styled(e, clean, geometry, turn, x + w / 2.0, y + h / 2.0, opacity, filled = true, strokes)
    }

    private fun ellipse(e: EllipseElement, opacity: Double, strokes: StrokeStyleProvider): List<DrawOp> {
        val b = PathBuilder(e.angle, e.cx.value, e.cy.value)
        PathGeometry.ellipse(b, e.cx.value, e.cy.value, e.rx.value, e.ry.value)
        val geometry = StrokeGeometry.Ellipse(e.cx.value, e.cy.value, e.rx.value, e.ry.value)
        return styled(e, b.build(), geometry, e.angle, e.cx.value, e.cy.value, opacity, filled = true, strokes)
    }

    private fun points(list: List<ScenePoint>): List<Pt> = list.map { Pt(it.x.value, it.y.value) }

    private fun strokeLine(b: PathBuilder, pts: List<Pt>, smooth: Boolean, closed: Boolean) {
        if (smooth) PathGeometry.smoothLine(b, pts, closed) else PathGeometry.polyline(b, pts, closed)
    }

    private fun lineGeometry(pts: List<Pt>, smooth: Boolean, closed: Boolean): StrokeGeometry {
        val scenePts = pts.map { ScenePt(it.x, it.y) }
        return if (smooth && pts.size >= PathGeometry.MIN_CURVE_POINTS && !closed) StrokeGeometry.Curve(scenePts) else StrokeGeometry.Polyline(scenePts, closed)
    }

    private fun line(e: LineElement, opacity: Double, strokes: StrokeStyleProvider): List<DrawOp> {
        val pts = points(e.points)
        val smooth = e.base.style.roundness != null
        val b = PathBuilder()
        strokeLine(b, pts, smooth, e.polygon)
        return styled(e, b.build(), lineGeometry(pts, smooth, e.polygon), 0.0, 0.0, 0.0, opacity, filled = e.polygon, strokes)
    }

    private fun arrow(e: ArrowElement, opacity: Double, strokes: StrokeStyleProvider): List<DrawOp> {
        val pts = points(e.points)
        val b = PathBuilder()
        val smooth = e.base.style.roundness != null && !e.elbowed
        strokeLine(b, pts, smooth, closed = false)
        val ops = ArrayList<DrawOp>()
        ops.addAll(styled(e, b.build(), lineGeometry(pts, smooth, closed = false), 0.0, 0.0, 0.0, opacity, filled = false, strokes))
        if (pts.size >= MIN_SHAFT_POINTS) {
            val last = pts.size - 1
            ops.addAll(head(e, e.endArrowhead, pts[last], pts[last - 1], opacity, strokes))
            ops.addAll(head(e, e.startArrowhead, pts[0], pts[1], opacity, strokes))
        }
        return ops
    }

    private fun head(e: ArrowElement, kind: dev.stapler.stelekit.sketch.v2.scene.Arrowhead, tip: Pt, from: Pt, opacity: Double, strokes: StrokeStyleProvider): List<DrawOp> {
        val shaft = tip - from
        val length = shaft.length()
        if (length == 0.0) return emptyList()
        val direction = shaft * (1.0 / length)
        val stroke = PaintStyle.strokeOf(e.base.style)
        val ops = ArrayList<DrawOp>()
        for (shape in ArrowheadGeometry.shapes(kind, tip, direction, length)) {
            val fill = if (shape.filled) e.base.style.strokeColor else null
            val drawn = strokes.outline(StrokeRequest(StrokeGeometry.Outline, e.base.seed, e.base.style.roughness), shape.segments)
            if (drawn === shape.segments || stroke == null) {
                ops.add(DrawOp.PathOp(e.id, shape.segments, stroke, fill, opacity))
            } else {
                if (fill != null) ops.add(DrawOp.PathOp(e.id, shape.segments, null, fill, opacity))
                ops.add(DrawOp.PathOp(e.id, drawn, stroke, null, opacity))
            }
        }
        return ops
    }

    /**
     * One element outline through the style: a restyled outline is stroked on its own and the fill is the hatch the
     * style generated (hachure, cross-hatch, zigzag) or else a plain fill of the Clean outline underneath. Unchanged
     * geometry stays one op.
     */
    private fun styled(
        e: SceneElement, clean: List<PathSegment>, geometry: StrokeGeometry, angle: Double, pivotX: Double, pivotY: Double,
        opacity: Double, filled: Boolean, strokes: StrokeStyleProvider,
    ): List<DrawOp> {
        val style = e.base.style
        val fillColor = if (filled) PaintStyle.fillOf(style) else null
        val request = StrokeRequest(geometry, e.base.seed, style.roughness, angle, pivotX, pivotY, fillRequestOf(style, fillColor))
        val result = strokes.stroke(request, clean)
        val stroke = PaintStyle.strokeOf(style)
        if (result.outline === clean || (stroke == null && result.fill == null)) return listOf(PaintStyle.pathOp(e, clean, opacity, filled))
        val ops = ArrayList<DrawOp>(2)
        if (fillColor != null) {
            ops += if (result.fill != null) {
                DrawOp.PathOp(e.id, result.fill, StrokePaint(fillColor, hatchWeight(style)), null, opacity)
            } else {
                DrawOp.PathOp(e.id, clean, null, fillColor, opacity)
            }
        }
        if (stroke != null) ops += DrawOp.PathOp(e.id, result.outline, stroke, null, opacity)
        return ops
    }

    private fun hatchWeight(style: ElementStyle): Double = max(style.strokeWidth, MIN_HATCH_STROKE) / HATCH_WEIGHT_DIVISOR

    /** Excalidraw's hatch options: gap four stroke widths, line weight half a stroke width; solid fills need none. */
    private fun fillRequestOf(style: ElementStyle, fillColor: String?): FillRequest? =
        if (fillColor == null || style.fillStyle == FillStyle.Solid) null
        else FillRequest(style.fillStyle, max(style.strokeWidth, MIN_HATCH_STROKE) * HATCH_GAP_PER_STROKE)

    private companion object {
        const val MIN_SHAFT_POINTS = 2
        const val MIN_HATCH_STROKE = 1.0
        const val HATCH_GAP_PER_STROKE = 4.0
        const val HATCH_WEIGHT_DIVISOR = 2.0
    }
}
