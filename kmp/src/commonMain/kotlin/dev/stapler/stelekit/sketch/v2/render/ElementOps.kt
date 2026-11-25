// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.render

import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.OpeningElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import dev.stapler.stelekit.sketch.v2.scene.WallGeometry
import dev.stapler.stelekit.sketch.v2.scene.WallScene
import dev.stapler.stelekit.sketch.v2.scene.WallStroke
import dev.stapler.stelekit.sketch.v2.scene.WallVec
import dev.stapler.stelekit.sketch.v2.scene.Roundness
import dev.stapler.stelekit.sketch.v2.scene.RoundnessKind
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.StrokeStyle
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.VerticalAlign
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.text.TextStyle
import kotlin.math.min

/** Stroke and fill paint of an element style; shared by [DrawListBuilder] and [StaticOpBuilder]. */
internal object PaintStyle {
    const val TRANSPARENT = "transparent"
    private const val DASH_ON = 8.0
    private const val DASH_GAP = 8.0
    private const val DOT_ON = 1.5
    private const val DOT_GAP = 6.0

    fun strokeOf(style: ElementStyle): StrokePaint? {
        if (style.strokeColor == TRANSPARENT || style.strokeWidth == 0.0) return null
        val w = style.strokeWidth
        val dash = when (style.strokeStyle) {
            StrokeStyle.Solid -> emptyList()
            StrokeStyle.Dashed -> listOf(DASH_ON, DASH_GAP + w)
            StrokeStyle.Dotted -> listOf(DOT_ON, DOT_GAP + w)
        }
        return StrokePaint(style.strokeColor, w, dash)
    }

    fun fillOf(style: ElementStyle): String? = style.backgroundColor.takeIf { it != TRANSPARENT && it.isNotEmpty() }

    fun pathOp(e: SceneElement, segments: List<PathSegment>, opacity: Double, filled: Boolean): DrawOp =
        DrawOp.PathOp(e.id, segments, strokeOf(e.base.style), if (filled) fillOf(e.base.style) else null, opacity)
}

/** Corner and outline geometry of boxes; shared by [DrawListBuilder] and [StaticOpBuilder]. */
internal object BoxGeometry {
    private const val PROPORTIONAL_RADIUS = 0.25
    private const val ADAPTIVE_RADIUS = 32.0

    fun cornerRadius(roundness: Roundness?, width: Double, height: Double): Double {
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

    fun boxCorners(x: Double, y: Double, w: Double, h: Double, diamond: Boolean): List<Pt> = if (diamond) {
        listOf(Pt(x + w / 2.0, y), Pt(x + w, y + h / 2.0), Pt(x + w / 2.0, y + h), Pt(x, y + h / 2.0))
    } else {
        listOf(Pt(x, y), Pt(x + w, y), Pt(x + w, y + h), Pt(x, y + h))
    }

    fun boxSegments(corners: List<Pt>, radius: Double, angle: Double, pivotX: Double, pivotY: Double): List<PathSegment> {
        val b = PathBuilder(angle, pivotX, pivotY)
        PathGeometry.polygon(b, corners, radius)
        return b.build()
    }
}

/** Draw ops of the elements that are never restyled by a [StrokeStyleProvider]: freedraw, text, images, frames, points, arcs. */
internal class StaticOpBuilder(private val metrics: TextMetricsProvider) {
    fun freedraw(e: FreedrawElement, opacity: Double): DrawOp {
        val measured = e.points.size == e.pressures.size && !e.simulatePressure
        val input = e.points.mapIndexed { i, p ->
            if (measured) FreehandPoint(p.x.value, p.y.value, e.pressures[i]) else FreehandPoint(p.x.value, p.y.value)
        }
        val outline = FreehandOutline.buildPts(input, FreehandOptions.excalidraw(e.base.style.strokeWidth, simulatePressure = !measured))
        val fill = e.base.style.strokeColor.takeIf { it != PaintStyle.TRANSPARENT }
        return DrawOp.PathOp(e.id, FreehandOutline.toPath(outline), null, fill, opacity)
    }

    fun point(e: PointElement, opacity: Double): DrawOp {
        val x = e.x.value
        val y = e.y.value
        val b = PathBuilder()
        b.moveTo(Pt(x - POINT_ARM, y)).lineTo(Pt(x + POINT_ARM, y)).moveTo(Pt(x, y - POINT_ARM)).lineTo(Pt(x, y + POINT_ARM))
        return PaintStyle.pathOp(e, b.build(), opacity, filled = false)
    }

    fun arc(e: ArcElement, opacity: Double): DrawOp {
        val b = PathBuilder()
        PathGeometry.arc(b, e.cx.value, e.cy.value, e.radius.value, e.startAngle, e.endAngle)
        return PaintStyle.pathOp(e, b.build(), opacity, filled = false)
    }

    /** The wall body as one filled path (when it has a background colour) and its faces, caps and jambs as one stroked path; crisp, never restyled. */
    fun wall(e: WallElement, openings: List<OpeningElement>, opacity: Double): List<DrawOp> {
        val layout = WallScene.layout(e, openings)
        val ops = ArrayList<DrawOp>(2)
        val fill = PaintStyle.fillOf(e.base.style)
        if (fill != null && layout.fills.isNotEmpty()) ops += DrawOp.PathOp(e.id, layout.fills.flatMap { closedPath(it) }, null, fill, opacity)
        ops += DrawOp.PathOp(e.id, layout.strokes.flatMap { strokePath(it) }, PaintStyle.strokeOf(e.base.style), null, opacity)
        return ops
    }

    /** A door's leaf and swing arc or a window's frame, drawn in the opening's own style over the wall it sits on. */
    fun opening(e: OpeningElement, host: WallElement, opacity: Double): List<DrawOp> {
        val strokes = WallGeometry.symbol(host, e)
        if (strokes.isEmpty()) return emptyList()
        return listOf(DrawOp.PathOp(e.id, strokes.flatMap { strokePath(it) }, PaintStyle.strokeOf(e.base.style), null, opacity))
    }

    private fun closedPath(points: List<WallVec>): List<PathSegment> =
        listOf<PathSegment>(PathSegment.MoveTo(points.first().x, points.first().y)) + points.drop(1).map { PathSegment.LineTo(it.x, it.y) } + PathSegment.Close

    private fun strokePath(s: WallStroke): List<PathSegment> {
        val path = closedPath(s.points)
        return if (s.closed) path else path.dropLast(1)
    }

    fun text(e: TextElement, opacity: Double): DrawOp {
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

    fun image(e: ImageElement, opacity: Double): DrawOp = DrawOp.ImageOp(
        e.id, e.fileId, DrawRect(e.x.value, e.y.value, e.width.value, e.height.value),
        e.angle, e.flipX, e.flipY, e.crop, opacity,
    )

    fun frame(e: FrameElement, opacity: Double): List<DrawOp> {
        val corners = BoxGeometry.boxCorners(e.x.value, e.y.value, e.width.value, e.height.value, diamond = false)
        val radius = BoxGeometry.cornerRadius(e.base.style.roundness, e.width.value, e.height.value)
        val segments = BoxGeometry.boxSegments(corners, radius, e.angle, e.x.value + e.width.value / 2.0, e.y.value + e.height.value / 2.0)
        val outlineOnly = PaintStyle.pathOp(e, segments, opacity, filled = false)
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
        const val POINT_ARM = 4.0
        const val FRAME_TITLE_SIZE = 14.0
        const val FRAME_TITLE_GAP = 4.0
        const val FRAME_TITLE_FONT = 2
    }
}
