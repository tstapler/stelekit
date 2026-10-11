// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.BranchRepairResult

/** Why the last "Use" tap did not finish; null while idle. */
internal fun BranchRepairResult.failureLine(): String? = when (this) {
    is BranchRepairResult.Repaired -> null
    is BranchRepairResult.Stale -> "This changed while the sheet was open. Review the new details."
    is BranchRepairResult.TargetNotOnRemote -> "The remote no longer has '$to'. Pick another branch."
    is BranchRepairResult.SaveFailed -> "Couldn't save the new branch. $message"
    is BranchRepairResult.ReadBackMismatch -> "Couldn't save the new branch."
}

/**
 * Repair for a configured branch that is missing on the remote. Offers exactly the branches the
 * remote has; a single candidate is a one-tap "Use 'x'", several force an explicit choice (no
 * preselection). Never syncs: [onUse] only changes the stored branch, and the caller routes to the
 * first-sync review.
 *
 * @param busy a sync is running; applying is disabled until it ends.
 */
@Composable
internal fun BranchRepairSheet(
    error: DomainError.GitError.RemoteBranchNotFound,
    busy: Boolean,
    saving: Boolean,
    failure: BranchRepairResult?,
    onUse: (branch: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(error) { mutableStateOf<String?>(null) }
    val single = error.available.singleOrNull()
    val target = single ?: selected

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Branch '${error.branch}' not found on remote") },
        text = {
            Column {
                Text(
                    if (error.available.isEmpty()) {
                        "The remote has no branches."
                    } else {
                        "SteleKit is set to sync '${error.branch}', but the remote has: " +
                            "${error.available.joinToString()}. Nothing was pulled."
                    },
                )
                if (error.available.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    Text("Choose another branch", style = MaterialTheme.typography.labelLarge)
                    Column(Modifier.selectableGroup().heightIn(max = 240.dp)) {
                        error.available.forEach { name ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(selected = selected == name, role = Role.RadioButton) { selected = name }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selected == name, onClick = null)
                                Spacer(Modifier.width(8.dp))
                                Text(name)
                            }
                        }
                    }
                }
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    Text("A sync is running. Wait for it to finish.", style = MaterialTheme.typography.bodySmall)
                }
                failure?.failureLine()?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { target?.let(onUse) },
                enabled = target != null && !busy && !saving,
            ) {
                Text(
                    when {
                        saving -> "Saving…"
                        single != null -> "Use '$single'"
                        else -> "Use selected branch"
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/**
 * Shown after a branch repair, before any sync runs. The user's "Sync now" tap is the consent.
 * [resultLine] reports the outcome of that sync; the dialog stays open until the user closes it.
 */
@Composable
internal fun FirstSyncReviewDialog(
    remoteBranch: String,
    previousBranch: String?,
    syncing: Boolean,
    resultLine: String?,
    onSyncNow: () -> Unit,
    onChangeBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Branch set to $remoteBranch. Review before syncing.") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Sync now fetches '$remoteBranch', merges it into this device and pushes any local " +
                        "commits. Files here can be changed or deleted to match the remote.",
                )
                resultLine?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                if (syncing) {
                    Spacer(Modifier.height(8.dp))
                    Text("Syncing…", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { Button(onClick = onSyncNow, enabled = !syncing) { Text("Sync now") } },
        dismissButton = {
            Row {
                if (previousBranch != null) {
                    TextButton(onClick = onChangeBack, enabled = !syncing) { Text("Change back to '$previousBranch'") }
                }
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        },
    )
}
