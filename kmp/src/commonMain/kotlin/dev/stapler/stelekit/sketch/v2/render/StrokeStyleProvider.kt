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
    /** A hatch fill to generate beside the stroke, drawing from the same random sequence (rough.js order); null for none. */
    val fill: FillRequest? = null,
)

/** A restyled stroke and, when the request asked for one and the style can draw it, the hatch [fill] (null otherwise). */
data class StyledStroke(val outline: List<PathSegment>, val fill: List<PathSegment>?)

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

    /** [outline] plus the hatch fill of [StrokeRequest.fill]; the default draws no hatch. */
    fun stroke(request: StrokeRequest, clean: List<PathSegment>): StyledStroke = StyledStroke(outline(request, clean), null)

    companion object {
        fun forStyle(style: RenderStyle): StrokeStyleProvider = when (style) {
            RenderStyle.Clean -> CleanStrokeStyle
            RenderStyle.Sketchy -> SketchyStrokeStyle
        }

        /** [forStyle] after the target's Sketchy availability: a target that cannot draw Sketchy gets Clean (Story 2.2.3). */
        fun forTarget(style: RenderStyle, availability: SketchyAvailability, target: SketchTarget): StrokeStyleProvider =
            forStyle(availability.effective(style, target))
    }
}

/** Identity geometry: the Clean outline, untouched. */
object CleanStrokeStyle : StrokeStyleProvider {
    override val style: RenderStyle = RenderStyle.Clean

    override fun outline(request: StrokeRequest, clean: List<PathSegment>): List<PathSegment> = clean
}
