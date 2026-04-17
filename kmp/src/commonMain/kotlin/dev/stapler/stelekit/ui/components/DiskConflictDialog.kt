package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.DiskConflict

/**
 * Dialog shown when the file watcher detects an external change to a page that
 * the user is currently editing. Offers three resolution options:
 *
 *  - Keep my changes  — re-saves local content to disk, discarding the external change
 *  - Use disk version — reloads from disk, discarding unsaved local edits
 *  - Save as new block — appends local edits as a new block, then loads disk version
 */
@Composable
fun DiskConflictDialog(
    conflict: DiskConflict,
    onKeepLocal: () -> Unit,
    onUseDisk: () -> Unit,
    onSaveAsNew: () -> Unit,
    onManualResolve: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* require explicit choice */ },
        title = {
            Text("Page modified on disk")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "\"${conflict.pageName}\" was changed externally while you were editing.",
                    style = MaterialTheme.typography.bodyMedium
                )

                if (conflict.localContent.isNotBlank()) {
                    Text("Your edit:", style = MaterialTheme.typography.labelMedium)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = conflict.localContent.take(200).let {
                                if (conflict.localContent.length > 200) "$it…" else it
                            },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                Text("Disk version:", style = MaterialTheme.typography.labelMedium)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = conflict.diskContent.take(200).let {
                            if (conflict.diskContent.length > 200) "$it…" else it
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onKeepLocal, modifier = Modifier.fillMaxWidth()) {
                    Text("Keep my changes")
                }
                OutlinedButton(onClick = onUseDisk, modifier = Modifier.fillMaxWidth()) {
                    Text("Use disk version")
                }
                if (conflict.localContent.isNotBlank()) {
                    TextButton(onClick = onSaveAsNew, modifier = Modifier.fillMaxWidth()) {
                        Text("Save my edit as a new block")
                    }
                }
                TextButton(onClick = onManualResolve, modifier = Modifier.fillMaxWidth()) {
                    Text("Manual resolve (show conflict markers)")
                }
            }
        }
    )
}
