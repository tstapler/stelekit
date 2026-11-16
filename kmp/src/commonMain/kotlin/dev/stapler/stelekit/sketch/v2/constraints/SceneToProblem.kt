// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.constraints

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.Raise
import arrow.core.raise.either
import dev.stapler.stelekit.sketch.constraints.ConstraintProblem
import dev.stapler.stelekit.sketch.constraints.SolveResult
import dev.stapler.stelekit.sketch.constraints.Solver
import dev.stapler.stelekit.sketch.constraints.constraints.AngleDimension
import dev.stapler.stelekit.sketch.constraints.constraints.AnglePolicy
import dev.stapler.stelekit.sketch.constraints.constraints.Coincident
import dev.stapler.stelekit.sketch.constraints.constraints.ConstraintEquation
import dev.stapler.stelekit.sketch.constraints.constraints.DiameterDimension
import dev.stapler.stelekit.sketch.constraints.constraints.DimensionalEquation
import dev.stapler.stelekit.sketch.constraints.constraints.DistanceDimension
import dev.stapler.stelekit.sketch.constraints.constraints.EqualLength
import dev.stapler.stelekit.sketch.constraints.constraints.EqualRadius
import dev.stapler.stelekit.sketch.constraints.constraints.FixedPoint
import dev.stapler.stelekit.sketch.constraints.constraints.FixedScalar
import dev.stapler.stelekit.sketch.constraints.constraints.Horizontal
import dev.stapler.stelekit.sketch.constraints.constraints.Parallel
import dev.stapler.stelekit.sketch.constraints.constraints.Perpendicular
import dev.stapler.stelekit.sketch.constraints.constraints.PointOnCircle
import dev.stapler.stelekit.sketch.constraints.constraints.PointOnLine
import dev.stapler.stelekit.sketch.constraints.constraints.RadiusDimension
import dev.stapler.stelekit.sketch.constraints.constraints.RadiusRef
import dev.stapler.stelekit.sketch.constraints.constraints.Symmetric
import dev.stapler.stelekit.sketch.constraints.constraints.TangentCircleCircle
import dev.stapler.stelekit.sketch.constraints.constraints.TangentLineCircle
import dev.stapler.stelekit.sketch.constraints.constraints.Vertical
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.sceneUnit
import dev.stapler.stelekit.sketch.v2.commands.touched
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.ValueError
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The solver problem of a document's constraints plus the way back to the scene (Task 4.2.1b, OD-8).
 *
 * Mapping: a [PointElement] is one point; a [LineElement] is its points (a polyline is consecutive segments); a
 * [RectElement] is its four corners (top-left, top-right, bottom-right, bottom-left) with implicit constraints that
 * keep it a rectangle (horizontal and vertical edges when axis-aligned, a parallelogram with a right angle when
 * rotated); an [EllipseElement] with equal radii is a centre and a radius variable; an [ArcElement] is centre, start
 * point and end point with the end point held on the start point's circle. Only elements a constraint names enter the
 * problem. Arrows, text, freedraw, images, diamonds and frames never do (`ConstraintEligibility`).
 *
 * A named [TargetPart] resolves exactly (an endpoint, a vertex, an edge, a centre). A [TargetPart.Whole] operand resolves
 * by what the constraint needs: a segment is a line's first segment or a rectangle's top edge; a point is a point
 * element; a curve is a circle or an arc; two Whole operands of Coincident meet at their primary points (a line's
 * start, a rectangle's top-left corner, a curve's centre).
 */
