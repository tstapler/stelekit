// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.ui.components.typedItems

object ElementsPanelTags {
    const val LIST = "sketch.elements.list"
    const val EMPTY = "sketch.elements.empty"
    const val RENAME_FIELD = "sketch.elements.rename.field"
    const val RENAME_ERROR = "sketch.elements.rename.error"
    fun layer(id: LayerId) = "sketch.elements.layer.${id.value}"
    fun row(id: ElementId) = "sketch.elements.row.${id.value}"
    fun rename(id: ElementId) = "sketch.elements.rename.${id.value}"
    fun unlock(id: ElementId) = "sketch.elements.unlock.${id.value}"
    fun showLayer(id: ElementId) = "sketch.elements.showlayer.${id.value}"
}

private val TARGET = 44.dp
private const val DIMMED_ALPHA = 0.5f

/**
 * The Elements tab (Story 3.5.7b, UX-110): layers as expandable groups holding their elements, topmost first. A tap
 * (or Enter) on a row calls [onSelect], which selects the element and pans to it ([ElementsPanelModel.reveal]); Enter
 * or F2 on a focused row, or its Rename button, renames in place (a name already used keeps the field open, Esc
 * reverts). A locked element offers [Unlock]; a row on a hidden layer is dimmed, says "(hidden)" and offers "Show
 * layer" instead of selecting. [onCommand] returns the refusal so the field can say why. The list is lazy: only the
 * rows in the visible window are composed.
 */
@Composable
fun ElementsPanel(
    scene: SketchSceneV2,
    selection: Set<ElementId>,
    onSelect: (ElementId) -> Unit,
    onCommand: (Command) -> CommandError?,
    modifier: Modifier = Modifier,
) {
    var collapsed by remember { mutableStateOf<Set<LayerId>>(emptySet()) }
    var renaming by remember { mutableStateOf<ElementId?>(null) }
    val rows = ElementsPanelModel.rows(scene, collapsed)
    if (scene.size == 0) {
        Text(ElementsPanelModel.EMPTY, modifier.padding(8.dp).testTag(ElementsPanelTags.EMPTY))
        return
    }
    LazyColumn(modifier.fillMaxWidth().testTag(ElementsPanelTags.LIST), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        typedItems(rows, key = { it.key }) { row ->
            when (row) {
                is ElementsRow.LayerHeader -> LayerHeaderRow(row) {
                    collapsed = if (row.layer.id in collapsed) collapsed - row.layer.id else collapsed + row.layer.id
                }
                is ElementsRow.Item -> ItemRow(
                    row, row.element.id in selection, renaming == row.element.id,
                    ItemActions(onSelect, onCommand, { renaming = row.element.id }, { renaming = null }),
                )
            }
        }
    }
}

private class ItemActions(
    val onSelect: (ElementId) -> Unit,
    val onCommand: (Command) -> CommandError?,
    val startRename: () -> Unit,
    val stopRename: () -> Unit,
)

@Composable
private fun LayerHeaderRow(row: ElementsRow.LayerHeader, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = TARGET).testTag(ElementsPanelTags.layer(row.layer.id))
            .clickable(onClick = onToggle).clearAndSetSemantics { contentDescription = row.description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (row.expanded) "▾ " else "▸ ")
        Text(row.layer.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text("${row.elementCount}", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ItemRow(row: ElementsRow.Item, selected: Boolean, renaming: Boolean, actions: ItemActions) {
    val id = row.element.id
    Column(Modifier.fillMaxWidth().padding(start = 16.dp).alpha(if (row.dimmed) DIMMED_ALPHA else 1f)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = TARGET).testTag(ElementsPanelTags.row(id))
                .onKeyEvent { event ->
                    val rename = event.type == KeyEventType.KeyDown && (event.key == Key.F2 || event.key == Key.Enter)
                    if (rename) actions.startRename()
                    rename
                }
                .focusable()
                .clickable { activate(row, actions) }
                .semantics(mergeDescendants = true) { contentDescription = row.description; this.selected = selected },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(kindIcon(row.kind), style = MaterialTheme.typography.labelLarge)
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.bodyLarge)
                if (row.textLabel.isNotEmpty()) Text(row.textLabel, style = MaterialTheme.typography.bodySmall)
            }
            if (row.stateText.isNotEmpty()) Text(row.stateText, style = MaterialTheme.typography.labelSmall)
        }
        RowButtons(row, actions)
        if (renaming) RenameField(row, actions)
    }
}

private fun activate(row: ElementsRow.Item, actions: ItemActions) {
    when (val outcome = ElementsPanelModel.activate(row)) {
        is RowOutcome.Select -> actions.onSelect(outcome.id)
        is RowOutcome.LayerHidden -> Unit
    }
}

@Composable
private fun RowButtons(row: ElementsRow.Item, actions: ItemActions) {
    val id = row.element.id
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        when (val outcome = ElementsPanelModel.activate(row)) {
            is RowOutcome.LayerHidden -> TextButton(
                onClick = { actions.onCommand(outcome.showLayer) },
                modifier = Modifier.heightIn(min = TARGET).testTag(ElementsPanelTags.showLayer(id)),
            ) { Text("Show layer") }
            is RowOutcome.Select -> outcome.unlock?.let { unlock ->
                TextButton(
                    onClick = { actions.onCommand(unlock) },
                    modifier = Modifier.heightIn(min = TARGET).testTag(ElementsPanelTags.unlock(id)),
                ) { Text("Unlock") }
            }
        }
        TextButton(
            onClick = actions.startRename,
            modifier = Modifier.heightIn(min = TARGET).testTag(ElementsPanelTags.rename(id))
                .semantics { contentDescription = "Rename ${row.name}" },
        ) { Text("Rename") }
    }
}

@Composable
private fun RenameField(row: ElementsRow.Item, actions: ItemActions) {
    var text by remember(row.name) { mutableStateOf(row.name) }
    var error by remember { mutableStateOf<String?>(null) }
    fun commit() {
        val failure = actions.onCommand(ElementsPanelModel.rename(row.element.id, text))
        if (failure == null) actions.stopRename() else error = ElementsPanelModel.renameError(failure) ?: "Could not rename"
    }
    Column(Modifier.padding(start = 8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; error = null },
            label = { Text("Element name") },
            singleLine = true,
            isError = error != null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier.testTag(ElementsPanelTags.RENAME_FIELD).onKeyEvent { event ->
                val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
                if (escape) actions.stopRename()
                escape
            },
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(ElementsPanelTags.RENAME_ERROR)) }
        Row {
            TextButton(onClick = ::commit) { Text("Save name") }
            TextButton(onClick = actions.stopRename) { Text("Cancel") }
        }
    }
}

/** A one-letter stand-in for the kind icon; the kind word is always in the row's text and semantics too. */
private fun kindIcon(kind: String): String = kind.take(1).uppercase()
