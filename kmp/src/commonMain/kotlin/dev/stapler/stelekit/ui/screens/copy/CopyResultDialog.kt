// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.copy

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import dev.stapler.stelekit.merge.CopyDirection
import dev.stapler.stelekit.merge.MergeResult

/**
 * S6. Persistent result (not a snackbar). Stopped runs ([MergeResult.stoppedAfter] set) get the
 * "Stopped after X of Y" title and Continue / Undo / Done; finished runs get Retry failed,
 * Review conflicts, Undo, Done. Undo is hidden when nothing was created ([undoAvailable] false
 * or new + combined = 0). Esc/Back = Done.
 */
@Composable
fun CopyResultDialog(
    direction: CopyDirection,
    sourceName: String,
    targetName: String,
    result: MergeResult,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryFailures: () -> Unit = {},
    onReviewConflicts: () -> Unit = {},
    onUndo: () -> Unit = {},
    onContinue: () -> Unit = {},
    undoAvailable: Boolean = true,
) {
    val stopped = result.stoppedAfter != null
    val title = result.stoppedMessage ?: CopyOutcomeStrings.resultTitle(direction, sourceName, targetName)
    CopyDialogSurface(title = title, onDismissRequest = onDone, modifier = modifier) {
        Column {
            if (stopped) StoppedSummary(result)
            Categories(result)
            if (result.failed.isNotEmpty()) FailedList(result)
            if (!stopped && result.newPages + result.combinedPages == 0 && result.failed.isEmpty()) {
                Text(CopyDialogStrings.NOTHING_NEEDED, style = MaterialTheme.typography.bodyMedium)
            }
            ResultActions(
                result = result,
                stopped = stopped,
                canUndo = undoAvailable && result.newPages + result.combinedPages > 0,
                onRetryFailures = onRetryFailures,
                onReviewConflicts = onReviewConflicts,
                onUndo = onUndo,
                onContinue = onContinue,
                onDone = onDone,
            )
        }
    }
}

@Composable
private fun StoppedSummary(result: MergeResult) {
    val kept = result.newPages + result.combinedPages + result.unchangedPages
    Column {
        Text(
            CopyOutcomeStrings.stoppedBody(kept, result.notAttempted),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.politeLiveRegion(),
        )
        Text(
            CopyOutcomeStrings.STOPPED_HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Categories(result: MergeResult) {
    Column(modifier = Modifier.politeLiveRegion()) {
        Text("${result.newPages} new", style = MaterialTheme.typography.bodyMedium)
        Text("${result.combinedPages} combined", style = MaterialTheme.typography.bodyMedium)
        Text("${result.unchangedPages} unchanged", style = MaterialTheme.typography.bodyMedium)
        if (result.conflicts > 0) Text(CopyOutcomeStrings.conflictsKept(result.conflicts), style = MaterialTheme.typography.bodyMedium)
        if (result.failed.isNotEmpty()) Text("${result.failed.size} failed", style = MaterialTheme.typography.bodyMedium)
        if (result.assetsRenamed > 0) Text(CopyDialogStrings.assetsLine(result.assetsRenamed), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FailedList(result: MergeResult) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = ActionButtonModifier.semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
        ) { Text(CopyOutcomeStrings.failedHeader(result.failed.size)) }
        if (expanded) {
            result.failed.forEach { f ->
                Text("${f.pageName} - ${f.error.message}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ResultActions(
    result: MergeResult,
    stopped: Boolean,
    canUndo: Boolean,
    onRetryFailures: () -> Unit,
    onReviewConflicts: () -> Unit,
    onUndo: () -> Unit,
    onContinue: () -> Unit,
    onDone: () -> Unit,
) {
    ActionColumn {
        if (stopped) {
            Button(onClick = onContinue, modifier = ActionButtonModifier) { Text(CopyDialogStrings.CONTINUE) }
        }
        if (!stopped && result.failed.isNotEmpty()) {
            Button(onClick = onRetryFailures, modifier = ActionButtonModifier) { Text(CopyDialogStrings.RETRY_FAILED) }
        }
        if (result.conflicts > 0) {
            OutlinedButton(onClick = onReviewConflicts, modifier = ActionButtonModifier) {
                Text(CopyOutcomeStrings.reviewConflicts(result.conflicts))
            }
        }
        if (canUndo) {
            OutlinedButton(onClick = onUndo, modifier = ActionButtonModifier) { Text(CopyDialogStrings.UNDO) }
        }
        if (stopped || result.failed.isNotEmpty() || canUndo) {
            TextButton(onClick = onDone, modifier = ActionButtonModifier) { Text(CopyDialogStrings.DONE) }
        } else {
            Button(onClick = onDone, modifier = ActionButtonModifier) { Text(CopyDialogStrings.DONE) }
        }
    }
}

/** Nothing was written (apply refused or failed): reason plus a way out (UX-30). */
@Composable
fun CopyRunFailedDialog(
    targetName: String,
    reason: String,
    onRetry: () -> Unit,
    onChooseAnotherDestination: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CopyDialogSurface(title = CopyOutcomeStrings.failedTitle(targetName), onDismissRequest = onDone, modifier = modifier) {
        Column {
            Text(reason, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.politeLiveRegion())
            ActionColumn {
                Button(onClick = onRetry, modifier = ActionButtonModifier) { Text(CopyDialogStrings.RETRY) }
                OutlinedButton(onClick = onChooseAnotherDestination, modifier = ActionButtonModifier) {
                    Text(CopyDialogStrings.CHOOSE_ANOTHER)
                }
                TextButton(onClick = onDone, modifier = ActionButtonModifier) { Text(CopyDialogStrings.DONE) }
            }
        }
    }
}
