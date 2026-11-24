// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.constraints

import dev.stapler.stelekit.sketch.constraints.SolverTier
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import dev.stapler.stelekit.sketch.v2.scene.OpeningElement
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.TextElement

/** The element kinds the constraint rules distinguish; an ellipse is split into circle and unequal ellipse (OD-8). */
enum class ElementKind {
    Line, Rect, Circle, Ellipse, Arc, Point,
    Diamond, Arrow, Freedraw, Text, Image, Frame, Wall, Opening;

    companion object {
        fun of(element: SceneElement): ElementKind = when (element) {
            is LineElement -> Line
            is RectElement -> Rect
            is EllipseElement -> if (element.isCircle) Circle else Ellipse
            is ArcElement -> Arc
            is PointElement -> Point
            is DiamondElement -> Diamond
            is ArrowElement -> Arrow
            is FreedrawElement -> Freedraw
            is TextElement -> Text
            is ImageElement -> Image
            is FrameElement -> Frame
            is WallElement -> Wall
            is OpeningElement -> Opening
        }
    }
}

/**
 * The constraint kinds a user can ask for. Sketch-specific model types arrive with Task 4.2.1a; this enum is only
 * the vocabulary of the eligibility table.
 */
enum class ConstraintKind {
    Coincident, Horizontal, Vertical, Parallel, Perpendicular, Equal, Tangent, Symmetric, Fixed,
    Distance, Angle, Radius, Diameter,

    /** Signed horizontal and vertical distance between two points (the solver's `DistanceX` and `DistanceY`). */
    DistanceX, DistanceY,
}

/** Whether a constraint kind can apply to an element kind, and if not, the plain-language reason. */
sealed interface Eligibility {
    data object Eligible : Eligibility
    data class Ineligible(val reason: String) : Eligibility
}

/** User-visible reason strings of the constraint rules. The one source of the wording; a grep test forbids a second. */
object SketchReasonText {
    const val NotConstrainable = "Text, arrows, images, freehand and frames can't be constrained. Use align and distribute instead."
    const val OnlyCircles = "Only circles can be constrained"
    const val RadiusNeedsCircle = "Radius needs a circle or arc"
    const val DiameterNeedsCircle = "Diameter needs a circle or arc"
    const val ParallelNeedsLines = "Parallel needs two lines"
    const val PerpendicularNeedsLines = "Perpendicular needs two lines"
    const val AngleNeedsLines = "Angle needs two lines"
    const val OrientationNeedsLineOrPoints = "Horizontal and Vertical need a line or two points"
    const val EqualNeedsMatchingShapes = "Equal needs two lines, or two circles or arcs"
    const val TangentNeedsCurve = "Tangent needs a circle or arc and one more element"
    const val SymmetricNeedsPointsOrLine = "Symmetric needs two points and a line"
    const val SelectAnElement = "Select an element to constrain"
    const val CoincidentNeedsTwo = "Coincident needs two elements"
    const val DistanceNeedsMeasure = "Distance needs a line or rectangle, or two points"
    const val DistanceAxisNeedsPoints = "Horizontal and vertical distance need two points"
    const val ReferenceNeedsDimension = "Reference needs a dimension on the selection"

    /** The diagnostics entry written when `CommandError.ConstraintCapExceeded` is returned (Story 4.2.1). */
    fun constraintLimitReached(limit: Int) = "Constraint limit reached ($limit)"
}

/**
 * The ONE rule for what can be constrained (Task 4.2.1d, UX-81, D-2): technical geometry only. The Constrain flyout, the
 * auto-constrain engine, the importer and the command layer all ask this table; none re-implements it.
 */
object ConstraintEligibility {
    private val technical = setOf(ElementKind.Line, ElementKind.Rect, ElementKind.Circle, ElementKind.Arc, ElementKind.Point)

    fun isConstrainable(kind: ElementKind): Boolean = kind in technical

