// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.git

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.git.lfs.LfsViolation
import dev.stapler.stelekit.git.lfs.LfsViolationReason

internal const val LFS_HELD_TITLE = "Files held back by Git LFS"
internal const val LFS_HELD_REMEDY = "Move the file out of the tracked folder, or sync from desktop."

/** One line explaining why a file was not committed; the wording follows ADR-003 section 8. */
internal fun lfsHeldReasonText(reason: LfsViolationReason): String = when (reason) {
    LfsViolationReason.RawBinary ->
        "This repository stores that path with Git LFS and SteleKit cannot upload it here yet. " +
            "The file was not committed."
    LfsViolationReason.CorruptPointer ->
        "This file looks like a Git LFS pointer but is damaged. The file was not committed."
    LfsViolationReason.ConflictMarkers ->
        "This Git LFS pointer still contains merge conflict markers. The file was not committed."
    LfsViolationReason.RawBinaryPushed ->
        "This file was already pushed as a regular file although this repository stores that path with Git LFS. " +
            "SteleKit did not create that commit; convert it with git lfs migrate on a machine that has Git LFS."
}

/** Plain-text report of [held], for the "Copy details" action and bug reports. */
internal fun lfsHeldDiagnostics(held: List<LfsViolation>): String =
    held.joinToString("\n") { v ->
        buildString {
            append(v.path).append(": ").append(v.reason.name)
            v.ruleSource?.let { append(" (rule ").append(it).append(')') }
        }
    }

/**
 * Lists the files left out of the last sync because their paths are stored with Git LFS (the safety guard
 * never commits them raw). Everything else synced; the held files are untouched on disk.
 */
@Composable
fun LfsHeldFilesSheet(held: List<LfsViolation>, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(LFS_HELD_TITLE) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                held.forEach { violation ->
                    Column(modifier = Modifier.padding(bottom = 12.dp)) {
                        Text(violation.path, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Text(lfsHeldReasonText(violation.reason), style = MaterialTheme.typography.bodySmall)
                        violation.ruleSource?.let {
                            Text("Rule: $it", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Text(LFS_HELD_REMEDY, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(lfsHeldDiagnostics(held))) }) { Text("Copy details") }
        },
    )
}
