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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.stapler.stelekit.git.TypedConfirm
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
import dev.stapler.stelekit.git.FirstSyncPreview
import dev.stapler.stelekit.git.FirstSyncPreviewSource

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

/** What the first-sync review shows about the preview: still loading, ready, or why it could not be built. */
internal sealed interface FirstSyncPreviewState {
    data object Loading : FirstSyncPreviewState
    data class Ready(val preview: FirstSyncPreview) : FirstSyncPreviewState
    data class Failed(val message: String) : FirstSyncPreviewState
}

/** Loads the preview for the review dialog as UI state; a failure message is redacted. */
internal suspend fun FirstSyncPreviewSource.loadState(graphId: String): FirstSyncPreviewState =
    load(graphId).fold(
        { FirstSyncPreviewState.Failed(dev.stapler.stelekit.security.Redactor.redactGitMessage(it.message)) },
        { FirstSyncPreviewState.Ready(it) },
    )

/** Read-only summary lines for [preview]; the first-sync review renders exactly these. */
internal fun FirstSyncPreview.summaryLines(): List<String> = buildList {
    val position = if (ahead != null && behind != null) "$ahead ahead, $behind behind $syncRef" else "Ahead/behind of $syncRef is unknown"
    add(position)
    val source = if (remoteBranchesLive) "" else " (last fetched)"
    add(
        "Remote branches$source: " + remoteBranches.joinToString(", ") {
            if (it.isSyncBranch) it.name else "${it.name} (not the sync branch)"
        },
    )
    add(if (filesToDelete == 0) "No files would be deleted" else "$filesToDelete files would be deleted from the remote")
    if (localOnlyCommits.isNotEmpty()) {
        add("Local commits not on the remote:")
        localOnlyCommits.forEach { add("${it.shortSha} ${it.subject}") }
    }
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
    preview: FirstSyncPreviewState? = null,
    onRetryPreview: () -> Unit = {},
    onCancelPreview: () -> Unit = {},
    onSyncWithoutPreview: () -> Unit = {},
    /** Non-null when the last sync ended in a state that has a detail surface (mass change, conflict, repair). */
    onOpenDetails: (() -> Unit)? = null,
) {
    var confirmingWithoutPreview by remember { mutableStateOf(false) }
    if (confirmingWithoutPreview) {
        SyncWithoutPreviewDialog(
            busy = syncing,
            onConfirm = {
                confirmingWithoutPreview = false
                onSyncWithoutPreview()
            },
            onCancel = { confirmingWithoutPreview = false },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Branch set to $remoteBranch. Review before syncing.") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Sync now fetches '$remoteBranch', merges it into this device and pushes any local " +
                        "commits. Files here can be changed or deleted to match the remote.",
                )
                PreviewSection(preview, onRetryPreview, onCancelPreview) { confirmingWithoutPreview = true }
                resultLine?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                onOpenDetails?.let { TextButton(onClick = it) { Text("Open details") } }
                if (syncing) {
                    Spacer(Modifier.height(8.dp))
                    Text("Syncing…", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = onSyncNow, enabled = !syncing && (preview?.allowsSyncNow ?: true)) { Text("Sync now") }
        },
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

@Composable
private fun PreviewSection(
    preview: FirstSyncPreviewState?,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onSyncWithoutPreview: () -> Unit,
) {
    if (preview == null) return
    Column {
        Spacer(Modifier.height(8.dp))
        when (preview) {
            FirstSyncPreviewState.Loading -> {
                Text("Checking the remote…", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            is FirstSyncPreviewState.Failed -> {
                Text("Preview unavailable: ${preview.message}. Nothing was changed.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRetry) { Text("Retry preview") }
                TextButton(onClick = onSyncWithoutPreview) { Text("Sync without preview") }
            }
            is FirstSyncPreviewState.Ready -> Column(Modifier.heightIn(max = PREVIEW_MAX_HEIGHT)) {
                preview.preview.summaryLines().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/**
 * Confirm for syncing when the remote could not be checked. The user types [TypedConfirm.SYNC_WITHOUT_PREVIEW_WORD];
 * focus starts on Cancel so a stray Enter cannot confirm.
 */
@Composable
internal fun SyncWithoutPreviewDialog(busy: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancelFocus.requestFocus() }
    val matches = TypedConfirm.matches(typed, TypedConfirm.SYNC_WITHOUT_PREVIEW_WORD)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Sync without a preview?") },
        text = {
            Column {
                Text(
                    "SteleKit couldn't check the remote, so how many commits are ahead or behind, and which " +
                        "branches exist there, is unknown. Syncing can still pull commits and delete files on this " +
                        "device to match the remote. The delete guard still applies.",
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Type sync to continue") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
                )
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    Text("A sync is running. Wait for it to finish.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm, enabled = matches && !busy) { Text("Sync without preview") } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus)) { Text("Cancel") } },
    )
}

private val PREVIEW_MAX_HEIGHT = 240.dp
