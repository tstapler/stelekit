// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.view

import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.BoxElement
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A position in screen pixels. */
data class ScreenPx(val x: Double, val y: Double)

/** Axis-aligned extent of scene content, in scene units. */
data class SceneBounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY

    companion object {
        /** Union of the elements' extents (rotation included), or null for no elements. */
        fun of(elements: Iterable<SceneElement>): SceneBounds? {
            var acc: SceneBounds? = null
            for (e in elements) {
                val b = of(e) ?: continue
                acc = acc?.let { SceneBounds(min(it.minX, b.minX), min(it.minY, b.minY), max(it.maxX, b.maxX), max(it.maxY, b.maxY)) } ?: b
            }
            return acc
        }

        fun of(element: SceneElement): SceneBounds? = when (element) {
            is BoxElement -> rotated(element.x.value + element.width.value / HALF, element.y.value + element.height.value / HALF, element.width.value / HALF, element.height.value / HALF, element.angle)
            is EllipseElement -> rotated(element.cx.value, element.cy.value, element.rx.value, element.ry.value, element.angle)
            is LineElement -> ofPoints(element.points)
            is ArrowElement -> ofPoints(element.points)
            is FreedrawElement -> ofPoints(element.points)
            is PointElement -> SceneBounds(element.x.value, element.y.value, element.x.value, element.y.value)
            is ArcElement -> element.radius.value.let { r -> SceneBounds(element.cx.value - r, element.cy.value - r, element.cx.value + r, element.cy.value + r) }
        }

        private const val HALF = 2.0

        private fun ofPoints(points: List<ScenePoint>): SceneBounds? =
            if (points.isEmpty()) null else SceneBounds(
                points.minOf { it.x.value }, points.minOf { it.y.value }, points.maxOf { it.x.value }, points.maxOf { it.y.value },
            )

        /** Extent of a box with half-sizes [hw] x [hh] centred at ([cx], [cy]) and turned by [angle] radians. */
        private fun rotated(cx: Double, cy: Double, hw: Double, hh: Double, angle: Double): SceneBounds {
            val c = abs(cos(angle))
            val s = abs(sin(angle))
            val ex = hw * c + hh * s
            val ey = hw * s + hh * c
            return SceneBounds(cx - ex, cy - ey, cx + ex, cy + ey)
        }
    }
}

/**
 * Scene to screen mapping, `screen = scene * zoom + pan`, where [zoom] is screen pixels per scene unit (the same
 * value `ToolContext.zoom` carries). Immutable; zoom is clamped to `SceneLimits.minZoomPercent` to
 * `maxZoomPercent` (1% to 3000%) and zooming keeps the scene point under the anchor fixed (Story 3.1.2, UX-09).
 */
class SketchViewport private constructor(
    val zoom: Double,
    val panX: Double,
    val panY: Double,
    val limits: SceneLimits,
) {
    val minZoom: Double get() = limits.minZoomPercent / PERCENT
    val maxZoom: Double get() = limits.maxZoomPercent / PERCENT

    fun toScreen(p: ScenePoint): ScreenPx = ScreenPx(p.x.value * zoom + panX, p.y.value * zoom + panY)

    /** Coordinates beyond `SceneLimits.maxCoord` are clamped to it, so the result is always a valid point. */
    fun toScene(s: ScreenPx): ScenePoint = ScenePoint(unit((s.x - panX) / zoom), unit((s.y - panY) / zoom))

    fun panBy(dx: Double, dy: Double): SketchViewport =
        if (dx.isFinite() && dy.isFinite()) SketchViewport(zoom, panX + dx, panY + dy, limits) else this

    /** Multiplies the zoom by [factor] (clamped) about [anchor]; a non-positive or non-finite factor changes nothing. */
    fun zoomBy(anchor: ScreenPx, factor: Double): SketchViewport =
        if (factor.isFinite() && factor > 0.0) zoomTo(anchor, zoom * factor) else this

    fun zoomTo(anchor: ScreenPx, newZoom: Double): SketchViewport {
        if (!newZoom.isFinite() || newZoom <= 0.0) return this
        val z = newZoom.coerceIn(minZoom, maxZoom)
        val ratio = z / zoom
        return SketchViewport(z, anchor.x - (anchor.x - panX) * ratio, anchor.y - (anchor.y - panY) * ratio, limits)
    }

    /**
     * Zoom and pan so [bounds] fills the [viewWidth] x [viewHeight] pixel area, leaving [paddingPx] on each side,
     * centred. No content (null bounds) resets to 100% at the origin. A degenerate extent (one point) keeps the
     * current zoom and centres on it.
     */
    fun fit(bounds: SceneBounds?, viewWidth: Double, viewHeight: Double, paddingPx: Double): SketchViewport {
        if (bounds == null) return SketchViewport(IDENTITY_ZOOM, 0.0, 0.0, limits)
        val availW = max(viewWidth - paddingPx * HALF, MIN_AREA)
        val availH = max(viewHeight - paddingPx * HALF, MIN_AREA)
        val z = when {
            bounds.width > 0.0 && bounds.height > 0.0 -> min(availW / bounds.width, availH / bounds.height)
            bounds.width > 0.0 -> availW / bounds.width
            bounds.height > 0.0 -> availH / bounds.height
            else -> zoom
        }.coerceIn(minZoom, maxZoom)
        val cx = (bounds.minX + bounds.maxX) / HALF
        val cy = (bounds.minY + bounds.maxY) / HALF
        return SketchViewport(z, viewWidth / HALF - cx * z, viewHeight / HALF - cy * z, limits)
    }

    override fun equals(other: Any?): Boolean =
        other is SketchViewport && other.zoom == zoom && other.panX == panX && other.panY == panY

    override fun hashCode(): Int = 31 * (31 * zoom.hashCode() + panX.hashCode()) + panY.hashCode()

    override fun toString(): String = "SketchViewport(zoom=$zoom, pan=($panX, $panY))"

    private fun unit(v: Double): SceneUnit =
        SceneUnit.of(v.coerceIn(-limits.maxCoord, limits.maxCoord), limits).getOrNull() ?: SceneUnit.ZERO

    companion object {
        private const val PERCENT = 100.0
        private const val HALF = 2.0
        private const val IDENTITY_ZOOM = 1.0
        private const val MIN_AREA = 1.0

        fun of(zoom: Double = IDENTITY_ZOOM, panX: Double = 0.0, panY: Double = 0.0, limits: SceneLimits = SceneLimits.Default): SketchViewport {
            val z = if (zoom.isFinite() && zoom > 0.0) zoom else IDENTITY_ZOOM
            return SketchViewport(
                z.coerceIn(limits.minZoomPercent / PERCENT, limits.maxZoomPercent / PERCENT),
                if (panX.isFinite()) panX else 0.0,
                if (panY.isFinite()) panY else 0.0,
                limits,
            )
        }
    }
}
