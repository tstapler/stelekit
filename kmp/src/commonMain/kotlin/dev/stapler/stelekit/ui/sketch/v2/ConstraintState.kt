// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.AddConstraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.diagnostics.DiagnosticsPort
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveOutcome
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveRejection
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveRunner

/**
 * What the last solve said about the sheet's constraints, for the Constraints tab: the degrees of freedom, the redundant and
 * conflicting constraints, and the inline card of a refused add or a sheet loaded with conflicts. All solving stays in the
 * [SolveRunner]; this class only reads its outcomes.
 */
class ConstraintState(
    private val runner: SolveRunner,
    private val diagnostics: DiagnosticsPort?,
    private val notifier: EditorNotifier,
    private val doc: () -> SketchDocumentV2,
) {
    var analysis: ConstraintAnalysis by mutableStateOf(ConstraintAnalysis.Unknown)
        private set

    var card: ConflictCard? by mutableStateOf(null)
        private set

    /** Re-reads the degrees of freedom and conflicts; nothing is recorded in history. The caller holds the edit gate. */
    suspend fun analyze() {
        analysis = ConstraintAnalysis.from(runner.analyze(doc()))
        card = ConflictCards.forLoaded(doc(), analysis)
    }

    /** The sheet has no constraints left: nothing to report. */
    fun cleared() {
        analysis = ConstraintAnalysis.Unknown
    }

    /** An edit and its solve were committed. */
    fun solved(command: Command, outcome: SolveOutcome.Done) {
        analysis = ConstraintAnalysis.from(outcome)
        card = null
        diagnostics?.let(ConstraintWarnings::resolve)
        (command as? AddConstraint)?.let { notifier.post(ConstraintToasts.added(listOf(it.constraint))) }
    }

    /** The solve refused [command]: say why, and for a refused constraint show the conflict card and raise the warning row. */
    fun refused(command: Command, outcome: SolveOutcome.Rejected) {
        notifier.rejected(outcome.reason)
        val added = (command as? AddConstraint)?.constraint ?: return
        val reason = outcome.reason as? SolveRejection.Unsatisfiable ?: return
        val shown = ConflictCards.forRejected(outcome.lastGood, added, reason)
        card = shown
        diagnostics?.let { ConstraintWarnings.raise(it, shown) }
    }

    /** The action of a card button: a command goes through [submit], the rest only changes the view through [highlight]. */
    fun onAction(action: ConflictAction, submit: (Command) -> Unit, highlight: (Set<ConstraintId>) -> Unit) {
        action.command?.let(submit)
        if (action.kind == ConflictActionKind.ShowConflicts) highlight(card?.conflictIds?.toSet().orEmpty())
        if (action.kind == ConflictActionKind.RemoveNew || action.command != null) card = null
    }
}
