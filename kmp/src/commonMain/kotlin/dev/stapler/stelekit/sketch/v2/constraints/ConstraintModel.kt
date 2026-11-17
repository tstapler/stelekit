// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.constraints

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import dev.stapler.stelekit.sketch.v2.commands.Applied
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import kotlin.jvm.JvmInline

/** Stable id of a constraint; survives save, load, undo and redo. Never contains `#` (equation ids use it as a separator). */
@JvmInline
value class ConstraintId(val value: String) {
    override fun toString(): String = value
}

/**
 * Which part of an element a constraint operand names (Story 4.2.6). [Whole] is the element as one operand and resolves
 * by what the constraint needs; the other cases name a point or an edge exactly. Which parts an element has, and which a
 * constraint accepts, is the one table in [ConstraintParts].
 */
sealed interface TargetPart {
    /** The element as one operand: its first segment, primary point or circle, per what the constraint needs. */
    data object Whole : TargetPart

    /** A line's first point. */
    data object Start : TargetPart

    /** A line's last point. */
    data object End : TargetPart

    /** Point [index] of a line or corner [index] of a rectangle (top-left, top-right, bottom-right, bottom-left). */
    data class Vertex(val index: Int) : TargetPart

    /** The segment from point [index] to the next, or corner [index] to the next; a closed line's last edge wraps. */
    data class Edge(val index: Int) : TargetPart

    /** The centre of a circle or an arc. */
    data object Centre : TargetPart

    data object ArcStart : TargetPart

    data object ArcEnd : TargetPart

    /** True for a part that names one point. */
    val isPoint: Boolean get() = this is Start || this is End || this is Vertex || this is Centre || this is ArcStart || this is ArcEnd

    /** The text stored in the sheet file (`SheetCodecV2`); [parse] is its inverse. */
    val label: String
        get() = when (this) {
            Whole -> WHOLE_LABEL
            Start -> START_LABEL
            End -> END_LABEL
            is Vertex -> "$VERTEX_PREFIX$index"
            is Edge -> "$EDGE_PREFIX$index"
            Centre -> CENTRE_LABEL
            ArcStart -> ARC_START_LABEL
            ArcEnd -> ARC_END_LABEL
        }

    companion object {
        const val WHOLE_LABEL = "whole"
        private const val START_LABEL = "start"
        private const val END_LABEL = "end"
        private const val CENTRE_LABEL = "centre"
        private const val ARC_START_LABEL = "arcStart"
        private const val ARC_END_LABEL = "arcEnd"
        private const val VERTEX_PREFIX = "vertex:"
        private const val EDGE_PREFIX = "edge:"

        /** The part named by [label], or null for an unknown label or an index outside `0 until indexLimit`. */
        fun parse(label: String, indexLimit: Int): TargetPart? = when {
            label == WHOLE_LABEL -> Whole
            label == START_LABEL -> Start
            label == END_LABEL -> End
            label == CENTRE_LABEL -> Centre
            label == ARC_START_LABEL -> ArcStart
            label == ARC_END_LABEL -> ArcEnd
            label.startsWith(VERTEX_PREFIX) -> indexOf(label.removePrefix(VERTEX_PREFIX), indexLimit)?.let(::Vertex)
            label.startsWith(EDGE_PREFIX) -> indexOf(label.removePrefix(EDGE_PREFIX), indexLimit)?.let(::Edge)
            else -> null
        }

        private fun indexOf(text: String, limit: Int): Int? =
            text.takeIf { it.isNotEmpty() && it.all(Char::isDigit) && (it.length == 1 || it[0] != '0') }?.toIntOrNull()?.takeIf { it < limit }
    }
}

data class ConstraintTarget(val elementId: ElementId, val part: TargetPart = TargetPart.Whole)

/** How many operands a [ConstraintKind] takes. */
data class Arity(val min: Int, val max: Int) {
    operator fun contains(count: Int): Boolean = count in min..max

    companion object {
        fun exactly(n: Int) = Arity(n, n)
    }
}

/**
 * A typed constraint (Story 4.2.1). [value] is set for the dimensional kinds only (Distance, Angle in radians,
 * Radius, Diameter); a [reference] constraint reports the measured value and never drives the geometry.
 */
