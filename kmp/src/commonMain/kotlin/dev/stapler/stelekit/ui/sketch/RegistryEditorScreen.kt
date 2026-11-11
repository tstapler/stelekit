// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.fusion.DimensionEntry
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.projectbridge.AliasKind
import dev.stapler.stelekit.projectbridge.ConstraintPreviewRow
import dev.stapler.stelekit.projectbridge.ConstraintTemplate
import dev.stapler.stelekit.projectbridge.KeySuggestion
import dev.stapler.stelekit.projectbridge.RegistryEditing

/** Every action the editor can take; the screen holds no rules, the view model and pure functions do. */
class RegistryEditorActions(
    val onBack: () -> Unit,
    val onAddKey: (key: String, kind: DimensionKind, label: String) -> Unit,
    val onRenameKey: (old: String, new: String) -> Unit,
    val onDeleteKey: (String) -> Unit,
    val onToggleKind: (key: String, kind: DimensionKind) -> Unit,
    val onAddAlias: (key: String, kind: AliasKind, alias: String) -> Unit,
    val onRemoveAlias: (key: String, kind: AliasKind, alias: String) -> Unit,
    val onAcceptSuggestion: (path: String) -> Unit,
    val onAddConstraint: (ConstraintTemplate) -> Unit,
    val onRemoveConstraint: (Int) -> Unit,
    val onStartOver: () -> Unit,
    val onDismissMessage: () -> Unit,
)

/** Registry and constraint editor for the first project, reached from the project settings (Story 5.9e). */
@Composable
fun RegistryEditorScreen(viewModel: RegistryEditorViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }
    val state by viewModel.state.collectAsState()
    val actions = remember(viewModel, onBack) {
        RegistryEditorActions(
            onBack = onBack,
            onAddKey = viewModel::addKey,
            onRenameKey = viewModel::renameKey,
            onDeleteKey = viewModel::deleteKey,
            onToggleKind = viewModel::setKind,
            onAddAlias = viewModel::addAlias,
            onRemoveAlias = viewModel::removeAlias,
            onAcceptSuggestion = viewModel::acceptSuggestion,
            onAddConstraint = viewModel::addConstraint,
            onRemoveConstraint = viewModel::removeConstraint,
            onStartOver = viewModel::startOver,
            onDismissMessage = viewModel::dismissMessage,
        )
    }
    RegistryEditorContent(state, actions, modifier)
}

@Composable
internal fun RegistryEditorContent(state: RegistryEditorState, actions: RegistryEditorActions, modifier: Modifier = Modifier) {
    var showConstraints by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Registry and constraints", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = actions.onBack) { Text("Back") }
        }
        state.message?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = actions.onDismissMessage) { Text("Dismiss") }
        }
        when {
            !state.loaded -> Text("Loading")
            !state.hasProject -> Text("Create a project first")
            state.needsAttention != null -> NeedsAttentionNotice(state.needsAttention, actions.onStartOver)
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showConstraints = false }, enabled = showConstraints) { Text("Registry") }
                    OutlinedButton(onClick = { showConstraints = true }, enabled = !showConstraints) { Text("Constraints") }
                }
                if (showConstraints) ConstraintsPane(state, actions) else RegistryPane(state, actions)
            }
        }
    }
}

@Composable
private fun NeedsAttentionNotice(reason: String, onStartOver: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Needs attention", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        Text(reason)
        Text("The digest stays empty until this is fixed. Starting over clears the saved registry and constraints.")
        Button(onClick = onStartOver) { Text("Start over") }
    }
}

@Composable
private fun RegistryPane(state: RegistryEditorState, actions: RegistryEditorActions) {
    var adding by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { adding = true }) { Text("Add key") }
        if (state.suggestions.isNotEmpty()) SuggestionList(state.suggestions, actions.onAcceptSuggestion)
        if (state.registry.dimensions.isEmpty()) Text("No dimension keys yet")
        state.registry.dimensions.forEach { KeyCard(it, actions) }
    }
    if (adding) {
        AddKeyDialog(onAdd = { key, kind, label -> actions.onAddKey(key, kind, label); adding = false }, onDismiss = { adding = false })
    }
}

@Composable
private fun SuggestionList(suggestions: List<KeySuggestion>, onAccept: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Suggested from the YAML file", style = MaterialTheme.typography.titleSmall)
        suggestions.take(SUGGESTIONS_SHOWN).forEach { suggestion ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(suggestion.path, Modifier.weight(1f))
                TextButton(onClick = { onAccept(suggestion.path) }) { Text("Add ${suggestion.key}") }
            }
        }
        if (suggestions.size > SUGGESTIONS_SHOWN) Text("${suggestions.size - SUGGESTIONS_SHOWN} more after you add these")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyCard(entry: DimensionEntry, actions: RegistryEditorActions) {
    var dialog by remember { mutableStateOf<KeyDialog?>(null) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(entry.key, style = MaterialTheme.typography.titleSmall)
        Text("${entry.label} (${kindName(entry.kind)})")
        AliasKind.entries.forEach { kind ->
            RegistryEditing.aliasesOf(entry, kind).forEach { alias ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${kind.title}: $alias", Modifier.weight(1f))
                    TextButton(onClick = { actions.onRemoveAlias(entry.key, kind, alias) }) { Text("Remove $alias") }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { dialog = KeyDialog.Alias }) { Text("Add alias") }
            TextButton(onClick = { dialog = KeyDialog.Rename }) { Text("Rename") }
            TextButton(onClick = { actions.onToggleKind(entry.key, other(entry.kind)) }) { Text("Make ${kindName(other(entry.kind))}") }
            TextButton(onClick = { actions.onDeleteKey(entry.key) }) { Text("Delete ${entry.key}") }
        }
    }
    when (dialog) {
        KeyDialog.Alias -> AddAliasDialog(entry.key, { kind, alias -> actions.onAddAlias(entry.key, kind, alias); dialog = null }, { dialog = null })
        KeyDialog.Rename -> TextEntryDialog("Rename ${entry.key}", "New key", entry.key, { actions.onRenameKey(entry.key, it); dialog = null }, { dialog = null })
        null -> Unit
    }
}