    /** [Eligibility.Eligible] when [constraint] can involve an element of [kind], else the reason to show. */
    fun reason(kind: ElementKind, constraint: ConstraintKind): Eligibility {
        if (kind == ElementKind.Ellipse) return Eligibility.Ineligible(SketchReasonText.OnlyCircles)
        if (kind !in technical) return Eligibility.Ineligible(SketchReasonText.NotConstrainable)
        val needs = needs(constraint)
        return if (kind in needs.kinds) Eligibility.Eligible else Eligibility.Ineligible(needs.reason)
    }

    fun reason(element: SceneElement, constraint: ConstraintKind): Eligibility = reason(ElementKind.of(element), constraint)

    /** What to say when every selected element suits [constraint] on its own but together they do not make a complete operand list. */
    fun selectionReason(constraint: ConstraintKind): String = when (constraint) {
        ConstraintKind.Coincident -> SketchReasonText.CoincidentNeedsTwo
        ConstraintKind.Distance -> SketchReasonText.DistanceNeedsMeasure
        ConstraintKind.DistanceX, ConstraintKind.DistanceY -> SketchReasonText.DistanceAxisNeedsPoints
        else -> needs(constraint).reason
    }

    private class Need(val kinds: Set<ElementKind>, val reason: String)

    private val lines = setOf(ElementKind.Line, ElementKind.Rect)
    private val curves = setOf(ElementKind.Circle, ElementKind.Arc)

    private fun needs(constraint: ConstraintKind): Need = when (constraint) {
        ConstraintKind.Coincident, ConstraintKind.Fixed, ConstraintKind.Distance -> Need(technical, SketchReasonText.NotConstrainable)
        ConstraintKind.Horizontal, ConstraintKind.Vertical -> Need(lines + ElementKind.Point, SketchReasonText.OrientationNeedsLineOrPoints)
        ConstraintKind.Parallel -> Need(lines, SketchReasonText.ParallelNeedsLines)
        ConstraintKind.Perpendicular -> Need(lines, SketchReasonText.PerpendicularNeedsLines)
        ConstraintKind.Angle -> Need(lines, SketchReasonText.AngleNeedsLines)
        ConstraintKind.Equal -> Need(lines + curves, SketchReasonText.EqualNeedsMatchingShapes)
        ConstraintKind.Tangent -> Need(lines + curves, SketchReasonText.TangentNeedsCurve)
        ConstraintKind.Symmetric -> Need(lines + ElementKind.Point, SketchReasonText.SymmetricNeedsPointsOrLine)
        ConstraintKind.Radius -> Need(curves, SketchReasonText.RadiusNeedsCircle)
        ConstraintKind.Diameter -> Need(curves, SketchReasonText.DiameterNeedsCircle)
        ConstraintKind.DistanceX, ConstraintKind.DistanceY -> Need(technical, SketchReasonText.DistanceAxisNeedsPoints)
    }
}

/** Plain-language degrees-of-freedom wording (UX-83): a count is never shown without its gloss. */
object DofWording {
    const val Explanation = "A degree of freedom is one way the sketch can still change shape or move."

    /** The status line, e.g. "2 degrees of freedom" or "Fully constrained". */
    fun status(dof: Int): String = when {
        dof <= 0 -> "Fully constrained"
        dof == 1 -> "1 degree of freedom"
        else -> "$dof degrees of freedom"
    }

    /** The detail line that accompanies [status], e.g. "(2 moves still possible)" or "(nothing can move)". */
    fun detail(dof: Int): String = when {
        dof <= 0 -> "(nothing can move)"
        dof == 1 -> "(1 move still possible)"
        else -> "($dof moves still possible)"
    }

    fun statusWithDetail(dof: Int): String = status(dof) + " " + detail(dof)
}

/** Which constraint kinds a [SolverTier] exposes in the Constrain flyout (D-114; ADR-003 item 7). */
object SolverTierGate {
    private val geometricOnly = setOf(
        ConstraintKind.Coincident, ConstraintKind.Horizontal, ConstraintKind.Vertical, ConstraintKind.Equal, ConstraintKind.Distance,
    )

    fun exposes(tier: SolverTier, kind: ConstraintKind): Boolean = when (tier) {
        SolverTier.Full -> true
        SolverTier.GeometricOnly -> kind in geometricOnly
        SolverTier.Hidden -> false
    }
}
