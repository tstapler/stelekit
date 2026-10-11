// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A graph name doubles as a folder name on desktop, so it can't contain path separators. */
internal fun isValidGraphFolderName(name: String): Boolean {
    val n = name.trim()
    return n.isNotEmpty() && n != "." && n != ".." && n.none { it == '/' || it == '\\' || it == ':' }
}

/** Name + optional description, plus a platform-specific [location] section supplied by the caller. */
@Composable
fun NewGraphDialog(
    name: String,
    onNameChange: (String) -> Unit,
    description: String,
    onDescriptionChange: (String) -> Unit,
    canCreate: Boolean,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
    location: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New graph") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("Name") },
                    singleLine = true,
                    isError = name.isNotEmpty() && !isValidGraphFolderName(name),
                    supportingText = if (name.isNotEmpty() && !isValidGraphFolderName(name)) {
                        { Text("Can't contain / \\ or :") }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    label = { Text("Description (optional)") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                location()
            }
        },
        confirmButton = { Button(onClick = onCreate, enabled = canCreate) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