class SceneProblem internal constructor(
    val problem: ConstraintProblem,
    /** The scene's own values, in variable order; the start of every solve. */
    val start: DoubleArray,
    private val bindings: List<ElementBinding>,
    private val dimensions: Map<ConstraintId, DimensionalEquation>,
    private val limits: SceneLimits,
) {
    /** The value a dimensional constraint currently measures at [x]; null for a constraint that is not dimensional. */
    fun measure(id: ConstraintId, x: DoubleArray = start): Double? = dimensions[id]?.measure(x)

    /** Variable layout of the elements in the problem, in the order they were first named. */
    val elementIds: List<ElementId> get() = bindings.map { it.id }

    /** Writes the solved values [x] back into [scene]; an element whose variables did not change is left untouched. */
    fun apply(scene: SketchSceneV2, x: DoubleArray): Either<CommandError, SketchSceneV2> = either {
        var out = scene
        for (b in bindings) {
            if (!b.changed(start, x)) continue
            val current = out[b.id] ?: raise(CommandError.UnknownElement(b.id.value))
            val next = b.write(current, x, limits).bind()
            if (next != current) out = out.replace(next.touched()).bind()
        }
        out
    }

    /** Solves from [start]. The scene changes only when the result is usable (`Solved` or `Redundant`). */
    fun solve(scene: SketchSceneV2, solver: Solver = Solver()): Either<CommandError, SolvedScene> = either {
        val result = solver.solve(problem, start)
        val usable = result is SolveResult.Solved || result is SolveResult.Redundant
        SolvedScene(if (usable) apply(scene, result.x).bind() else scene, result)
    }

    companion object {
        /** Maps an equation id back to the constraint it came from; null for an implicit one (a rectangle's own shape). */
        fun constraintIdOf(equationId: String): ConstraintId? =
            if (equationId.startsWith(IMPLICIT_PREFIX)) null else ConstraintId(equationId.substringBefore(SUFFIX_SEPARATOR))

        internal const val IMPLICIT_PREFIX = "~"
        internal const val SUFFIX_SEPARATOR = '#'
    }
}

data class SolvedScene(val scene: SketchSceneV2, val result: SolveResult)

object SceneToProblem {
    /** Builds the problem of [constraints] over [scene]; the first constraint that cannot be built is the error. */
    fun build(scene: SketchSceneV2, constraints: List<Constraint>): Either<CommandError, SceneProblem> = either {
        val builder = Builder(scene)
        for (c in constraints) builder.add(c).bind()
        SceneProblem(
            ConstraintProblem(builder.equations, builder.values.size),
            builder.values.toDoubleArray(),
            builder.bindings.values.toList(),
            builder.dimensions,
            scene.limits,
        )
    }

    /** The value [constraint] measures on the current geometry (what a reference dimension shows). */
    fun measure(scene: SketchSceneV2, constraint: Constraint): Either<CommandError, Double> = either {
        val problem = build(scene, listOf(constraint.copy(reference = true))).bind()
        problem.measure(constraint.id) ?: raise(CommandError.InvalidConstraint(constraint.id.value, "reference"))
    }
}

private const val PAIR = 2
private const val FOUR_CORNERS = 4
private const val TWO_PI = 2.0 * PI
private const val OPERANDS = "operands"

/** One element's variables. [id] is the element. */
internal sealed class ElementBinding(val id: ElementId) {
    /** Index of the x variable of each point this element owns; a circle's radius scalar is not one. */
    abstract val pointStarts: IntArray
    val variables: IntArray get() = pointStarts.flatMap { listOf(it, it + 1) }.toIntArray() + extraScalars()

    protected open fun extraScalars(): IntArray = IntArray(0)

    fun changed(start: DoubleArray, x: DoubleArray): Boolean = variables.any { start[it] != x[it] }

    abstract fun write(current: SceneElement, x: DoubleArray, limits: SceneLimits): Either<CommandError, SceneElement>

    class PointB(id: ElementId, val p: Int) : ElementBinding(id) {
        override val pointStarts = intArrayOf(p)
        override fun write(current: SceneElement, x: DoubleArray, limits: SceneLimits): Either<CommandError, SceneElement> = either {
            (current as PointElement).copy(x = sceneUnit(x[p], limits).bind(), y = sceneUnit(x[p + 1], limits).bind())
        }
    }

    class LineB(id: ElementId, val points: IntArray, val closed: Boolean) : ElementBinding(id) {
        override val pointStarts = points
        override fun write(current: SceneElement, x: DoubleArray, limits: SceneLimits): Either<CommandError, SceneElement> = either {
            (current as LineElement).copy(points = points.map { ScenePoint(sceneUnit(x[it], limits).bind(), sceneUnit(x[it + 1], limits).bind()) })
        }
    }

