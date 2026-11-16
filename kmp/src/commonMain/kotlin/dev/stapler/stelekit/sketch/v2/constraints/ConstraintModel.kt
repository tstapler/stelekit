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
 * Which part of an element a constraint operand names. Story 4.2.1 defines only [Whole]; Task 4.2.6a adds the
 * parts (a segment, an endpoint, the centre) as further cases without changing [ConstraintTarget]'s shape.
 */
sealed interface TargetPart {
    /** The element as one operand: its first segment, primary point or circle, per what the constraint needs. */
    data object Whole : TargetPart

    companion object {
        const val WHOLE_LABEL = "whole"
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
        val DIMENSIONAL = setOf(ConstraintKind.Distance, ConstraintKind.Angle, ConstraintKind.Radius, ConstraintKind.Diameter)

        fun arityOf(kind: ConstraintKind): Arity = when (kind) {
            ConstraintKind.Coincident, ConstraintKind.Parallel, ConstraintKind.Perpendicular,
            ConstraintKind.Equal, ConstraintKind.Tangent, ConstraintKind.Angle,
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
    for (t in c.targets) {
        val element = doc.scene[t.elementId] ?: raise(CommandError.UnknownElement(t.elementId.value))
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
    SceneToProblem.build(doc.scene, listOf(c)).bind()
}

/** Adds [constraint] (Task 4.2.1a). Refused at `SceneLimits.maxConstraints`, for a duplicate id, an unknown or ineligible operand, or an unbuildable shape. */
data class AddConstraint(val constraint: Constraint) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val limit = doc.scene.limits.maxConstraints
        if (doc.constraints.size >= limit) raise(CommandError.ConstraintCapExceeded(limit))
        if (doc.constraints.any { it.id == constraint.id }) raise(CommandError.DuplicateConstraint(constraint.id.value))
        validateConstraint(doc, constraint).bind()
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
