// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.render

/** The geometry a style may redraw, in scene space BEFORE any element rotation (except [Outline], which is already rotated). */
sealed interface StrokeGeometry {
    /** Straight edges through [points]; [closed] adds the edge back to the first point. */
    data class Polyline(val points: List<ScenePt>, val closed: Boolean) : StrokeGeometry

    /** A smooth line through [points] (Excalidraw's rounded lines). */
    data class Curve(val points: List<ScenePt>) : StrokeGeometry

    data class Ellipse(val cx: Double, val cy: Double, val rx: Double, val ry: Double) : StrokeGeometry

    /** Any other path (rounded boxes, arrowheads); already rotated, so [StrokeRequest.angle] is 0. */
    data object Outline : StrokeGeometry
}

data class ScenePt(val x: Double, val y: Double)

/** One stroke to restyle: [seed] and [roughness] come from the element, [angle] is rotated about ([pivotX], [pivotY]) after generation. */
data class StrokeRequest(
    val geometry: StrokeGeometry,
    val seed: Int,
    val roughness: Int,
    val angle: Double = 0.0,
    val pivotX: Double = 0.0,
    val pivotY: Double = 0.0,
)

/**
 * Geometry strategy of a draw list (ADR-004): turns the Clean outline of an element into the outline the style draws.
 * Implementations are pure and thread-safe.
 */
interface StrokeStyleProvider {
    val style: RenderStyle

    /**
     * The segments to stroke for [request]. [clean] is the Clean outline of the same shape (already rotated); a
     * provider that does not change the geometry returns [clean] itself, which tells the caller nothing was restyled.
     */
    fun outline(request: StrokeRequest, clean: List<PathSegment>): List<PathSegment>

    companion object {
        fun forStyle(style: RenderStyle): StrokeStyleProvider = when (style) {
            RenderStyle.Clean -> CleanStrokeStyle
            RenderStyle.Sketchy -> SketchyStrokeStyle
        }
    }
}

/** Identity geometry: the Clean outline, untouched. */
object CleanStrokeStyle : StrokeStyleProvider {
    override val style: RenderStyle = RenderStyle.Clean

    override fun outline(request: StrokeRequest, clean: List<PathSegment>): List<PathSegment> = clean
}