private enum class KeyDialog { Alias, Rename }

private fun kindName(kind: DimensionKind) = if (kind == DimensionKind.LENGTH) "length" else "count"

private fun other(kind: DimensionKind) = if (kind == DimensionKind.LENGTH) DimensionKind.COUNT else DimensionKind.LENGTH

@Composable
private fun AddKeyDialog(onAdd: (String, DimensionKind, String) -> Unit, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(DimensionKind.LENGTH) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(key, { key = it }, label = { Text("Key") }, singleLine = true)
                OutlinedTextField(label, { label = it }, label = { Text("Label") }, singleLine = true)
                TextButton(onClick = { kind = other(kind) }) { Text("Kind: ${kindName(kind)}") }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(key, kind, label) }, enabled = key.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddAliasDialog(key: String, onAdd: (AliasKind, String) -> Unit, onDismiss: () -> Unit) {
    var alias by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(AliasKind.YAML) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add alias to $key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AliasKind.entries.forEach { option ->
                    TextButton(onClick = { kind = option }) { Text(if (option == kind) "${option.title} (selected)" else option.title) }
                }
                OutlinedTextField(alias, { alias = it }, label = { Text("Alias") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(kind, alias) }, enabled = alias.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TextEntryDialog(title: String, label: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConstraintsPane(state: RegistryEditorState, actions: RegistryEditorActions) {
    var template by remember { mutableStateOf<TemplateKind?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TemplateKind.entries.forEach { kind -> Button(onClick = { template = kind }, enabled = state.registry.dimensions.size >= 2) { Text(kind.title) } }
        }
        Text("A check compares each source's own readings, within the rounding of the numbers as written.")
        if (state.spec.constraints.isEmpty()) Text("No constraints yet")
        state.preview.forEach { ConstraintRow(it, actions.onRemoveConstraint) }
    }
    template?.let { kind ->
        ConstraintDialog(kind, state.registry.dimensions.map { it.key }, { actions.onAddConstraint(it); template = null }, { template = null })
    }
}

@Composable
private fun ConstraintRow(row: ConstraintPreviewRow, onRemove: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(row.text)
            Text(row.verdict.label, style = MaterialTheme.typography.labelMedium)
        }
        TextButton(onClick = { onRemove(row.id) }) { Text("Remove constraint ${row.id}") }
    }
}

private enum class TemplateKind(val title: String) {
    SUM("Parts sum to total"),
    EQUAL("Equal"),
    OFFSET("Offset"),
}

@Composable
private fun ConstraintDialog(kind: TemplateKind, keys: List<String>, onAdd: (ConstraintTemplate) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf(keys.firstOrNull().orEmpty()) }
    var second by remember { mutableStateOf(keys.getOrNull(1).orEmpty()) }
    var parts by remember { mutableStateOf(emptySet<String>()) }
    var offset by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(kind.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyPicker(if (kind == TemplateKind.SUM) "Total" else "First", first, keys) { first = it }
                if (kind == TemplateKind.SUM) {
                    OutlinedTextField(label, { label = it }, label = { Text("Check name") }, singleLine = true)
                    keys.filter { it != first }.forEach { key ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = key in parts, onCheckedChange = { parts = if (it) parts + key else parts - key })
                            Text(key)
                        }
                    }
                } else {
                    KeyPicker("Second", second, keys) { second = it }
                }
                if (kind == TemplateKind.OFFSET) OutlinedTextField(offset, { offset = it }, label = { Text("First minus second") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(buildTemplate(kind, DialogInput(first, second, keys.filter { it in parts }, offset, label))) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private class DialogInput(val first: String, val second: String, val parts: List<String>, val offset: String, val label: String)

private fun buildTemplate(kind: TemplateKind, input: DialogInput): ConstraintTemplate = when (kind) {
    TemplateKind.SUM -> ConstraintTemplate.PartsSum(input.first, input.parts, input.label)
    TemplateKind.EQUAL -> ConstraintTemplate.Equality(input.first, input.second)
    TemplateKind.OFFSET -> ConstraintTemplate.OffsetBy(input.first, input.second, input.offset)
}

@Composable
private fun KeyPicker(title: String, selected: String, keys: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = true }) { Text("$title: $selected") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            keys.forEach { key -> DropdownMenuItem(text = { Text(key) }, onClick = { onPick(key); open = false }) }
        }
    }
}

private const val SUGGESTIONS_SHOWN = 12
