// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.constraints

import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.SequenceCommand
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.hit.PartHitTester
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** The kinds of sheet that decide the auto-constrain default (D-2): a free-form diagram, a scaled or technical sheet, or one converted from v1. */
enum class SheetKind { Diagram, Technical, Converted }

/** The one rule for the per-sheet auto-constrain default (Story 4.2.2, UX-82): OFF on a diagram, ON on a technical or converted sheet. */
object AutoConstrainDefault {
    fun enabledFor(kind: SheetKind): Boolean = kind != SheetKind.Diagram
}

/**
 * What auto-constrain may do for one drawn shape. [enabled] is the sheet's setting; [suppressed] is Ctrl held on release or the
 * one-shot "Free draw" chip (Story 4.2.2c), which turns inference off for this shape only.
 */
data class AutoConstrainOptions(val enabled: Boolean, val suppressed: Boolean = false) {
    val active: Boolean get() = enabled && !suppressed
}

/**
 * A drawn element together with the constraints inferred for it. [command] adds the element and every constraint as ONE
 * command, so the editor commits them in one transaction (one undo step); [asPlain] is the same drawing without the
 * constraints, the fallback when the solver refuses the inferred set.
 */
data class AutoConstrainPlan(val element: SceneElement, val constraints: List<Constraint>) {
    val command: Command
        get() = if (constraints.isEmpty()) AddElement(element) else SequenceCommand(listOf<Command>(AddElement(element)) + constraints.map(::AddConstraint))

    val asPlain: AutoConstrainPlan get() = AutoConstrainPlan(element, emptyList())

    val kinds: List<ConstraintKind> get() = constraints.map { it.kind }
}

/**
 * Snap-inferred constraints for a freshly drawn element (Story 4.2.2, Task 4.2.2a, UX-39). Pure: it reads the document
 * and the drawn element and returns constraints; the editor commits them through `SolveRunner`. Only elements
 * `ConstraintEligibility` accepts are considered, never text or arrows.
 *
 * Rules: a two-point (or longer) line whose end lies on an existing endpoint, vertex, centre or point gets a Coincident
 * there; a line within [ANGLE_TOLERANCE_DEGREES] of horizontal or vertical gets Horizontal or Vertical; a point element
 * placed on an existing point part gets a Coincident. At most `SceneLimits.maxConstraints` constraints exist after the add.
 */
object AutoConstrain {
    /** A line this close to an axis (in degrees) is taken to be meant as horizontal or vertical. */
    const val ANGLE_TOLERANCE_DEGREES = 2.0

    /** How near (in scene units) a drawn end must lie to an existing point part to count as snapped onto it. */
    const val ENDPOINT_TOLERANCE = 0.25

    private const val STRAIGHT_ANGLE_DEGREES = 180.0
    private const val QUARTER_TURN_DEGREES = 90.0
    private const val MIN_LINE_POINTS = 2

    fun plan(doc: SketchDocumentV2, drawn: SceneElement, options: AutoConstrainOptions, newId: () -> ConstraintId): AutoConstrainPlan {
        if (!options.active || !ConstraintEligibility.isConstrainable(ElementKind.of(drawn))) return AutoConstrainPlan(drawn, emptyList())
        val room = doc.scene.limits.maxConstraints - doc.constraints.size
        val wanted = coincidences(doc, drawn, newId) + orientation(drawn, newId)
        return AutoConstrainPlan(drawn, wanted.take(room.coerceAtLeast(0)))
    }

    private fun coincidences(doc: SketchDocumentV2, drawn: SceneElement, newId: () -> ConstraintId): List<Constraint> {
        val ends = drawnEnds(drawn)
        val found = ArrayList<Constraint>()
        for ((part, at) in ends) {
            val hit = nearestPointPart(doc, drawn, at) ?: continue
            val operands = listOf(ConstraintTarget(drawn.id, part), ConstraintTarget(hit.first, hit.second))
            found += Constraint(newId(), ConstraintKind.Coincident, operands)
        }
        return found
    }