data class Constraint(
    val id: ConstraintId,
    val kind: ConstraintKind,
    val targets: List<ConstraintTarget>,
    val value: Double? = null,
    val reference: Boolean = false,
) {
    val isDimensional: Boolean get() = kind in DIMENSIONAL

    val elementIds: Set<ElementId> get() = targets.mapTo(LinkedHashSet()) { it.elementId }

    /** The first rule this constraint breaks on its own (no scene needed), or null. Reasons are fixed labels. */
    fun shapeProblem(): String? = when {
        id.value.isBlank() || '#' in id.value -> "id"
        targets.size !in arityOf(kind) -> "operands"
        isDimensional && (value == null || !value.isFinite()) -> "value"
        !isDimensional && value != null -> "value"
        reference && !isDimensional -> "reference"
        else -> null
    }

    companion object {
        val DIMENSIONAL = setOf(ConstraintKind.Distance, ConstraintKind.Angle, ConstraintKind.Radius, ConstraintKind.Diameter, ConstraintKind.DistanceX, ConstraintKind.DistanceY)

        fun arityOf(kind: ConstraintKind): Arity = when (kind) {
            ConstraintKind.Coincident, ConstraintKind.Parallel, ConstraintKind.Perpendicular,
            ConstraintKind.Equal, ConstraintKind.Tangent, ConstraintKind.Angle, ConstraintKind.DistanceX, ConstraintKind.DistanceY,
            -> Arity.exactly(PAIR)
            ConstraintKind.Fixed, ConstraintKind.Radius, ConstraintKind.Diameter -> Arity.exactly(1)
            ConstraintKind.Horizontal, ConstraintKind.Vertical, ConstraintKind.Distance -> Arity(1, PAIR)
            ConstraintKind.Symmetric -> Arity.exactly(TRIPLE)
        }

        private const val PAIR = 2
        private const val TRIPLE = 3
    }
}

/** Constraints whose every operand still exists; the cascade of deleting geometry (Story 4.2.1, UX-38). */
fun SketchDocumentV2.withoutOrphans(): SketchDocumentV2 {
    if (constraints.isEmpty()) return this
    val kept = constraints.filter { c -> c.targets.all { it.elementId in scene } }
    return if (kept.size == constraints.size) this else copy(constraints = kept)
}

/** How many constraints [after] lost against [before] (the count in the deletion toast, UX-38). */
fun removedConstraintCount(before: SketchDocumentV2, after: SketchDocumentV2): Int =
    before.constraints.count { b -> after.constraints.none { it.id == b.id } }

/**
 * Checks a constraint against the document: shape, eligibility of every operand (the ONE table, `ConstraintEligibility`)
 * and that the adapter can build it. Shared by every command that stores a constraint.
 */
internal fun validateConstraint(doc: SketchDocumentV2, c: Constraint): Either<CommandError, Unit> = either {
    c.shapeProblem()?.let { raise(CommandError.InvalidConstraint(c.id.value, it)) }
    val refs = ArrayList<PartRef>()
    for (t in c.targets) {
        val element = doc.scene[t.elementId] ?: raise(CommandError.UnknownElement(t.elementId.value))
        refs += PartRef(element, t.part)
        val kind = ElementKind.of(element)
        if (ConstraintEligibility.reason(kind, c.kind) is Eligibility.Ineligible) {
            raise(
                when {
                    kind == ElementKind.Ellipse -> CommandError.InvalidConstraint(c.id.value, "ellipse")
                    ConstraintEligibility.isConstrainable(kind) -> CommandError.InvalidConstraint(c.id.value, "operands")
                    else -> CommandError.NotConstrainable(kind)
                },
            )
        }
    }
    if (!ConstraintParts.accepts(c.kind, refs)) raise(CommandError.InvalidConstraint(c.id.value, "operands"))
    SceneToProblem.build(doc.scene, listOf(c)).bind()
}

/** Adds [constraint] (Task 4.2.1a). Refused for a second driving dimension of the same target (the label is edited instead, Task 4.2.7a), at `SceneLimits.maxConstraints`, for a duplicate id, an unknown or ineligible operand, or an unbuildable shape. */
data class AddConstraint(val constraint: Constraint) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val limit = doc.scene.limits.maxConstraints
        if (doc.constraints.size >= limit) raise(CommandError.ConstraintCapExceeded(limit))
        if (doc.constraints.any { it.id == constraint.id }) raise(CommandError.DuplicateConstraint(constraint.id.value))
        validateConstraint(doc, constraint).bind()
        (DimensionAdd.plan(doc, constraint) as? DimensionAddPlan.EditExisting)?.let { raise(CommandError.DuplicateDimension(it.id.value)) }
        Applied(doc.copy(constraints = doc.constraints + constraint), constraint.elementIds)
    }
}

data class RemoveConstraint(val id: ConstraintId) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> {
        val target = doc.constraints.firstOrNull { it.id == id } ?: return CommandError.UnknownConstraint(id.value).left()
        return Applied(doc.copy(constraints = doc.constraints.filter { it.id != id }), target.elementIds).right()
    }
}

/**
 * Turns a dimensional constraint into a reference one (it then only reports the measure) or back into a driving one.
 * The stored value becomes the CURRENT measure either way, so switching never moves the geometry.
 */
data class SetConstraintReference(val id: ConstraintId, val reference: Boolean) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val existing = doc.constraints.firstOrNull { it.id == id } ?: raise(CommandError.UnknownConstraint(id.value))
        if (!existing.isDimensional) raise(CommandError.InvalidConstraint(id.value, "reference"))
        val measured = SceneToProblem.measure(doc.scene, existing).bind()
        val next = existing.copy(reference = reference, value = measured)
        Applied(doc.copy(constraints = doc.constraints.map { if (it.id == id) next else it }), existing.elementIds)
    }
}
