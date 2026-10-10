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
import androidx.compose.ui.Modifier

/** Why an interrupted copy cannot be resumed (S9 error states). */
enum class InterruptedProblem { StagingGone, TargetGone }

/** Plain data for S9; [problem] null means Resume is possible. [totalPages] is null when unknown. */
data class InterruptedCopyNotice(
    val targetName: String,
    val pagesCopied: Int,
    val totalPages: Int? = null,
    val problem: InterruptedProblem? = null,
)

/**
 * S9: shown at launch when `MergeManifestStore.findInterrupted()` is non-empty. Resume re-plans
 * (idempotent), so finished pages never repeat. Dismiss keeps the manifest for undo; the host shows
 * [CopyDialogStrings.DISMISS_SNACKBAR].
 */
@Composable
fun InterruptedCopyDialog(
    notice: InterruptedCopyNotice,
    onResume: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPicker: () -> Unit = {},
) {
    CopyDialogSurface(title = CopyOutcomeStrings.interruptedTitle(notice.targetName), onDismissRequest = onDismiss, modifier = modifier) {
        Column {
            Text(
                CopyOutcomeStrings.interruptedBody(notice.pagesCopied, notice.totalPages),
                style = MaterialTheme.typography.bodyMedium,
            )
            val message = when (notice.problem) {
                null -> CopyOutcomeStrings.RESUME_SAFE
                InterruptedProblem.StagingGone -> CopyOutcomeStrings.CANNOT_RESUME
                InterruptedProblem.TargetGone -> CopyDialogStrings.targetGone(notice.targetName)
            }
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.politeLiveRegion())
            ActionColumn {
                when (notice.problem) {
                    null -> Button(onClick = onResume, modifier = ActionButtonModifier) { Text(CopyDialogStrings.RESUME) }
                    InterruptedProblem.StagingGone ->
                        OutlinedButton(onClick = onOpenPicker, modifier = ActionButtonModifier) { Text(CopyOutcomeStrings.OPEN_PICKER) }
                    InterruptedProblem.TargetGone -> Unit
                }
                TextButton(onClick = onDismiss, modifier = ActionButtonModifier) { Text(CopyDialogStrings.DISMISS) }
            }
        }
    }
}
