// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Per-graph project settings: the digest page opt-in and title, and the opt-in analysis behind the outbound preview. */
@Composable
fun ProjectSettingsPanel(
    state: ProjectListUiState,
    onToggleDigest: (Boolean) -> Unit,
    onDigestTitle: (String) -> Unit,
    onAnalyseAfterSync: (Boolean) -> Unit,
    onConfirmPreview: () -> Unit,
    onDismissPreview: () -> Unit,
    onAddPrivateTerm: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var title by remember(state.digestTitle) { mutableStateOf(state.digestTitle) }
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Project settings", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Write a digest page after sync")
            Switch(checked = state.digestEnabled, onCheckedChange = onToggleDigest)
        }
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Digest page title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { onDigestTitle(title) }, enabled = title != state.digestTitle) { Text("Save title") }
        if (state.analyseOffered) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Analyse after sync")
                Switch(checked = state.analyseAfterSync, onCheckedChange = onAnalyseAfterSync)
            }
        }
        state.pendingPreview?.let {
            OutboundPreviewDialog(it, state.privateTerms, onAddPrivateTerm, onConfirmPreview, onDismissPreview)
        }
    }
}
