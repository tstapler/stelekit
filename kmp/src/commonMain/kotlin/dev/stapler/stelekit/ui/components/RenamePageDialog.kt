package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.model.Page

/**
 * Dialog for renaming a page.
 *
 * Shows a text field pre-filled with the current page name. While the rename is in progress
 * ([busy] = true) all controls are disabled and a progress bar is shown.
 * Any [error] from a previous attempt is displayed beneath the input field.
 */
@Composable
fun RenamePageDialog(
    page: Page,
    busy: Boolean,
    error: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember(page.uuid) { mutableStateOf(page.name) }
    val focusRequester = remember { FocusRequester() }

    val trimmedName = newName.trim()
    val canConfirm = !busy && trimmedName.isNotBlank() && trimmedName != page.name

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Rename Page") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New name") },
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
                if (error != null) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(trimmedName) },
                enabled = canConfirm
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("Cancel")
            }
        }
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}
