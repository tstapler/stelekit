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
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.Roundness
import dev.stapler.stelekit.sketch.v2.scene.RoundnessKind
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.StrokeStyle
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.VerticalAlign
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.text.TextStyle
import kotlin.math.min

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
class DrawListBuilder(private val metrics: TextMetricsProvider) {
    private class Cached(val version: Int, val opacity: Double, val style: RenderStyle, val ops: List<DrawOp>)

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
        if (hit != null && hit.version == element.base.version && hit.opacity == opacity && hit.style == style) return hit.ops
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
        is FreedrawElement -> listOf(freedraw(element, opacity))
        is TextElement -> listOf(text(element, opacity))
        is ImageElement -> listOf(image(element, opacity))
        is FrameElement -> frame(element, opacity)
        is PointElement -> listOf(point(element, opacity))
        is ArcElement -> listOf(arc(element, opacity))
    }

    private fun strokeOf(style: ElementStyle): StrokePaint? {
        if (style.strokeColor == TRANSPARENT || style.strokeWidth == 0.0) return null
        val w = style.strokeWidth
        val dash = when (style.strokeStyle) {
            StrokeStyle.Solid -> emptyList()
            StrokeStyle.Dashed -> listOf(DASH_ON, DASH_GAP + w)
            StrokeStyle.Dotted -> listOf(DOT_ON, DOT_GAP + w)
        }
        return StrokePaint(style.strokeColor, w, dash)
    }

    private fun fillOf(style: ElementStyle): String? = style.backgroundColor.takeIf { it != TRANSPARENT && it.isNotEmpty() }

    private fun cornerRadius(roundness: Roundness?, width: Double, height: Double): Double {
        if (roundness == null) return 0.0
        val shortSide = min(width, height)
        val radius = when (roundness.kind) {
            RoundnessKind.Legacy, RoundnessKind.ProportionalRadius -> shortSide * (roundness.value ?: PROPORTIONAL_RADIUS)
            RoundnessKind.AdaptiveRadius -> {
                val fixed = roundness.value ?: ADAPTIVE_RADIUS
                if (shortSide <= fixed / PROPORTIONAL_RADIUS) shortSide * PROPORTIONAL_RADIUS else fixed
            }
        }
        return min(radius, shortSide / 2.0)
    }

    private fun box(e: SceneElement, x: Double, y: Double, w: Double, h: Double, angle: Double, opacity: Double, diamond: Boolean, strokes: StrokeStyleProvider): List<DrawOp> {
        val radius = cornerRadius(e.base.style.roundness, w, h)
        val corners = boxCorners(x, y, w, h, diamond)
        val clean = boxSegments(corners, radius, angle, x + w / 2.0, y + h / 2.0)
        val geometry = if (radius <= 0.0) StrokeGeometry.Polyline(corners.map { ScenePt(it.x, it.y) }, closed = true) else StrokeGeometry.Outline
        val turn = if (radius <= 0.0) angle else 0.0
        return styled(e, clean, geometry, turn, x + w / 2.0, y + h / 2.0, opacity, filled = true, strokes)
    }

    private fun boxCorners(x: Double, y: Double, w: Double, h: Double, diamond: Boolean): List<Pt> = if (diamond) {
        listOf(Pt(x + w / 2.0, y), Pt(x + w, y + h / 2.0), Pt(x + w / 2.0, y + h), Pt(x, y + h / 2.0))
    } else {
        listOf(Pt(x, y), Pt(x + w, y), Pt(x + w, y + h), Pt(x, y + h))
    }

    private fun boxSegments(corners: List<Pt>, radius: Double, angle: Double, pivotX: Double, pivotY: Double): List<PathSegment> {
        val b = PathBuilder(angle, pivotX, pivotY)
        PathGeometry.polygon(b, corners, radius)
        return b.build()
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
        val stroke = strokeOf(e.base.style)
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
     * One element outline through the style: a restyled outline is stroked on its own and any fill stays a plain
     * fill of the Clean outline underneath (fill styles other than solid are Story 2.2.2). Unchanged geometry stays one op.
     */
    private fun styled(
        e: SceneElement, clean: List<PathSegment>, geometry: StrokeGeometry, angle: Double, pivotX: Double, pivotY: Double,
        opacity: Double, filled: Boolean, strokes: StrokeStyleProvider,
    ): List<DrawOp> {
        val drawn = strokes.outline(StrokeRequest(geometry, e.base.seed, e.base.style.roughness, angle, pivotX, pivotY), clean)
        val stroke = strokeOf(e.base.style)
        if (drawn === clean || stroke == null) return listOf(pathOp(e, clean, opacity, filled))
        val fill = if (filled) fillOf(e.base.style) else null
        val stroked = DrawOp.PathOp(e.id, drawn, stroke, null, opacity)
        return if (fill == null) listOf(stroked) else listOf(DrawOp.PathOp(e.id, clean, null, fill, opacity), stroked)
    }

    private fun freedraw(e: FreedrawElement, opacity: Double): DrawOp {
        val measured = e.points.size == e.pressures.size && !e.simulatePressure
        val input = e.points.mapIndexed { i, p ->
            if (measured) FreehandPoint(p.x.value, p.y.value, e.pressures[i]) else FreehandPoint(p.x.value, p.y.value)
        }
        val outline = FreehandOutline.buildPts(input, FreehandOptions.excalidraw(e.base.style.strokeWidth, simulatePressure = !measured))
        val fill = e.base.style.strokeColor.takeIf { it != TRANSPARENT }
        return DrawOp.PathOp(e.id, FreehandOutline.toPath(outline), null, fill, opacity)
    }

    private fun point(e: PointElement, opacity: Double): DrawOp {
        val x = e.x.value
        val y = e.y.value
        val b = PathBuilder()
        b.moveTo(Pt(x - POINT_ARM, y)).lineTo(Pt(x + POINT_ARM, y)).moveTo(Pt(x, y - POINT_ARM)).lineTo(Pt(x, y + POINT_ARM))
        return pathOp(e, b.build(), opacity, filled = false)
    }

    private fun arc(e: ArcElement, opacity: Double): DrawOp {
        val b = PathBuilder()
        PathGeometry.arc(b, e.cx.value, e.cy.value, e.radius.value, e.startAngle, e.endAngle)
        return pathOp(e, b.build(), opacity, filled = false)
    }

    private fun pathOp(e: SceneElement, segments: List<PathSegment>, opacity: Double, filled: Boolean): DrawOp =
        DrawOp.PathOp(e.id, segments, strokeOf(e.base.style), if (filled) fillOf(e.base.style) else null, opacity)

    private fun text(e: TextElement, opacity: Double): DrawOp {
        val lines = e.text.split('\n')
        val lineHeight = e.fontSize * e.lineHeight
        val blockHeight = lines.size * lineHeight
        val top = when (e.verticalAlign) {
            VerticalAlign.Top -> e.y.value
            VerticalAlign.Middle -> e.y.value + (e.height.value - blockHeight) / 2.0
            VerticalAlign.Bottom -> e.y.value + e.height.value - blockHeight
        }
        val anchor = when (e.textAlign) {
            TextAlign.Left -> e.x.value
            TextAlign.Center -> e.x.value + e.width.value / 2.0
            TextAlign.Right -> e.x.value + e.width.value
        }
        val style = TextStyle(e.fontFamily, e.fontSize)
        var fallback = false
        for (ch in e.text) if (ch != '\n' && !fallback && !metrics.hasGlyph(ch.code, style)) fallback = true
        return DrawOp.TextRun(
            e.id,
            lines.mapIndexed { i, s -> TextLine(s, anchor, top + i * lineHeight) },
            e.fontFamily, e.fontSize, e.base.style.strokeColor, e.textAlign,
            e.angle, e.x.value + e.width.value / 2.0, e.y.value + e.height.value / 2.0,
            fallback, opacity,
        )
    }

    private fun image(e: ImageElement, opacity: Double): DrawOp = DrawOp.ImageOp(
        e.id, e.fileId, DrawRect(e.x.value, e.y.value, e.width.value, e.height.value),
        e.angle, e.flipX, e.flipY, e.crop, opacity,
    )

    private fun frame(e: FrameElement, opacity: Double): List<DrawOp> {
        val corners = boxCorners(e.x.value, e.y.value, e.width.value, e.height.value, diamond = false)
        val radius = cornerRadius(e.base.style.roundness, e.width.value, e.height.value)
        val segments = boxSegments(corners, radius, e.angle, e.x.value + e.width.value / 2.0, e.y.value + e.height.value / 2.0)
        val outlineOnly = pathOp(e, segments, opacity, filled = false)
        val title = e.base.name ?: return listOf(outlineOnly)
        val style = ElementStyle(strokeColor = e.base.style.strokeColor)
        val run = DrawOp.TextRun(
            e.id, listOf(TextLine(title, e.x.value, e.y.value - FRAME_TITLE_SIZE * TextElement.DEFAULT_LINE_HEIGHT - FRAME_TITLE_GAP)),
            FRAME_TITLE_FONT, FRAME_TITLE_SIZE, style.strokeColor, TextAlign.Left,
            0.0, e.x.value, e.y.value, !metrics.hasGlyphs(title, TextStyle(FRAME_TITLE_FONT, FRAME_TITLE_SIZE)), opacity,
        )
        return listOf(outlineOnly, run)
    }

    private fun TextMetricsProvider.hasGlyphs(text: String, style: TextStyle): Boolean = text.all { hasGlyph(it.code, style) }

    private companion object {
        const val TRANSPARENT = "transparent"
        const val MIN_SHAFT_POINTS = 2
        const val PROPORTIONAL_RADIUS = 0.25
        const val ADAPTIVE_RADIUS = 32.0
        const val DASH_ON = 8.0
        const val DASH_GAP = 8.0
        const val DOT_ON = 1.5
        const val DOT_GAP = 6.0
        const val POINT_ARM = 4.0
        const val FRAME_TITLE_SIZE = 14.0
        const val FRAME_TITLE_GAP = 4.0
        const val FRAME_TITLE_FONT = 2
    }
}
