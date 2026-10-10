// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.stapler.stelekit.db.StorageMoveUiState
import dev.stapler.stelekit.error.DomainError

/**
 * Story 3.4.3: renders [StorageMoveUiState] (design/ux.md Surfaces 7/8/8b), shaped like
 * `FolderSyncReconciliationProgress`'s single-composable-swapping-content pattern — one dialog
 * instance a driving `scope.launch` updates in place as the coordinator's flow emits, not a new
 * dialog per state.
 *
 * `Summary` renders only a minimal acknowledgment here — Surface 9's full "Keep old copy" /
 * "Delete old copy" cleanup choice (AC34-37) has no task in this epic's plan.md (see
 * `EditGraphStorageSummaryTest.kt`'s "gap" row in validation.md) and is left to a future epic;
 * this `when` branch still exists so the composable is exhaustive for the whole sealed interface.
 *
 * @param onCancel the identical cancel callback for `Quiescing`, `Copying`, **and** `Verifying`
 * (AC48/Task 3.4.3d) — `Verifying` runs inside the same closed-driver region as `Copying`, so
 * there is no coordinator-level reason to disable it there.
 * @param onRetry `Failed`'s "Retry" — re-invokes the coordinator from the top.
 * @param onSummaryAcknowledge dismisses the minimal `Summary` acknowledgment.
 * @param onReopenFailedAcknowledge `ReopenFailed`'s sole "OK" action — returns to the graph list.
 */
@Composable
fun StorageMoveProgressDialog(
    graphName: String,
    state: StorageMoveUiState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onSummaryAcknowledge: () -> Unit,
    onReopenFailedAcknowledge: () -> Unit,
) {
    Dialog(
        // AC30: not dismissable by outside-tap/system-back while a move is in progress — every
        // exit (including the terminal states) is an explicit in-content action instead.
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
            Column(modifier = Modifier.width(360.dp).padding(20.dp)) {
                Text("Moving \"$graphName\"", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                when (state) {
                    is StorageMoveUiState.Quiescing ->
                        InProgressStep("Waiting for in-flight changes to finish…", progress = null, onCancel)
                    is StorageMoveUiState.Copying ->
                        InProgressStep(
                            "Copying files… ${state.count} of ${state.total}",
                            progress = if (state.total > 0) state.count.toFloat() / state.total else 0f,
                            onCancel,
                        )
                    is StorageMoveUiState.Verifying ->
                        // AC48: identical enabled Cancel treatment as Quiescing/Copying above —
                        // no special-casing here is the fix for the earlier disabled-Cancel bug.
                        InProgressStep("Verifying copied files…", progress = null, onCancel)
                    is StorageMoveUiState.Summary -> SummaryStep(graphName, onSummaryAcknowledge)
                    is StorageMoveUiState.Failed -> FailedStep(state.reason, onRetry, onCancel)
                    is StorageMoveUiState.ReopenFailed -> ReopenFailedStep(graphName, onReopenFailedAcknowledge)
                }
            }
        }
    }
}

@Composable
private fun InProgressStep(label: String, progress: Float?, onCancel: () -> Unit) {
    // Single top-level Column wrapping every emitter below — compose-rules' MultipleEmitters check
    // requires exactly one emission source at a composable's top level; this is a pure layout
    // wrapper with no behavior change (a Column inside the caller's own Column stacks identically).
    Column {
        Column(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            // AC29/AC48: always enabled — Cancel discards the destination copy and reopens the
            // driver at the original location via the coordinator's existing cancellation handling.
            TextButton(onClick = onCancel, enabled = true) { Text("Cancel") }
        }
    }
}

@Composable
private fun SummaryStep(graphName: String, onAcknowledge: () -> Unit) {
    // See InProgressStep's comment on why this wraps every emitter in a single top-level Column.
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("\"$graphName\" moved successfully.", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onAcknowledge) { Text("Done") }
        }
    }
}

@Composable
private fun FailedStep(reason: DomainError.StorageError, onRetry: () -> Unit, onCancel: () -> Unit) {
    // See InProgressStep's comment on why this wraps every emitter in a single top-level Column.
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Move couldn't be verified", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(4.dp))
        Text("Reason: ${reason.message}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        // AC32: verbatim on every Failed render, regardless of DomainError.StorageError subtype.
        Text(
            "Your original files were not touched or deleted.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        // AC31: only Retry/Cancel — never a "delete anyway"/"use it anyway" affordance.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

/**
 * Surface 8b: a **sibling** of [FailedStep], never sharing its template — no "Retry" (retrying
 * `relocate()` presumes a driver state the coordinator cannot establish here) and no "your
 * original files were not touched or deleted" claim (that reassurance implies the coordinator can
 * vouch for the overall graph state, which is exactly what this state means it cannot do). The
 * narrower, load-bearing claim this screen *can* make — the old location was never removed — is
 * stated instead (AC46).
 */
@Composable
private fun ReopenFailedStep(graphName: String, onAcknowledge: () -> Unit) {
    // See InProgressStep's comment on why this wraps every emitter in a single top-level Column.
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("\"$graphName\" couldn't be reopened", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "SteleKit can't confirm this graph's current state. The move may have completed, but " +
                "reopening it just failed. We can't tell you right now whether your files are at " +
                "the old location, the new one, or both.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Nothing else was deleted by this operation — the old location was never removed. If " +
                "you need your files immediately, check there first using your device's file manager.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "There's no automatic fix for this yet. Returning to your graph list and trying to " +
                "open \"$graphName\" again may succeed even though this attempt didn't.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        // AC45/AC47: the only interactive element on this screen — no "Retry".
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onAcknowledge) { Text("OK") }
        }
    }
}
