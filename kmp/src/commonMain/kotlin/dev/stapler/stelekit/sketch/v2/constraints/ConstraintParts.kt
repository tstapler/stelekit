// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.constraints

import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement

/** What an operand acts as in a constraint: one point, one segment, or one circle or arc. */
enum class OperandRole { Point, Segment, Curve }

/** An element together with the part of it a constraint operand names. */
data class PartRef(val element: SceneElement, val part: TargetPart)

/**
 * The ONE table of which parts an element has and which operands a constraint accepts (Task 4.2.6a). The picker, the
 * command layer and the adapter's tests ask it; `SceneToProblem` resolves the same parts, and a test keeps the two in
 * step. Parts per kind: line or polyline Start, End, interior Vertex(i), Edge(i) (a closed line also wraps);
 * rectangle Vertex(0..3) and Edge(0..3); circle Centre and Whole (the circumference); arc Centre, ArcStart, ArcEnd and
 * Whole; point Whole. A [TargetPart.Whole] operand of a line or rectangle stays accepted wherever a segment is (its
 * first one), so constraints stored before named parts keep their meaning.
 */
object ConstraintParts {
    /** The parts to offer for [element] in a picker: each target once, in the fixed order Start, End, vertices, edges, centre. */
    fun partsOf(element: SceneElement): List<TargetPart> = when (element) {
        is PointElement -> listOf(TargetPart.Whole)
        is LineElement -> linePartsOf(element)
        is RectElement -> List(RECT_CORNERS) { TargetPart.Vertex(it) } + List(RECT_CORNERS) { TargetPart.Edge(it) }
        is EllipseElement -> if (element.isCircle) listOf(TargetPart.Centre, TargetPart.Whole) else emptyList()
        is ArcElement -> listOf(TargetPart.ArcStart, TargetPart.ArcEnd, TargetPart.Centre, TargetPart.Whole)
        else -> emptyList()
    }

    private fun linePartsOf(line: LineElement): List<TargetPart> {
        val n = line.points.size
        if (n < MIN_LINE_POINTS) return emptyList()
        val edges = if (line.polygon) n else n - 1
        return listOf(TargetPart.Start, TargetPart.End) + (1 until n - 1).map { TargetPart.Vertex(it) } + (0 until edges).map { TargetPart.Edge(it) }
    }

    /** What [part] of [element] acts as, or null when the element has no such part. */
    fun roleOf(element: SceneElement, part: TargetPart): OperandRole? = when (element) {
        is PointElement -> if (part == TargetPart.Whole) OperandRole.Point else null
        is LineElement -> linearRole(part, element.points.size, if (element.polygon) element.points.size else element.points.size - 1)
        is RectElement -> linearRole(part, RECT_CORNERS, RECT_CORNERS)
        is EllipseElement -> if (element.isCircle) curveRole(part, emptySet()) else null
        is ArcElement -> curveRole(part, ARC_ENDS)
        else -> null
    }

    private fun linearRole(part: TargetPart, points: Int, edges: Int): OperandRole? = when {
        points < MIN_LINE_POINTS -> null
        part == TargetPart.Whole || (part is TargetPart.Edge && part.index in 0 until edges) -> OperandRole.Segment
        part is TargetPart.Vertex && part.index in 0 until points -> OperandRole.Point
        part == TargetPart.Start || part == TargetPart.End -> OperandRole.Point
        else -> null
    }

    private fun curveRole(part: TargetPart, ends: Set<TargetPart>): OperandRole? = when (part) {
        TargetPart.Whole -> OperandRole.Curve
        TargetPart.Centre -> OperandRole.Point
        in ends -> OperandRole.Point
        else -> null
    }

    /** True when [operands] are a complete, acceptable operand list for [kind]. */
    fun accepts(kind: ConstraintKind, operands: List<PartRef>): Boolean = resolve(operands)?.let { complete(kind, it) } ?: false

    /**
     * The parts of [element] that may be picked next for [kind] after [chosen] (the armed picker's markers): those that
     * keep some complete operand list reachable. Parts a constraint cannot use are absent, never merely disabled.
     */
    fun candidates(kind: ConstraintKind, chosen: List<PartRef>, element: SceneElement): List<TargetPart> {
        val prefix = resolve(chosen) ?: return emptyList()
        return partsOf(element).filter { part ->
            val op = resolve(listOf(PartRef(element, part)))?.single() ?: return@filter false
            val next = prefix + op
            next.size <= Constraint.arityOf(kind).max && reachable(kind, next)
        }
    }

    private class Op(val role: OperandRole, val whole: Boolean)

    private fun resolve(operands: List<PartRef>): List<Op>? =
        operands.map { ref -> Op(roleOf(ref.element, ref.part) ?: return null, ref.part == TargetPart.Whole) }

    private fun reachable(kind: ConstraintKind, prefix: List<Op>): Boolean {
        if (prefix.size in Constraint.arityOf(kind) && complete(kind, prefix)) return true
        if (prefix.size >= Constraint.arityOf(kind).max) return false
        return EXTENSIONS.any { reachable(kind, prefix + it) }
    }

    private val EXTENSIONS = OperandRole.entries.flatMap { role -> listOf(Op(role, false), Op(role, true)) }

    private fun complete(kind: ConstraintKind, ops: List<Op>): Boolean {
        if (ops.size !in Constraint.arityOf(kind)) return false
        val roles = ops.map { it.role }
        val point = OperandRole.Point
        val segment = OperandRole.Segment
        val curve = OperandRole.Curve
        return when (kind) {
            ConstraintKind.Coincident -> coincident(ops)
            ConstraintKind.Fixed -> true
            ConstraintKind.Horizontal, ConstraintKind.Vertical, ConstraintKind.Distance ->
                roles == listOf(segment) || roles == listOf(point, point)
            ConstraintKind.Parallel, ConstraintKind.Perpendicular, ConstraintKind.Angle -> roles == listOf(segment, segment)
            ConstraintKind.Equal -> roles == listOf(segment, segment) || roles == listOf(curve, curve)
            ConstraintKind.Tangent -> roles.count { it == curve } >= 1 && roles.all { it != point }
            ConstraintKind.Symmetric -> roles == listOf(point, point, segment)
            ConstraintKind.Radius, ConstraintKind.Diameter -> roles == listOf(curve)
        }
    }

    /** Two points; a point on a segment or curve; or two whole non-point elements, which meet at their primary points. */
    private fun coincident(ops: List<Op>): Boolean {
        val (a, b) = ops
        val bothPoints = a.role == OperandRole.Point && b.role == OperandRole.Point
        val onObject = (a.role == OperandRole.Point) != (b.role == OperandRole.Point)
        val legacyWhole = a.whole && b.whole && a.role != OperandRole.Point && b.role != OperandRole.Point
        return bothPoints || onObject || legacyWhole
    }

    private const val RECT_CORNERS = 4
    private const val MIN_LINE_POINTS = 2
    private val ARC_ENDS: Set<TargetPart> = setOf(TargetPart.ArcStart, TargetPart.ArcEnd)
}