    /** The parts of [drawn] that may have snapped onto something: a line's two ends, or a point element. */
    private fun drawnEnds(drawn: SceneElement): List<Pair<TargetPart, Pair<Double, Double>>> = when (drawn) {
        is LineElement ->
            if (drawn.polygon || drawn.points.size < MIN_LINE_POINTS) {
                emptyList()
            } else {
                listOf(
                    TargetPart.Start to (drawn.points.first().x.value to drawn.points.first().y.value),
                    TargetPart.End to (drawn.points.last().x.value to drawn.points.last().y.value),
                )
            }
        is PointElement -> listOf(TargetPart.Whole to (drawn.x.value to drawn.y.value))
        else -> emptyList()
    }

    /** The closest point part of another eligible element within [ENDPOINT_TOLERANCE] of [at], or null; the older element wins a tie. */
    private fun nearestPointPart(doc: SketchDocumentV2, drawn: SceneElement, at: Pair<Double, Double>): Pair<ElementId, TargetPart>? {
        var best: Pair<ElementId, TargetPart>? = null
        var bestDistance = ENDPOINT_TOLERANCE
        for (other in doc.scene.elementsInOrder) {
            if (other.id == drawn.id || !ConstraintEligibility.isConstrainable(ElementKind.of(other))) continue
            for (part in ConstraintParts.partsOf(other)) {
                if (ConstraintParts.roleOf(other, part) != OperandRole.Point) continue
                val p = PartPoints.pointOf(other, part) ?: continue
                val d = hypot(p.first - at.first, p.second - at.second)
                if (d <= bestDistance && (best == null || d < bestDistance)) {
                    best = other.id to part
                    bestDistance = d
                }
            }
        }
        return best
    }

    private fun orientation(drawn: SceneElement, newId: () -> ConstraintId): List<Constraint> {
        val line = drawn as? LineElement ?: return emptyList()
        if (line.polygon || line.points.size != MIN_LINE_POINTS) return emptyList()
        val (a, b) = line.points
        val dx = b.x.value - a.x.value
        val dy = b.y.value - a.y.value
        if (hypot(dx, dy) == 0.0) return emptyList()
        val degrees = abs(atan2(dy, dx)) * STRAIGHT_ANGLE_DEGREES / PI
        val offHorizontal = minOf(degrees, STRAIGHT_ANGLE_DEGREES - degrees)
        val offVertical = abs(degrees - QUARTER_TURN_DEGREES)
        val kind = when {
            offHorizontal <= ANGLE_TOLERANCE_DEGREES -> ConstraintKind.Horizontal
            offVertical <= ANGLE_TOLERANCE_DEGREES -> ConstraintKind.Vertical
            else -> return emptyList()
        }
        return listOf(Constraint(newId(), kind, listOf(ConstraintTarget(line.id))))
    }
}

/** Where a named part of an element lies, in scene units; the one place the constraint code asks, so a test compares it with the hit tester. */
object PartPoints {
    fun pointOf(element: SceneElement, part: TargetPart): Pair<Double, Double>? = when (element) {
        is PointElement -> if (part == TargetPart.Whole) element.x.value to element.y.value else null
        is LineElement -> linePoint(element, part)
        is RectElement -> (part as? TargetPart.Vertex)?.let { PartHitTester.cornersOf(element).getOrNull(it.index) }
        is EllipseElement -> if (element.isCircle && part == TargetPart.Centre) element.cx.value to element.cy.value else null
        is ArcElement -> arcPoint(element, part)
        else -> null
    }

    private fun linePoint(line: LineElement, part: TargetPart): Pair<Double, Double>? {
        val index = when (part) {
            TargetPart.Start -> 0
            TargetPart.End -> line.points.size - 1
            is TargetPart.Vertex -> part.index
            else -> return null
        }
        return line.points.getOrNull(index)?.let { it.x.value to it.y.value }
    }

    private fun arcPoint(arc: ArcElement, part: TargetPart): Pair<Double, Double>? {
        val cx = arc.cx.value
        val cy = arc.cy.value
        val r = arc.radius.value
        return when (part) {
            TargetPart.Centre -> cx to cy
            TargetPart.ArcStart -> (cx + r * cos(arc.startAngle)) to (cy + r * sin(arc.startAngle))
            TargetPart.ArcEnd -> (cx + r * cos(arc.endAngle)) to (cy + r * sin(arc.endAngle))
            else -> null
        }
    }
}