    class RectB(id: ElementId, val corners: IntArray, val axisAligned: Boolean) : ElementBinding(id) {
        override val pointStarts = corners
        override fun write(current: SceneElement, x: DoubleArray, limits: SceneLimits): Either<CommandError, SceneElement> = either {
            val rect = current as RectElement
            val p0 = corners[0]
            val p1 = corners[1]
            val p2 = corners[2]
            val p3 = corners[PAIR + 1]
            if (axisAligned) {
                val left = min(x[p0], x[p2])
                val top = min(x[p0 + 1], x[p2 + 1])
                rect.copy(
                    x = sceneUnit(left, limits).bind(),
                    y = sceneUnit(top, limits).bind(),
                    width = sceneUnit(abs(x[p2] - x[p0]), limits).bind(),
                    height = sceneUnit(abs(x[p2 + 1] - x[p0 + 1]), limits).bind(),
                )
            } else {
                val cx = (x[p0] + x[p2]) / PAIR
                val cy = (x[p0 + 1] + x[p2 + 1]) / PAIR
                val w = hypot(x[p1] - x[p0], x[p1 + 1] - x[p0 + 1])
                val h = hypot(x[p3] - x[p0], x[p3 + 1] - x[p0 + 1])
                rect.copy(
                    x = sceneUnit(cx - w / PAIR, limits).bind(),
                    y = sceneUnit(cy - h / PAIR, limits).bind(),
                    width = sceneUnit(w, limits).bind(),
                    height = sceneUnit(h, limits).bind(),
                    angle = atan2(x[p1 + 1] - x[p0 + 1], x[p1] - x[p0]),
                )
            }
        }
    }

    class CircleB(id: ElementId, val center: Int, val r: Int) : ElementBinding(id) {
        override val pointStarts = intArrayOf(center)
        override fun extraScalars() = intArrayOf(r)
        override fun write(current: SceneElement, x: DoubleArray, limits: SceneLimits): Either<CommandError, SceneElement> = either {
            val radius = sceneUnit(x[r], limits).bind()
            if (radius.value <= 0.0) raise(CommandError.ValueRejected(ValueError.NotPositive(radius.value)))
            (current as EllipseElement).copy(cx = sceneUnit(x[center], limits).bind(), cy = sceneUnit(x[center + 1], limits).bind(), rx = radius, ry = radius)
        }
    }

    class ArcB(id: ElementId, val center: Int, val startPt: Int, val endPt: Int) : ElementBinding(id) {
        override val pointStarts = intArrayOf(center, startPt, endPt)
        override fun write(current: SceneElement, x: DoubleArray, limits: SceneLimits): Either<CommandError, SceneElement> = either {
            val arc = current as ArcElement
            val radius = sceneUnit(hypot(x[startPt] - x[center], x[startPt + 1] - x[center + 1]), limits).bind()
            if (radius.value <= 0.0) raise(CommandError.ValueRejected(ValueError.NotPositive(radius.value)))
            // Keep the original winding: the new angles are the nearest equivalents of the old ones.
            val sweep = arc.endAngle - arc.startAngle
            val rawStart = atan2(x[startPt + 1] - x[center + 1], x[startPt] - x[center])
            val newStart = rawStart - TWO_PI * round((rawStart - arc.startAngle) / TWO_PI)
            val rawEnd = atan2(x[endPt + 1] - x[center + 1], x[endPt] - x[center])
            val newEnd = rawEnd - TWO_PI * round((rawEnd - (newStart + sweep)) / TWO_PI)
            arc.copy(
                cx = sceneUnit(x[center], limits).bind(),
                cy = sceneUnit(x[center + 1], limits).bind(),
                radius = radius,
                startAngle = newStart,
                endAngle = newEnd,
            )
        }
    }
}

private fun hypot(dx: Double, dy: Double): Double = sqrt(dx * dx + dy * dy)

private class Curve(val center: Int, val radius: RadiusRef)

private class Segment(val a: Int, val b: Int)

// Operand views: how an element binding offers points, segments and curves to a constraint.

private fun ElementBinding.primaryPoint(): Int = when (this) {
    is ElementBinding.PointB -> p
    is ElementBinding.LineB -> points[0]
    is ElementBinding.RectB -> corners[0]
    is ElementBinding.CircleB -> center
    is ElementBinding.ArcB -> center
}

private fun ElementBinding.wholeSegment(): Segment? = when (this) {
    is ElementBinding.LineB -> Segment(points[0], points[1])
    is ElementBinding.RectB -> Segment(corners[0], corners[1])
    else -> null
}

private fun ElementBinding.wholeCurve(): Curve? = when (this) {
    is ElementBinding.CircleB -> Curve(center, RadiusRef.scalar(r))
    is ElementBinding.ArcB -> Curve(center, RadiusRef.arc(center, startPt))
    else -> null
}

