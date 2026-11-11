// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Smallest scale: the image fitted to the canvas. */
const val MIN_VIEW_SCALE: Float = 1f

/** Largest scale. */
const val MAX_VIEW_SCALE: Float = 8f

/** Scale a double-tap zooms to from the fitted view. */
const val DOUBLE_TAP_SCALE: Float = 4f

/** Factor of one zoom-in or zoom-out button press. */
const val ZOOM_STEP_FACTOR: Float = 1.5f

private const val FIT_EPSILON = 1e-3f
private const val WHEEL_BASE = 1.1f
private const val MAX_LOUPE_ZOOM = 16f
private const val MIN_LOUPE_ZOOM = 3f
private const val LOUPE_ZOOM_PER_VIEW_SCALE = 2f

/**
 * Where the image sits on screen. "Canvas" is the fitted image box that all annotation layers are
 * laid out in; "local" is that box's pixel space (origin at its top-left, points may fall outside it
 * once zoomed). The viewport is the clipped area the canvas lives in, positioned at
 * ([canvasLeft], [canvasTop]) inside it. [slack] is extra pan room past the image edge so a vertex
 * on the edge is not stuck under the screen border.
 */
data class ViewGeometry(
    val canvasWidth: Float,
    val canvasHeight: Float,
    val canvasLeft: Float,
    val canvasTop: Float,
    val viewportWidth: Float,
    val viewportHeight: Float,
    val slack: Float = 0f,
) {
    /** The viewport centre in local coordinates. */
    val viewportCenter: Offset
        get() = Offset(viewportWidth / 2f - canvasLeft, viewportHeight / 2f - canvasTop)
}

/**
 * The view onto the image: `local = canvas * scale + offset`, with `scale` in
 * [MIN_VIEW_SCALE]..[MAX_VIEW_SCALE]. A pure view concern. Annotation geometry stays in normalised
 * image coordinates and is never persisted or exported through this.
 */
data class AnnotationViewTransform(
    val scale: Float = MIN_VIEW_SCALE,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    val isFit: Boolean get() = scale <= MIN_VIEW_SCALE + FIT_EPSILON

    /** Canvas-pixel point to local screen position. */
    fun toView(canvas: Offset): Offset = Offset(canvas.x * scale + offsetX, canvas.y * scale + offsetY)

    /** Local screen position to canvas-pixel point. */
    fun toCanvas(view: Offset): Offset = Offset((view.x - offsetX) / scale, (view.y - offsetY) / scale)

    /** Moves the image by [delta] screen pixels, clamped so it cannot leave the viewport. */
    fun panned(delta: Offset, geometry: ViewGeometry): AnnotationViewTransform =
        copy(offsetX = offsetX + delta.x, offsetY = offsetY + delta.y).clamped(geometry)

    /** Zooms to [newScale] keeping the image point under [anchor] (local) fixed, subject to clamping. */
    fun zoomedTo(newScale: Float, anchor: Offset, geometry: ViewGeometry): AnnotationViewTransform =
        transformed(anchor, Offset.Zero, newScale / scale, geometry)

    /** One pinch/pan step: scale by [zoom] about [centroid] (local) while the centroid moves by [pan]. */
    fun transformed(centroid: Offset, pan: Offset, zoom: Float, geometry: ViewGeometry): AnnotationViewTransform {
        val newScale = (scale * zoom).coerceIn(MIN_VIEW_SCALE, MAX_VIEW_SCALE)
        val ratio = newScale / scale
        return AnnotationViewTransform(
            scale = newScale,
            offsetX = centroid.x - (centroid.x - offsetX) * ratio + pan.x,
            offsetY = centroid.y - (centroid.y - offsetY) * ratio + pan.y,
        ).clamped(geometry)
    }

    /** Double-tap: back to fit when zoomed, otherwise [DOUBLE_TAP_SCALE] about [anchor] (local). */
    fun toggledAt(anchor: Offset, geometry: ViewGeometry): AnnotationViewTransform =
        if (isFit) zoomedTo(DOUBLE_TAP_SCALE, anchor, geometry) else Fit

    /** Zoom button: scales by [factor] about the viewport centre. */
    fun stepped(factor: Float, geometry: ViewGeometry): AnnotationViewTransform =
        zoomedTo(scale * factor, geometry.viewportCenter, geometry)

    /** Pans the minimum needed to bring canvas point [canvas] at least [margin] px inside the viewport. */
    fun revealing(canvas: Offset, margin: Float, geometry: ViewGeometry): AnnotationViewTransform {
        val p = toView(canvas)
        val dx = revealDelta(p.x, -geometry.canvasLeft, geometry.viewportWidth - geometry.canvasLeft, margin)
        val dy = revealDelta(p.y, -geometry.canvasTop, geometry.viewportHeight - geometry.canvasTop, margin)
        return if (dx == 0f && dy == 0f) this else panned(Offset(dx, dy), geometry)
    }

    /**
     * Pulls the offset into the legal range. At fit the transform is the identity. When the zoomed
     * image is smaller than the viewport on an axis it is centred on that axis; otherwise it can
     * travel until its far edge is [ViewGeometry.slack] short of the viewport edge.
     */
    fun clamped(geometry: ViewGeometry): AnnotationViewTransform {
        val s = scale.coerceIn(MIN_VIEW_SCALE, MAX_VIEW_SCALE)
        if (s <= MIN_VIEW_SCALE + FIT_EPSILON) return Fit
        return AnnotationViewTransform(
            scale = s,
            offsetX = clampAxis(offsetX, s, AxisExtent(geometry.canvasWidth, geometry.canvasLeft, geometry.viewportWidth, geometry.slack)),
            offsetY = clampAxis(offsetY, s, AxisExtent(geometry.canvasHeight, geometry.canvasTop, geometry.viewportHeight, geometry.slack)),
        )
    }

    companion object {
        val Fit = AnnotationViewTransform()
    }
}

