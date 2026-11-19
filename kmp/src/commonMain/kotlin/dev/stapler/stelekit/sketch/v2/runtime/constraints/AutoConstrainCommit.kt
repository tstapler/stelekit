// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.constraints

import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.SequenceCommand
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.AutoConstrainPlan
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.RemoveConstraint

/** How a drawn shape was committed (Story 4.2.2): with its inferred constraints, or plain because the solver refused them. */
sealed interface AutoConstrainResult {
    /** The shape and every inferred constraint are ONE history step; [added] feeds the "Constraint added." toast. */
    data class WithConstraints(val done: SolveOutcome.Done, val added: List<Constraint>) : AutoConstrainResult

    /** No constraint was inferred, or the inferred set was refused and the shape was committed alone ([refused] says so). */
    data class Plain(val done: SolveOutcome.Done, val refused: Boolean) : AutoConstrainResult

    /** The shape itself could not be committed (for example the scene is at its cap); [outcome] says why. */
    data class Failed(val outcome: SolveOutcome) : AutoConstrainResult
}

/**
 * Commits a drawn shape with the constraints auto-constrain inferred, through the runner so the solver's tidy-up of the
 * shape (a near-horizontal line made exactly horizontal) lands in the same undo step. A set the solver refuses never blocks
 * the drawing: the shape is committed alone.
 */
suspend fun SolveRunner.commitAutoConstrained(base: SketchDocumentV2, plan: AutoConstrainPlan): AutoConstrainResult {
    val first = commitEdit(base, plan.command)
    if (first is SolveOutcome.Done) {
        return if (plan.constraints.isEmpty()) AutoConstrainResult.Plain(first, refused = false) else AutoConstrainResult.WithConstraints(first, plan.constraints)
    }
    if (plan.constraints.isEmpty() || first !is SolveOutcome.Rejected) return AutoConstrainResult.Failed(first)
    val plain = commitEdit(base, plan.asPlain.command)
    return if (plain is SolveOutcome.Done) AutoConstrainResult.Plain(plain, refused = true) else AutoConstrainResult.Failed(plain)
}

/** The toast's Undo: removes just the inferred constraints and leaves the drawn shape (UX-39). It is itself one undoable step. */
fun undoAutoConstraints(added: List<Constraint>): Command = SequenceCommand(added.map { RemoveConstraint(it.id) })
