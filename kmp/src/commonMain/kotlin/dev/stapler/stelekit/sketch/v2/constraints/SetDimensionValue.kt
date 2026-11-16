// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.constraints

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.sketch.constraints.SolveResult
import dev.stapler.stelekit.sketch.v2.commands.Applied
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2

/**
 * Changes the value of a driving dimension and solves the sketch from its current geometry (Story 4.2.7). The new value
 * and every element the solver moved are ONE result, so one undo step reverts both. A value the solver cannot satisfy
 * is refused with [CommandError.Unsolvable] and the document is unchanged ("Failure keeps the last good state"); a
 * redundant but consistent result is accepted.
 */
data class SetDimensionValue(val id: ConstraintId, val value: Double) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val existing = doc.constraints.firstOrNull { it.id == id } ?: raise(CommandError.UnknownConstraint(id.value))
        if (!existing.isDimensional || existing.reference) raise(CommandError.InvalidConstraint(id.value, "reference"))
        val next = existing.copy(value = value)
        next.shapeProblem()?.let { raise(CommandError.InvalidConstraint(id.value, it)) }
        if (existing.kind != ConstraintKind.Angle && value <= 0.0) raise(CommandError.InvalidConstraint(id.value, "value"))
        val constraints = doc.constraints.map { if (it.id == id) next else it }
        val solved = SceneToProblem.build(doc.scene, constraints).bind().solve(doc.scene).bind()
        val result = solved.result
        if (result !is SolveResult.Solved && result !is SolveResult.Redundant) raise(CommandError.Unsolvable(id.value, conflictIdsOf(result)))
        val moved = doc.scene.order.filter { solved.scene[it] != doc.scene[it] }.toSet()
        Applied(doc.copy(scene = solved.scene, constraints = constraints), moved + existing.elementIds)
    }

    private fun conflictIdsOf(result: SolveResult): List<String> {
        val raw = when (result) {
            is SolveResult.Conflict -> result.ids
            is SolveResult.Degenerate -> result.ids
            is SolveResult.Redundant, is SolveResult.Solved, is SolveResult.NoConvergence -> emptyList()
        }
        return raw.mapNotNull { SceneProblem.constraintIdOf(it)?.value }.distinct()
    }
}

/** What adding a dimension does when the target already has one. */
sealed interface DimensionAddPlan {
    /** No driving dimension of this kind measures the same targets: add the constraint. */
    data object Add : DimensionAddPlan

    /** The target already has a driving dimension: open that label for editing instead of adding a duplicate. */
    data class EditExisting(val id: ConstraintId, val currentValue: Double) : DimensionAddPlan
}

/**
 * The add path's dimension rules (Story 4.2.7). [plan] stops a duplicate before it can conflict; [replaceable] finds the
 * existing dimension a conflict report can offer to edit in place ("Replace existing"). Neither offers anything for a
 * non-dimensional constraint, so a Parallel against a Horizontal conflict keeps its other actions only.
 */
object DimensionAdd {
    /** [DimensionAddPlan.EditExisting] when a driving dimension of the same kind and the same targets exists. */
    fun plan(doc: SketchDocumentV2, candidate: Constraint): DimensionAddPlan {
        if (!candidate.isDimensional || candidate.reference) return DimensionAddPlan.Add
        val twin = doc.constraints.firstOrNull { it.isDimensional && !it.reference && it.kind == candidate.kind && sameTargets(it, candidate) }
        val value = twin?.value
        return if (twin != null && value != null) DimensionAddPlan.EditExisting(twin.id, value) else DimensionAddPlan.Add
    }

    /**
     * The driving dimension named in [conflictIds] that measures the same geometry as [added] (the same kind on the
     * same points, however the targets are spelled), or null when the conflict is not between two such dimensions.
     */
    fun replaceable(doc: SketchDocumentV2, added: Constraint, conflictIds: Collection<String>): ConstraintId? {
        if (!added.isDimensional || added.reference) return null
        val key = measureKey(doc.scene, added) ?: return null
        return doc.constraints.firstOrNull { c ->
            c.id != added.id && c.id.value in conflictIds && c.isDimensional && !c.reference && c.kind == added.kind && measureKey(doc.scene, c) == key
        }?.id
    }

    private fun sameTargets(a: Constraint, b: Constraint): Boolean =
        if (a.kind == ConstraintKind.Distance && a.targets.size == PAIR && b.targets.size == PAIR) {
            a.targets.toSet() == b.targets.toSet()
        } else {
            a.targets == b.targets
        }

    /** What a dimension measures, spelled the same for every way of naming it; null when an operand no longer exists. */
    private fun measureKey(scene: SketchSceneV2, c: Constraint): String? {
        val perTarget = c.targets.map { keysOf(scene, it) ?: return null }
        return if (c.kind == ConstraintKind.Distance) perTarget.flatten().toSortedSet().joinToString(",") else perTarget.map { it.sorted().joinToString("-") }.sorted().joinToString(",")
    }

    private fun keysOf(scene: SketchSceneV2, t: ConstraintTarget): List<String>? {
        val element = scene[t.elementId] ?: return null
        val id = t.elementId.value
        val part = t.part
        return when (element) {
            is PointElement -> listOf("$id:point")
            is LineElement -> linear(id, part, element.points.size)
            is RectElement -> linear(id, part, RECT_CORNERS)
            is EllipseElement, is ArcElement -> curve(id, part)
            else -> null
        }
    }

    private fun linear(id: String, part: TargetPart, points: Int): List<String>? = when (part) {
        TargetPart.Whole -> listOf("$id:v0", "$id:v1")
        TargetPart.Start -> listOf("$id:v0")
        TargetPart.End -> listOf("$id:v${points - 1}")
        is TargetPart.Vertex -> listOf("$id:v${part.index}")
        is TargetPart.Edge -> listOf("$id:v${part.index}", "$id:v${(part.index + 1) % points}")
        else -> null
    }

    private fun curve(id: String, part: TargetPart): List<String>? = when (part) {
        TargetPart.Whole -> listOf("$id:curve")
        TargetPart.Centre -> listOf("$id:centre")
        TargetPart.ArcStart -> listOf("$id:arcStart")
        TargetPart.ArcEnd -> listOf("$id:arcEnd")
        else -> null
    }

    private const val PAIR = 2
    private const val RECT_CORNERS = 4
}
