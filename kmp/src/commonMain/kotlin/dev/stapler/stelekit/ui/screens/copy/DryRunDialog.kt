// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.copy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.stapler.stelekit.merge.ClosureSummary
import dev.stapler.stelekit.merge.CopyDirection
import dev.stapler.stelekit.merge.DryRunSummary
import dev.stapler.stelekit.merge.PlanConflict

/** Linked-page delta above which the user must confirm again (UX-12). */
const val LARGE_CLOSURE_CONFIRM_THRESHOLD = 200

/** What the dry-run dialog shows; the host maps `plan()`/`ApplyFailure.PlanStale` onto it. */
sealed interface DryRunUiState {
    /** Plan running over chunks; [soFar] are the live counters. */
    data class Checking(val checked: Int, val total: Int, val soFar: DryRunSummary = DryRunSummary()) : DryRunUiState

    /** [stale] true after `ApplyFailure.PlanStale`: [summary] is then the recomputed one. */
    data class Ready(
        val summary: DryRunSummary,
        val closure: ClosureSummary = ClosureSummary(),
        val conflictDetails: List<PlanConflict> = emptyList(),
        val stale: Boolean = false,
    ) : DryRunUiState

    data class PlanFailed(val reason: String) : DryRunUiState

    data object TargetGone : DryRunUiState
}

/**
 * S4. Stateless: [onBack] also fires for Back/Esc and cancels the plan (nothing was written, so
 * "Back" is correct wording here). [onConfirm] is the Copy button; a stale plan needs a second press.
 */
@Composable
fun DryRunDialog(
    direction: CopyDirection,
    sourceName: String,
    targetName: String,
    state: DryRunUiState,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    onChooseAnotherDestination: () -> Unit = {},
) {
    val count = (state as? DryRunUiState.Ready)?.summary?.toCopy
    CopyDialogSurface(
        title = CopyDialogStrings.dryRunTitle(direction, count, sourceName, targetName),
        onDismissRequest = onBack,
        modifier = modifier,
    ) {
        when (state) {
            is DryRunUiState.Checking -> CheckingBody(state, onBack)
            is DryRunUiState.Ready -> ReadyBody(state, onConfirm, onBack)
            is DryRunUiState.PlanFailed -> FailedBody(state.reason, onRetry, onBack)
            DryRunUiState.TargetGone -> TargetGoneBody(targetName, onChooseAnotherDestination, onBack)
        }
    }
}

@Composable
private fun CheckingBody(state: DryRunUiState.Checking, onBack: () -> Unit) {
    Column {
        Text(
            CopyDialogStrings.checking(state.checked, state.total),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.politeLiveRegion(),
        )
        if (state.total > 0) {
            LinearProgressIndicator(
                progress = { (state.checked.toFloat() / state.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        CountLines(state.soFar)
        ActionColumn {
            Button(onClick = {}, enabled = false, modifier = ActionButtonModifier) { Text("Copy pages") }
            TextButton(onClick = onBack, modifier = ActionButtonModifier) { Text(CopyDialogStrings.BACK) }
        }
    }
}

@Composable
private fun ReadyBody(state: DryRunUiState.Ready, onConfirm: () -> Unit, onBack: () -> Unit) {
    val s = state.summary
    val needsLargeConfirm = state.closure.requiresConfirmation || state.closure.added > LARGE_CLOSURE_CONFIRM_THRESHOLD
    var largeConfirmShown by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Column {
        if (state.stale) {
            Text(
                CopyDialogStrings.STALE,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.politeLiveRegion(),
            )
        }
        CountLines(s)
        if (state.closure.added > 0) {
            Text("Includes ${CopyDialogStrings.pages(state.closure.added)} linked from your selection", style = MaterialTheme.typography.bodyMedium)
        }
        if (state.closure.notIncluded > 0) {
            Text("${CopyDialogStrings.pages(state.closure.notIncluded)} linked pages were not included (over the limit)", style = MaterialTheme.typography.bodyMedium)
        }
        if (state.conflictDetails.isNotEmpty()) {
            TextButton(onClick = { showDetails = !showDetails }, modifier = ActionButtonModifier) {
                Text(if (showDetails) "Hide details" else "Show details")
            }
            if (showDetails) {
                state.conflictDetails.forEach { Text(it.pageName, style = MaterialTheme.typography.bodySmall) }
            }
        }
        val nothing = s.toCopy == 0
        if (nothing) {
            Text(CopyDialogStrings.NOTHING_TO_COPY, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.politeLiveRegion())
        }
        Text(CopyDialogStrings.REASSURANCE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (needsLargeConfirm && largeConfirmShown) {
            Text(
                "This adds ${CopyDialogStrings.pages(state.closure.added)} you did not pick. Copy them too?",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.politeLiveRegion(),
            )
        }
        ActionColumn {
            Button(
                onClick = {
                    if (needsLargeConfirm && !largeConfirmShown) largeConfirmShown = true else onConfirm()
                },
                enabled = !nothing,
                modifier = ActionButtonModifier,
            ) {
                Text(
                    if (needsLargeConfirm && largeConfirmShown) "Yes, ${CopyDialogStrings.copyButton(s.toCopy)}"
                    else CopyDialogStrings.copyButton(s.toCopy),
                )
            }
            OutlinedButton(onClick = onBack, modifier = ActionButtonModifier) { Text(CopyDialogStrings.BACK) }
        }
    }
}

@Composable
private fun CountLines(s: DryRunSummary) {
    Column {
        Text(CopyDialogStrings.newLine(s.new), style = MaterialTheme.typography.bodyMedium)
        Text(CopyDialogStrings.combinedLine(s.combined), style = MaterialTheme.typography.bodyMedium)
        Text(CopyDialogStrings.unchangedLine(s.unchanged), style = MaterialTheme.typography.bodyMedium)
        if (s.conflicts > 0) Text(CopyDialogStrings.conflictLine(s.conflicts), style = MaterialTheme.typography.bodyMedium)
        if (s.unreadable > 0) Text(CopyDialogStrings.unreadableLine(s.unreadable), style = MaterialTheme.typography.bodyMedium)
        if (s.assetsRenamed > 0) Text(CopyDialogStrings.assetsLine(s.assetsRenamed), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FailedBody(reason: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column {
        Text("Couldn't check what would change: $reason", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.politeLiveRegion())
        ActionColumn {
            Button(onClick = onRetry, modifier = ActionButtonModifier) { Text(CopyDialogStrings.RETRY) }
            OutlinedButton(onClick = onBack, modifier = ActionButtonModifier) { Text(CopyDialogStrings.BACK) }
        }
    }
}

@Composable
private fun TargetGoneBody(targetName: String, onChooseAnother: () -> Unit, onClose: () -> Unit) {
    Column {
        Text(CopyDialogStrings.targetGone(targetName), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.politeLiveRegion())
        ActionColumn {
            Button(onClick = onChooseAnother, modifier = ActionButtonModifier) { Text(CopyDialogStrings.CHOOSE_ANOTHER) }
            OutlinedButton(onClick = onClose, modifier = ActionButtonModifier) { Text("Close") }
        }
    }
}