private data class AxisExtent(val canvas: Float, val canvasStart: Float, val viewport: Float, val slack: Float)

private fun clampAxis(offset: Float, scale: Float, axis: AxisExtent): Float {
    val content = axis.canvas * scale
    if (content <= axis.viewport) return (axis.viewport - content) / 2f - axis.canvasStart
    val left = (axis.canvasStart + offset).coerceIn(axis.viewport - content - axis.slack, axis.slack)
    return left - axis.canvasStart
}

private fun revealDelta(position: Float, low: Float, high: Float, margin: Float): Float {
    val lo = min(low + margin, (low + high) / 2f)
    val hi = max(high - margin, (low + high) / 2f)
    return when {
        position < lo -> lo - position
        position > hi -> hi - position
        else -> 0f
    }
}

/** Who owns a touch gesture. */
enum class GestureOwner { TOOL, VIEW }

/**
 * Two or more pressed pointers hand the gesture to the view (pinch/pan) and it stays there until every
 * pointer lifts, so lifting one finger of a pinch can never resume a tool placement or drag.
 */
fun gestureOwner(previous: GestureOwner, pressedPointers: Int): GestureOwner =
    if (previous == GestureOwner.VIEW || pressedPointers >= 2) GestureOwner.VIEW else GestureOwner.TOOL

/** A finished tap: when the pointer lifted and where. */
data class TapRecord(val upMillis: Long, val position: Offset)

/** Whether [tap] follows [previous] within [windowMillis] and [slopPx], i.e. is the second tap of a double tap. */
fun isDoubleTap(previous: TapRecord?, tap: TapRecord, windowMillis: Long, slopPx: Float): Boolean {
    if (previous == null) return false
    return tap.upMillis - previous.upMillis in 0..windowMillis && (tap.position - previous.position).getDistance() <= slopPx
}

/** Zoom factor for a mouse-wheel/trackpad scroll of [scrollDeltaY] (negative scrolls up = zoom in). */
fun wheelZoomFactor(scrollDeltaY: Float): Float = WHEEL_BASE.pow(-scrollDeltaY)

/** Loupe magnification: at least 3x the canvas, and more when the view is already zoomed so it still adds detail. */
fun loupeZoomFactor(viewScale: Float): Float =
    max(MIN_LOUPE_ZOOM, viewScale * LOUPE_ZOOM_PER_VIEW_SCALE).coerceAtMost(MAX_LOUPE_ZOOM)

/** True if this and [other] are the same view to within rendering tolerance. */
fun AnnotationViewTransform.approximatelyEquals(other: AnnotationViewTransform, tolerance: Float = 1e-3f): Boolean =
    abs(scale - other.scale) <= tolerance && abs(offsetX - other.offsetX) <= tolerance && abs(offsetY - other.offsetY) <= tolerance
