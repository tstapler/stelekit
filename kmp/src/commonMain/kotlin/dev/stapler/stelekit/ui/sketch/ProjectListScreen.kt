// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.components.typedItems

/** Flag-gated project list with creation and the per-graph settings; reached from the Sketch row. */
@Composable
fun ProjectListScreen(viewModel: ProjectListViewModel, onOpenProject: (String) -> Unit, modifier: Modifier = Modifier) {
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }
    val state by viewModel.state.collectAsState()
    ProjectListContent(
        state = state,
        onCreate = viewModel::createProject,
        onDismissMessage = viewModel::dismissMessage,
        onOpen = onOpenProject,
        settings = {
            ProjectSettingsPanel(
                state = state,
                onToggleDigest = viewModel::setDigestEnabled,
                onDigestTitle = viewModel::setDigestTitle,
                onAnalyseAfterSync = viewModel::setAnalyseAfterSync,
                onConfirmPreview = viewModel::confirmPreview,
                onDismissPreview = viewModel::dismissPreview,
                onAddPrivateTerm = viewModel::addPrivateTerm,
            )
        },
        modifier = modifier,
    )
}

@Composable
internal fun ProjectListContent(
    state: ProjectListUiState,
    onCreate: (String) -> Unit,
    onDismissMessage: () -> Unit,
    onOpen: (String) -> Unit,
    settings: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var creating by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        Text("Projects", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
        Button(onClick = { creating = true }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("New project") }
        state.message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            TextButton(onClick = onDismissMessage) { Text("Dismiss") }
        }
        if (state.projects.isEmpty()) {
            Text("No projects yet", modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(Modifier.weight(1f)) {
                typedItems(state.projects, key = { it.uuid }) { Text(it.name, modifier = Modifier.clickable { onOpen(it.uuid) }.padding(16.dp)) }
            }
            if (state.hasMore) Text("Showing the first ${ProjectListViewModel.PAGE_SIZE} projects", modifier = Modifier.padding(16.dp))
        }
        settings()
    }
    if (creating) {
        NewProjectDialog(onCreate = { onCreate(it); creating = false }, onDismiss = { creating = false })
    }
}

@Composable
private fun NewProjectDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
