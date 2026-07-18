// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.platform.HostAccessState
import kotlinx.coroutines.launch

/**
 * Task 3.1.1b: "Enable live folder sync" affordance for a graph that has never had live sync
 * connected — Surface 7 in design/ux.md. Shown only when [supportsNativeDirectoryPicker] and
 * [hostAccessState] is [HostAccessState.NotApplicable] (never shown once already connected, and
 * never shown at all on browsers without the File System Access API — the established "don't show
 * a broken affordance" convention from Onboarding).
 *
 * [onConnect] is a caller-supplied suspend lambda that performs the real
 * `showDirectoryPicker → HostDirectorySync.connectHostDirectory → runHostReconciliation` sequence
 * and returns the terminal [ReconciliationUiState] ([ReconciliationUiState.Summary] or
 * [ReconciliationUiState.Failed] — never [ReconciliationUiState.Connecting], which this composable
 * sets locally the instant the button is clicked, before awaiting [onConnect]). This shape
 * deliberately differs from plan.md Task 3.1.1b's literal `suspend () -> Unit` signature: Task
 * 3.1.2b additionally requires the four per-category reconciliation counts to reach this
 * composable, and this codebase has no existing precedent for a one-shot async operation result
 * threaded through a continuous `StateFlow` (the `localChangesCountFlow` precedent is for
 * continuously-updating data, not a single operation's outcome) — a return-value-carrying
 * callback is the smallest change that satisfies both tasks without inventing new state-flow
 * plumbing. See this dispatch's final report for the full rationale.
 */
@Composable
fun FolderSyncSettings(
    hostAccessState: HostAccessState,
    supportsNativeDirectoryPicker: Boolean,
    onConnect: suspend () -> ReconciliationUiState,
    modifier: Modifier = Modifier,
) {
    if (!supportsNativeDirectoryPicker || hostAccessState != HostAccessState.NotApplicable) return

    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf<ReconciliationUiState?>(null) }

    fun startConnect() {
        uiState = ReconciliationUiState.Connecting
        scope.launch {
            uiState = try {
                onConnect()
            } catch (e: Throwable) {
                ReconciliationUiState.Failed(e.message ?: "Couldn't finish comparing your files")
            }
        }
    }

    val currentUiState = uiState
    if (currentUiState != null) {
        FolderSyncReconciliationProgress(
            state = currentUiState,
            onDone = { uiState = null },
            onRetry = { startConnect() },
            onCancel = { uiState = null },
            modifier = modifier,
        )
        return
    }

    SettingsSection("Folder Sync") {
        Text(
            "This graph is stored in your browser only. You can connect it to a folder on your " +
                "computer so edits made here are written straight to your files — no export, " +
                "no git required.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = ::startConnect, modifier = Modifier.fillMaxWidth()) {
            Text("Enable live folder sync")
        }
        Spacer(Modifier.height(8.dp))
        // Load-bearing reassurance copy (design/ux.md Surface 7) — directly targets the Critical
        // Finding's failure mode (silent destruction of browser-only edits on connect). Must be
        // shown verbatim, before the button is ever clicked, and must never be cut for space.
        Text(
            "Existing edits in this graph are kept — nothing is overwritten when you connect.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
