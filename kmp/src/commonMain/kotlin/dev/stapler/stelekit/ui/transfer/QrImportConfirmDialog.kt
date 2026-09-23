package dev.stapler.stelekit.ui.transfer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.stapler.stelekit.transfer.qrcode.QrImportService

/**
 * Post-decode name-collision resolution modal (Story 3.2.4, S11) — reuses
 * [dev.stapler.stelekit.ui.components.CapturePreviewDialog]'s Save/Discard-with-spinner shape:
 * the chosen button shows a spinner while writing, the OTHER write-triggering button disables, but
 * [onCancel] stays tappable throughout (UX criterion 10) and [onDismissRequest][Dialog] (tap
 * outside / back) is blocked while a write is in flight — mirrors `CapturePreviewDialog`'s
 * `onDismissRequest = { if (!isImporting) onDiscard() }` guard.
 *
 * Never silently overwrites or duplicates: the three explicit choices are the only way to resolve
 * a detected collision.
 *
 * @param pendingChoice non-null while a write for that specific choice is in flight; the OTHER
 *   button disables and this one shows a spinner in place of its label.
 */
@Composable
fun QrImportConfirmDialog(
    existingName: String,
    pendingChoice: QrImportService.CollisionChoice?,
    onKeepBoth: () -> Unit,
    onOverwrite: () -> Unit,
    onCancel: () -> Unit,
) {
    val isWriting = pendingChoice != null

    Dialog(
        onDismissRequest = { if (!isWriting) onCancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "A page named \"$existingName\" already exists.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                // Cancel is ALWAYS tappable — never disabled, even mid-write (UX criterion 10).
                TextButton(onClick = onCancel) { Text("Cancel") }
                OutlinedButton(onClick = onKeepBoth, enabled = !isWriting) {
                    if (pendingChoice == QrImportService.CollisionChoice.KEEP_BOTH) {
                        SpinnerLabel()
                    } else {
                        Text("Keep both")
                    }
                }
                Button(onClick = onOverwrite, enabled = !isWriting) {
                    if (pendingChoice == QrImportService.CollisionChoice.OVERWRITE) {
                        SpinnerLabel()
                    } else {
                        Text("Overwrite")
                    }
                }
            }
        }
    }
}

@Composable
private fun SpinnerLabel() {
    CircularProgressIndicator(
        modifier = Modifier.size(16.dp),
        strokeWidth = 2.dp,
    )
}
