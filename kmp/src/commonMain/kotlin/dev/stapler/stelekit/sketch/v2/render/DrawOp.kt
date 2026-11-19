// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.render

import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ImageCrop
import dev.stapler.stelekit.sketch.v2.scene.TextAlign

/** One path instruction in scene space. */
sealed interface PathSegment {
    data class MoveTo(val x: Double, val y: Double) : PathSegment
    data class LineTo(val x: Double, val y: Double) : PathSegment
    data class CubicTo(val c1x: Double, val c1y: Double, val c2x: Double, val c2y: Double, val x: Double, val y: Double) : PathSegment
    data object Close : PathSegment
}

/** Stroke of a path; an empty [dash] is a solid line. */
data class StrokePaint(val color: String, val width: Double, val dash: List<Double> = emptyList())

data class DrawRect(val x: Double, val y: Double, val width: Double, val height: Double)

/** A text line: [x] is the anchor (left, centre or right edge per the run's alignment), [y] the top of the line box. */
data class TextLine(val text: String, val x: Double, val y: Double)

/**
 * One render instruction in scene space (ADR-004). Opacity is already the effective value (element x layer).
 * Paths carry any element rotation baked into their points; text and image ops carry an angle and a pivot instead.
 */
sealed interface DrawOp {
    data class PathOp(
        val elementId: ElementId,
        val segments: List<PathSegment>,
        val stroke: StrokePaint?,
        val fill: String?,
        val opacity: Double,
    ) : DrawOp

    data class TextRun(
        val elementId: ElementId,
        val lines: List<TextLine>,
        val fontFamily: Int,
        val fontSize: Double,
        val color: String,
        val align: TextAlign,
        val angle: Double,
        val pivotX: Double,
        val pivotY: Double,
        /** True when a glyph is outside the bundled font, so the painter must use the platform fallback (policy b). */
        val usesFallbackFont: Boolean,
        val opacity: Double,
    ) : DrawOp

    data class ImageOp(
        val elementId: ElementId,
        val fileId: String?,
        val rect: DrawRect,
        val angle: Double,
        val flipX: Boolean,
        val flipY: Boolean,
        val crop: ImageCrop?,
        val opacity: Double,
    ) : DrawOp

    /** Children are painted only inside [rect] (a frame's box). */
    data class ClipOp(val rect: DrawRect, val ops: List<DrawOp>) : DrawOp
}

/**
 * Geometry strategy of a draw list (ADR-004). Sketchy restyles an element only when its `roughness` is above 0;
 * at 0 the element is drawn Clean, byte for byte.
 */
enum class RenderStyle { Clean, Sketchy }
