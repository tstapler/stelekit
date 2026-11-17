// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.diagnostics

/** The operations over which the binding invariant must hold (requirements.md "Abort and pivot criteria"). */
enum class BindingOperation { Rotate, GroupTransform, Paste }

/** The recorded result of one binding checkpoint evaluation (Task 6.2.9b). */
sealed interface BindingOutcome {
    /** The invariant held over every operation and `reconcile` found no dangling id: full binding ships. */
    data object Proceed : BindingOutcome

    /** No result exists for some operation, so no outcome is claimed. */
    data object NotMeasured : BindingOutcome

    /**
     * The invariant failed over [failed]: v1 ships bind-on-drop, follow-on-move and unbind-on-delete only; an arrow end is
     * left at its last position and unbound with a notice on those operations. Elbow routing stays stored-only (OD-7).
     */
    data class NarrowToBindOnDrop(val failed: Set<BindingOperation>) : BindingOutcome

    /** A binding defect corrupted a scene (a dangling id after `reconcile`): this blocks release, a missing convenience does not. */
    data object BlocksRelease : BindingOutcome
}

/**
 * The binding pivot of requirements.md "Abort and pivot criteria" (Story 3.3.2, Task 6.2.9b). Pure: the per-operation results
 * come from `BindingInvariantPropertyTest`, [danglingAfterReconcile] from a `reconcile` of the scenes that test produced.
 * Elbow routing is stored-only whatever the outcome, so it is not an input. It narrows a claim and never removes a feature.
 */
object BindingCheckpoint {
    /** [held] maps each operation to whether the invariant held over it; a missing operation is not measured. */
    fun evaluate(held: Map<BindingOperation, Boolean>, danglingAfterReconcile: Boolean = false): BindingOutcome {
        val failed = held.filterValues { !it }.keys
        return when {
            danglingAfterReconcile -> BindingOutcome.BlocksRelease
            held.keys.size != BindingOperation.entries.size -> BindingOutcome.NotMeasured
            failed.isEmpty() -> BindingOutcome.Proceed
            else -> BindingOutcome.NarrowToBindOnDrop(failed)
        }
    }
}