/** The edge [i] of this element as a segment, or null when the element has no such edge. */
private fun ElementBinding.edge(i: Int): Segment? = when (this) {
    is ElementBinding.LineB -> {
        val last = if (closed) points.size else points.size - 1
        if (i in 0 until last) Segment(points[i], points[(i + 1) % points.size]) else null
    }
    is ElementBinding.RectB -> if (i in 0 until FOUR_CORNERS) Segment(corners[i], corners[(i + 1) % FOUR_CORNERS]) else null
    else -> null
}

private fun ElementBinding.vertex(i: Int): Int? = when (this) {
    is ElementBinding.LineB -> points.getOrNull(i)
    is ElementBinding.RectB -> corners.getOrNull(i)
    else -> null
}

private fun ElementBinding.namedPoint(part: TargetPart): Int? = when (part) {
    TargetPart.Start -> (this as? ElementBinding.LineB)?.points?.first()
    TargetPart.End -> (this as? ElementBinding.LineB)?.points?.last()
    is TargetPart.Vertex -> vertex(part.index)
    TargetPart.Centre -> when (this) {
        is ElementBinding.CircleB -> center
        is ElementBinding.ArcB -> center
        else -> null
    }
    TargetPart.ArcStart -> (this as? ElementBinding.ArcB)?.startPt
    TargetPart.ArcEnd -> (this as? ElementBinding.ArcB)?.endPt
    TargetPart.Whole, is TargetPart.Edge -> null
}

private class Builder(private val scene: SketchSceneV2) {
    val values = ArrayList<Double>()
    val bindings = LinkedHashMap<ElementId, ElementBinding>()
    val equations = ArrayList<ConstraintEquation>()
    val dimensions = LinkedHashMap<ConstraintId, DimensionalEquation>()

    private fun point(px: Double, py: Double): Int {
        val index = values.size
        values += px
        values += py
        return index
    }

    private fun scalar(v: Double): Int {
        values += v
        return values.size - 1
    }

    private fun implicit(id: ElementId, count: Int, eq: (String) -> ConstraintEquation) {
        equations += eq("${IMPLICIT}${id.value}${SEP}$count")
    }

    private fun Raise<CommandError>.bindingOf(cid: String, target: ConstraintTarget): ElementBinding {
        bindings[target.elementId]?.let { return it }
        val element = scene[target.elementId] ?: raise(CommandError.UnknownElement(target.elementId.value))
        val created: ElementBinding = when (element) {
            is PointElement -> ElementBinding.PointB(element.id, point(element.x.value, element.y.value))
            is LineElement -> {
                if (element.points.size < PAIR) raise(CommandError.InvalidConstraint(cid, "geometry"))
                ElementBinding.LineB(element.id, element.points.map { point(it.x.value, it.y.value) }.toIntArray(), element.polygon)
            }
            is RectElement -> rect(element)
            is EllipseElement -> {
                if (!element.isCircle) raise(CommandError.InvalidConstraint(cid, "ellipse"))
                ElementBinding.CircleB(element.id, point(element.cx.value, element.cy.value), scalar(element.rx.value))
            }
            is ArcElement -> arc(element)
            else -> raise(CommandError.NotConstrainable(ElementKind.of(element)))
        }
        bindings[element.id] = created
        return created
    }

