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
import dev.stapler.stelekit.projectbridge.SourceBindingKind
import dev.stapler.stelekit.projectbridge.SourcePickerText

/** Picks wiki pages a project reads: a bounded search, then "Table" or "Notes" to bind. Titles show on this device only. */
@Composable
fun SourcePagePicker(
    state: ProjectListUiState,
    onSearch: (String) -> Unit,
    onBind: (SourceBindingKind, String) -> Unit,
    onUnbind: (SourceBindingKind, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Source pages", style = MaterialTheme.typography.titleMedium)
        state.boundSources.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(SourcePickerText.boundLabel(row))
                TextButton(onClick = { onUnbind(row.kind, row.pageUuid) }) { Text("Remove") }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; onSearch(it) },
            label = { Text("Find a page") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        state.pickerResults.forEach { hit ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(hit.name)
                Row {
                    TextButton(onClick = { onBind(SourceBindingKind.TABLE, hit.uuid) }) { Text("Use as table") }
                    TextButton(onClick = { onBind(SourceBindingKind.PROSE, hit.uuid) }) { Text("Use as notes") }
                }
            }
        }
    }
}
