// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp

/**
 * Shows the exact text that would leave the device and lets the owner add words that must never be sent
 * (Story 5.6c). Approving records acceptance of this text only; any later change asks again.
 */
@Composable
fun OutboundPreviewDialog(
    previewText: String,
    privateTerms: List<String>,
    onAddTerm: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var term by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review what would be sent") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("After each sync, exactly this text is sent to your chosen provider:", style = MaterialTheme.typography.bodyMedium)
                Text(previewText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                Text("Words never to send", style = MaterialTheme.typography.labelLarge)
                if (privateTerms.isNotEmpty()) Text(privateTerms.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = term,
                    onValueChange = { term = it },
                    label = { Text("Add a word") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { onAddTerm(term); term = "" }, enabled = term.isNotBlank()) { Text("Add word") }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Send this after each sync") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