    private fun rect(e: RectElement): ElementBinding.RectB {
        val x0 = e.x.value
        val y0 = e.y.value
        val x1 = x0 + e.width.value
        val y1 = y0 + e.height.value
        val raw = listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1)
        val rotated = e.angle != 0.0
        val cx = (x0 + x1) / PAIR
        val cy = (y0 + y1) / PAIR
        val pts = raw.map { (px, py) ->
            if (!rotated) px to py
            else (cx + (px - cx) * cos(e.angle) - (py - cy) * sin(e.angle)) to (cy + (px - cx) * sin(e.angle) + (py - cy) * cos(e.angle))
        }
        val c = IntArray(FOUR_CORNERS) { point(pts[it].first, pts[it].second) }
        var n = 0
        if (!rotated) {
            implicit(e.id, n++) { Horizontal(it, c[0], c[1]) }
            implicit(e.id, n++) { Vertical(it, c[1], c[2]) }
            implicit(e.id, n++) { Horizontal(it, c[PAIR], c[PAIR + 1]) }
            implicit(e.id, n++) { Vertical(it, c[PAIR + 1], c[0]) }
        } else {
            implicit(e.id, n++) { Perpendicular(it, c[0], c[1], c[1], c[2]) }
            implicit(e.id, n++) { Parallel(it, c[0], c[1], c[PAIR + 1], c[2]) }
            implicit(e.id, n) { Parallel(it, c[1], c[2], c[0], c[PAIR + 1]) }
        }
        return ElementBinding.RectB(e.id, c, axisAligned = !rotated)
    }

    private fun arc(e: ArcElement): ElementBinding.ArcB {
        val center = point(e.cx.value, e.cy.value)
        val r = e.radius.value
        val s = point(e.cx.value + r * cos(e.startAngle), e.cy.value + r * sin(e.startAngle))
        val end = point(e.cx.value + r * cos(e.endAngle), e.cy.value + r * sin(e.endAngle))
        implicit(e.id, 0) { PointOnCircle(it, end, center, RadiusRef.arc(center, s)) }
        return ElementBinding.ArcB(e.id, center, s, end)
    }

    /** One resolved operand; every constraint kind reads the view it needs and rejects the operand when it is missing. */
    private class Operand(
        val binding: ElementBinding,
        val part: TargetPart,
        val point: Int?,
        val segment: Segment?,
        val curve: Curve?,
    ) {
        val whole: Boolean get() = part == TargetPart.Whole
    }

    private fun Raise<CommandError>.operand(cid: String, target: ConstraintTarget): Operand {
        val b = bindingOf(cid, target)
        val part = target.part
        val resolved = when (part) {
            TargetPart.Whole -> Operand(b, part, (b as? ElementBinding.PointB)?.p, b.wholeSegment(), b.wholeCurve())
            is TargetPart.Edge -> Operand(b, part, null, b.edge(part.index), null)
            else -> Operand(b, part, b.namedPoint(part), null, null)
        }
        if (resolved.point == null && resolved.segment == null && resolved.curve == null) raise(CommandError.InvalidConstraint(cid, OPERANDS))
        return resolved
    }

    private fun Raise<CommandError>.segmentOf(cid: String, o: Operand): Segment = o.segment ?: raise(CommandError.InvalidConstraint(cid, OPERANDS))

    private fun Raise<CommandError>.curveOf(cid: String, o: Operand): Curve = o.curve ?: raise(CommandError.InvalidConstraint(cid, OPERANDS))

    private fun Raise<CommandError>.pointOf(cid: String, o: Operand): Int = o.point ?: raise(CommandError.InvalidConstraint(cid, OPERANDS))

    // Constraint kinds

    fun add(c: Constraint): Either<CommandError, Unit> = either {
        val cid = c.id.value
        c.shapeProblem()?.let { raise(CommandError.InvalidConstraint(cid, it)) }
        val ops = c.targets.map { operand(cid, it) }
        val produced = ArrayList<ConstraintEquation>()
        fun emit(make: (String) -> ConstraintEquation) {
            val eq = make(if (produced.isEmpty()) cid else "$cid$SEP${produced.size}")
            produced += eq
            if (eq is DimensionalEquation) dimensions[c.id] = eq
        }
        val value = c.value ?: 0.0
        when (c.kind) {
            ConstraintKind.Coincident -> coincident(cid, ops, ::emit)
            ConstraintKind.Fixed -> fixed(ops[0], ::emit)
            ConstraintKind.Horizontal, ConstraintKind.Vertical -> orientation(c.kind, cid, ops, ::emit)
            ConstraintKind.Parallel -> segments(cid, ops).let { (s, t) -> emit { Parallel(it, s.a, s.b, t.a, t.b) } }
            ConstraintKind.Perpendicular -> segments(cid, ops).let { (s, t) -> emit { Perpendicular(it, s.a, s.b, t.a, t.b) } }
            ConstraintKind.Equal -> equal(cid, ops, ::emit)
            ConstraintKind.Tangent -> tangent(cid, ops, ::emit)
            ConstraintKind.Symmetric -> {
                val axis = segmentOf(cid, ops[PAIR])
                val p = pointOf(cid, ops[0])
                val q = pointOf(cid, ops[1])
                emit { Symmetric(it, p, q, axis.a, axis.b) }
            }
            ConstraintKind.Distance -> {
                if (ops.size == 1) {
                    val s = segmentOf(cid, ops[0])
                    emit { DistanceDimension(it, s.a, s.b, value, c.reference) }
                } else {
                    val p = pointOf(cid, ops[0])
                    val q = pointOf(cid, ops[1])
                    emit { DistanceDimension(it, p, q, value, c.reference) }
                }
            }
            ConstraintKind.Angle -> segments(cid, ops).let { (s, t) -> emit { AngleDimension(it, s.a, s.b, t.a, t.b, value, AnglePolicy.Lines, c.reference) } }
            ConstraintKind.Radius -> curveOf(cid, ops[0]).let { k -> emit { RadiusDimension(it, k.radius, value, c.reference) } }
            ConstraintKind.Diameter -> curveOf(cid, ops[0]).let { k -> emit { DiameterDimension(it, k.radius, value, c.reference) } }
        }
        equations += produced
    }

    private fun Raise<CommandError>.segments(cid: String, ops: List<Operand>): Pair<Segment, Segment> =
        segmentOf(cid, ops[0]) to segmentOf(cid, ops[1])

    private fun Raise<CommandError>.coincident(cid: String, ops: List<Operand>, emit: ((String) -> ConstraintEquation) -> Unit) {
        val a = ops[0]
        val b = ops[1]
        val pa = a.point
        val pb = b.point
        when {
            pa != null && pb != null -> emit { Coincident(it, pa, pb) }
            pa != null || pb != null -> {
                val p = pa ?: pb ?: raise(CommandError.InvalidConstraint(cid, OPERANDS))
                val other = if (pa != null) b else a
                val curve = other.curve
                val segment = other.segment
                when {
                    curve != null -> emit { PointOnCircle(it, p, curve.center, curve.radius) }
                    segment != null -> emit { PointOnLine(it, p, segment.a, segment.b) }
                    else -> raise(CommandError.InvalidConstraint(cid, OPERANDS))
                }
            }
            a.whole && b.whole -> emit { Coincident(it, a.binding.primaryPoint(), b.binding.primaryPoint()) }
            else -> raise(CommandError.InvalidConstraint(cid, OPERANDS))
        }
    }

    private fun fixed(o: Operand, emit: ((String) -> ConstraintEquation) -> Unit) {
        val b = o.binding
        val pinned = when {
            o.point != null -> listOf(o.point)
            o.segment != null && !o.whole -> listOf(o.segment.a, o.segment.b)
            else -> b.pointStarts.toList()
        }
        for (v in pinned) emit { FixedPoint(it, v, values[v], values[v + 1]) }
        if (o.whole && b is ElementBinding.CircleB) emit { FixedScalar(it, b.r, values[b.r]) }
    }

    private fun Raise<CommandError>.orientation(kind: ConstraintKind, cid: String, ops: List<Operand>, emit: ((String) -> ConstraintEquation) -> Unit) {
        val (a, b) = if (ops.size == 1) segmentOf(cid, ops[0]).let { it.a to it.b } else pointOf(cid, ops[0]) to pointOf(cid, ops[1])
        if (kind == ConstraintKind.Horizontal) emit { Horizontal(it, a, b) } else emit { Vertical(it, a, b) }
    }

    private fun Raise<CommandError>.equal(cid: String, ops: List<Operand>, emit: ((String) -> ConstraintEquation) -> Unit) {
        val s = ops[0].segment
        val t = ops[1].segment
        val k = ops[0].curve
        val l = ops[1].curve
        when {
            s != null && t != null -> emit { EqualLength(it, s.a, s.b, t.a, t.b) }
            k != null && l != null -> emit { EqualRadius(it, k.radius, l.radius) }
            else -> raise(CommandError.InvalidConstraint(cid, OPERANDS))
        }
    }

    private fun Raise<CommandError>.tangent(cid: String, ops: List<Operand>, emit: ((String) -> ConstraintEquation) -> Unit) {
        val snapshot = values.toDoubleArray()
        val k0 = ops[0].curve
        val k1 = ops[1].curve
        val s0 = ops[0].segment
        val s1 = ops[1].segment
        when {
            k0 != null && k1 != null -> emit { TangentCircleCircle.infer(it, snapshot, k0.center, k0.radius, k1.center, k1.radius) }
            k0 != null && s1 != null -> emit { TangentLineCircle(it, s1.a, s1.b, k0.center, k0.radius, TangentLineCircle.sideOf(snapshot, s1.a, s1.b, k0.center)) }
            s0 != null && k1 != null -> emit { TangentLineCircle(it, s0.a, s0.b, k1.center, k1.radius, TangentLineCircle.sideOf(snapshot, s0.a, s0.b, k1.center)) }
            else -> raise(CommandError.InvalidConstraint(cid, OPERANDS))
        }
    }

    private companion object {
        const val IMPLICIT = SceneProblem.IMPLICIT_PREFIX
        const val SEP = SceneProblem.SUFFIX_SEPARATOR
    }
}

