// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.settings

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Task 3.1.2a: three-state UI model for the "enable live folder sync on an existing graph" flow
 * (design/ux.md Surface 8) — small enough to live alongside [FolderSyncReconciliationProgress]
 * rather than in `platform/HostReconciliation.kt`, since it is a pure UI-shape type (four `Int`
 * tallies, not the wasmJsMain-only `ReconciliationSummary`/`ReconciliationOutcome` types).
 */
sealed interface ReconciliationUiState {
    /** Transient state shown for the duration of `connectHostDirectory`'s awaited reconciliation walk. */
    data object Connecting : ReconciliationUiState

    /**
     * Terminal success state — one count per [dev.stapler.stelekit.platform.ReconciliationOutcome]
     * category. Categories with a zero count are omitted from the rendered summary (design/ux.md
     * Surface 8's wireframe only ever lists categories that actually have members).
     */
    data class Summary(
        val identical: Int,
        val hostChangedConflict: Int,
        val hostOnlyNew: Int,
        val browserOnlyNeedsPush: Int,
    ) : ReconciliationUiState

    /** Terminal failure state — reconciliation threw mid-walk; `hostDirHandle` was never set. */
    data class Failed(val message: String) : ReconciliationUiState
}

/**
 * Task 3.1.2a/c: renders [ReconciliationUiState]'s three states verbatim per design/ux.md
 * Surface 8's wireframes — the highest-stakes surface in the whole feature, since it is the only
 * place a user directly observes the Critical Finding's remediation (their browser-only edits
 * being preserved, not silently destroyed) in action.
 *
 * @param onDone called when the user dismisses the [ReconciliationUiState.Summary] screen.
 * @param onRetry called from [ReconciliationUiState.Failed]'s "Try again" button.
 * @param onCancel called from [ReconciliationUiState.Failed]'s "Cancel" button.
 */
@Composable
fun FolderSyncReconciliationProgress(
    state: ReconciliationUiState,
    onDone: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is ReconciliationUiState.Connecting -> ConnectingState(modifier)
        is ReconciliationUiState.Summary -> SummaryState(state, onDone, modifier)
        is ReconciliationUiState.Failed -> FailedState(onRetry, onCancel, modifier)
    }
}

@Composable
private fun ConnectingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            // Task 3.1.2c: announced on entry, per design/ux.md AC24.
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Connecting to folder…", style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Comparing your browser edits with the files on disk.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SummaryState(
    state: ReconciliationUiState.Summary,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // Task 3.1.2c: announced again on completion, per design/ux.md AC24.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Folder sync enabled", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))

        if (state.identical > 0) {
            Text("${state.identical} files already match", style = MaterialTheme.typography.bodyMedium)
        }
        if (state.hostChangedConflict > 0) {
            Text(
                "${state.hostChangedConflict} files differ — you'll be asked which version to keep as you open each page",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state.hostOnlyNew > 0) {
            Text(
                "${state.hostOnlyNew} new files found on disk — added to your graph",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state.browserOnlyNeedsPush > 0) {
            Text(
                "${state.browserOnlyNeedsPush} browser-only pages — will be written to the folder",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onDone) { Text("Done") }
        }
    }
}

@Composable
private fun FailedState(
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Couldn't finish comparing your files", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Nothing was changed — your graph is unaffected.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onRetry) { Text("Try again") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
