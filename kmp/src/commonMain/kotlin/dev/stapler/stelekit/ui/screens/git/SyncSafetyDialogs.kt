// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.MassChangeChallenge

internal const val SYNC_RUNNING_LINE = "A sync is running. Wait for it to finish."

/**
 * Destination of a tap on the "Unfinished merge" badge: explains the state and offers the abort that the
 * conflict screen's Cancel also uses. Aborting resets tracked files to the pre-merge commit.
 */
@Composable
internal fun RepairNeededDialog(
    error: DomainError.GitError.RepairNeeded,
    busy: Boolean,
    onAbortMerge: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Repository needs repair") },
        text = {
            Column {
                Text(
                    "A merge was interrupted (${error.repositoryState}), so SteleKit will not commit, merge or push " +
                        "until it is finished. Aborting the merge puts the notes folder back to how it was before " +
                        "the merge started; uncommitted edits to tracked files are discarded.",
                )
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    Text(SYNC_RUNNING_LINE, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAbortMerge, enabled = !busy) {
                Text("Abort merge", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

/**
 * Foreground-only confirm for a sync that would delete many files. The user must count a figure from the
 * listed paths; the expected number is never shown, and non-digits are dropped from the field.
 */
@Composable
internal fun MassChangeConfirmDialog(
    error: DomainError.GitError.MassChangeBlocked,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val challenge = remember(error) { MassChangeChallenge.of(error) }
    var typed by remember(error) { mutableStateOf("") }
    val matches = challenge.accepts(typed)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(challenge.headline) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                    challenge.listedPaths.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                Spacer(Modifier.height(8.dp))
                if (!challenge.answerable) {
                    Text(MassChangeChallenge.UNANSWERABLE_LINE, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(challenge.question)
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it.filter { c -> c in '0'..'9' } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrectEnabled = false),
                    )
                    if (typed.isNotBlank() && !matches) {
                        Text(MassChangeChallenge.MISMATCH_HINT, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (busy) Text(SYNC_RUNNING_LINE, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = onConfirm, enabled = matches && !busy) { Text("Sync and delete") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
