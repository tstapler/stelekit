// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.constraints

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.sketch.constraints.ConstraintProblem
import dev.stapler.stelekit.sketch.constraints.SolveAlgorithm
import dev.stapler.stelekit.sketch.constraints.SolveResult
import dev.stapler.stelekit.sketch.constraints.SolverTier
import dev.stapler.stelekit.sketch.constraints.constraints.FixedScalar
import dev.stapler.stelekit.sketch.v2.binding.followBoundArrows
import dev.stapler.stelekit.sketch.v2.commands.Applied
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.commands.relayoutLabels
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.SceneProblem
import dev.stapler.stelekit.sketch.v2.constraints.SceneToProblem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs

/** What a finished solve reports (the status line): degrees of freedom, effort and any redundant constraints. */
data class SolveSummary(val dof: Int, val iterations: Int, val algorithm: SolveAlgorithm?, val redundant: List<ConstraintId>)

/** The edit and its solve as one open transaction, so committing it is one history step. */
internal class Piped(val tx: Transaction, val summary: SolveSummary?)

/**
 * One edit plus the solve it triggers (Story 4.1.4a). The edit runs first; if the sheet has constraints the result is solved
 * and the solver's geometry, the bound arrows that follow it and the labels that ride on it are applied to the SAME
 * [Transaction], so one commit is one undo step and a redo replays the stored patch without calling this again.
 *
 * Which variables move: the solve first holds every variable the edit did not change at its previous value ("anchored"),
 * so dragging a vertex onto a Horizontal line moves only that vertex. When that over-determines the sketch (a coincident
 * partner has to follow, a new constraint is added with no geometry change) it solves again with everything free. The
 * constraints are then re-classified at the solution without the anchors, so degrees of freedom and redundancy are those
 * of the real sketch.
 */
internal class SolvePipeline(
    private val engine: SolveEngine,
    private val tier: SolverTier,
    private val counters: MutableStateFlow<SolveCounters>,
) {
    suspend fun run(before: SketchDocumentV2, edit: Command): Either<SolveRejection, Piped> {
        val edited = Transaction.begin(before).apply(edit).getOrElse { return SolveRejection.EditFailed(it).left() }
        val doc = edited.doc
        if (tier == SolverTier.Hidden || doc.constraints.isEmpty()) return Piped(edited, null).right()
        val problem = SceneToProblem.build(doc.scene, doc.constraints).getOrElse { return SolveRejection.EditFailed(it).left() }
        val attempt = solve(before, doc, problem)
        val result = attempt.result
        if (result !is SolveResult.Solved && result !is SolveResult.Redundant) {
            return SolveRejection.Unsatisfiable(unsatisfiedIds(result), kindOf(result)).left()
        }
        val summary = SolveSummary(result.dof, result.iterations + attempt.anchoredIterations, result.algorithm ?: attempt.anchoredAlgorithm, redundantOf(result))
        val applied = applied(doc, problem, result.x).getOrElse { return SolveRejection.EditFailed(it).left() }
        val next = edited.apply(Command { Applied(applied.doc, applied.changedIds).right() }).getOrElse { return SolveRejection.EditFailed(it).left() }
        return Piped(next, summary).right()
    }

    /** The final result and what the anchored attempt that preceded it cost, so the summary counts every iteration. */
    private class Attempt(val result: SolveResult, val anchoredIterations: Int = 0, val anchoredAlgorithm: SolveAlgorithm? = null)

    private suspend fun solve(before: SketchDocumentV2, doc: SketchDocumentV2, problem: SceneProblem): Attempt {
        val pinned = unchangedVariables(before, doc, problem)
        if (pinned.isNotEmpty() && pinned.size < problem.problem.variableCount) {
            val anchors = pinned.map { FixedScalar("$PIN_PREFIX$it", it, problem.start[it]) }
            val held = ConstraintProblem(problem.problem.equations + anchors, problem.problem.variableCount)
            val anchored = solveOnce(held, problem.start)
            if (anchored is SolveResult.Solved || anchored is SolveResult.Redundant) {
                return Attempt(solveOnce(problem.problem, anchored.x), anchored.iterations, anchored.algorithm)
            }
        }
        return Attempt(solveOnce(problem.problem, problem.start))
    }

    private suspend fun solveOnce(problem: ConstraintProblem, start: DoubleArray): SolveResult {
        counters.update { it.copy(solves = it.solves + 1) }
        return engine.solve(problem, start)
    }

    /** Variables whose value the edit left alone (to within solver noise), found by laying the same constraints over the scene before the edit. */
    private fun unchangedVariables(before: SketchDocumentV2, doc: SketchDocumentV2, problem: SceneProblem): List<Int> {
        val old = SceneToProblem.build(before.scene, doc.constraints).getOrNull() ?: return emptyList()
        if (old.problem.variableCount != problem.problem.variableCount || old.elementIds != problem.elementIds) return emptyList()
        return problem.start.indices.filter { abs(old.start[it] - problem.start[it]) <= ANCHOR_TOLERANCE }
    }

    private fun applied(doc: SketchDocumentV2, problem: SceneProblem, x: DoubleArray): Either<CommandError, Applied> {
        val solved = problem.apply(doc.scene, x).getOrElse { return it.left() }
        val moved = doc.scene.order.filter { solved[it] != doc.scene[it] }.toSet()
        if (moved.isEmpty()) return Applied(doc, emptySet()).right()
        val followed = solved.followBoundArrows(moved).getOrElse { return it.left() }
        if (followed.arrows.isNotEmpty()) counters.update { it.copy(bindingRecomputes = it.bindingRecomputes + 1) }
        val labelled = relayoutLabels(followed.scene, moved + followed.arrows).getOrElse { return it.left() }
        return Applied(doc.copy(scene = labelled.scene), moved + followed.arrows + labelled.labels).right()
    }

    private fun redundantOf(result: SolveResult): List<ConstraintId> =
        (result as? SolveResult.Redundant)?.ids?.mapNotNull { SceneProblem.constraintIdOf(it) }?.distinct().orEmpty()

    private fun unsatisfiedIds(result: SolveResult): List<ConstraintId> {
        val raw = when (result) {
            is SolveResult.Conflict -> result.ids
            is SolveResult.Degenerate -> result.ids
            is SolveResult.Redundant, is SolveResult.Solved, is SolveResult.NoConvergence -> emptyList()
        }
        return raw.mapNotNull { SceneProblem.constraintIdOf(it) }.distinct()
    }

    private fun kindOf(result: SolveResult): UnsatisfiedKind = when (result) {
        is SolveResult.Conflict -> UnsatisfiedKind.Conflict
        is SolveResult.Degenerate -> UnsatisfiedKind.Degenerate
        else -> UnsatisfiedKind.NoConvergence
    }

    private companion object {
        /** Equation-id prefix of an anchor; a leading `~` marks an equation no constraint owns (`SceneProblem.constraintIdOf`). */
        const val PIN_PREFIX = "~pin#"

        /** A variable that moved by no more than this since the last solve counts as untouched; the solver itself stops at 1e-9 (ADR-003 item 6). */
        const val ANCHOR_TOLERANCE = 1e-9
    }
}
