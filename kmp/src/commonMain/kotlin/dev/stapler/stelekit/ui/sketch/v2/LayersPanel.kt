// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.RenameLayer
import dev.stapler.stelekit.sketch.v2.commands.SetLayerLocked
import dev.stapler.stelekit.sketch.v2.commands.SetLayerOpacity
import dev.stapler.stelekit.sketch.v2.commands.SetLayerVisible
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2

/** Touch target floor in dp (one floor across the editor, ux.md section 1 `toolTarget`). */
private val TARGET = 44.dp

private const val PERCENT = 100
private const val OPACITY_STEP_PERCENT = 10

object LayersPanelTags {
    const val NEW_LAYER = "sketch.layers.new"
    fun row(id: LayerId) = "sketch.layers.row.${id.value}"
    fun visible(id: LayerId) = "sketch.layers.visible.${id.value}"
    fun locked(id: LayerId) = "sketch.layers.locked.${id.value}"
    fun up(id: LayerId) = "sketch.layers.up.${id.value}"
    fun down(id: LayerId) = "sketch.layers.down.${id.value}"
    fun name(id: LayerId) = "sketch.layers.name.${id.value}"
    fun rename(id: LayerId) = "sketch.layers.rename.${id.value}"
    fun delete(id: LayerId) = "sketch.layers.delete.${id.value}"
    const val RENAME_FIELD = "sketch.layers.rename.field"
    const val RENAME_ERROR = "sketch.layers.rename.error"
    const val OPACITY_FIELD = "sketch.layers.opacity.field"
    const val OPACITY_MINUS = "sketch.layers.opacity.minus"
    const val OPACITY_PLUS = "sketch.layers.opacity.plus"
    const val DELETE_ALL = "sketch.layers.dialog.deleteAll"
    const val DELETE_MOVE = "sketch.layers.dialog.move"
    const val DELETE_CANCEL = "sketch.layers.dialog.cancel"
    const val DIALOG_TITLE = "sketch.layers.dialog.title"
}

/**
 * Layers panel (Story 3.5.2a). Stateless over [scene]: every edit goes out as a [Command] through [onCommand], which
 * returns the failure when the host refused it so the panel can say why (a name collision keeps the field open).
 * Nothing renders until a second layer exists (UX-19); the overflow item creates it through [LayersPanelModel.newLayer].
 */
@Composable
fun LayersPanel(
    scene: SketchSceneV2,
    activeLayer: LayerId,
    onActiveLayer: (LayerId) -> Unit,
    onCommand: (Command) -> CommandError?,
    onNewLayer: () -> Unit,
    modifier: Modifier = Modifier,
    onVisibilityChange: (LayerId, Boolean) -> Unit = { _, _ -> },
) {
    if (!LayersPanelModel.isVisible(scene)) return
    var renaming by remember { mutableStateOf<LayerId?>(null) }
    var renameError by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<DeleteLayerDialog?>(null) }
    val rows = LayersPanelModel.rows(scene, LayersPanelModel.activeOrBase(scene, activeLayer))

    Column(modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onNewLayer, modifier = Modifier.heightIn(min = TARGET).testTag(LayersPanelTags.NEW_LAYER)) { Text("+ New layer") }
        for (row in rows) {
            val id = row.layer.id
            Row(
                Modifier.fillMaxWidth().testTag(LayersPanelTags.row(id)).padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SwitchCell(LayersPanelTags.visible(id), row.visibilityLabel, row.layer.visible, if (row.layer.visible) "Hide" else "Show") {
                    onCommand(SetLayerVisible(id, !row.layer.visible))
                    onVisibilityChange(id, !row.layer.visible)
                }
                SwitchCell(LayersPanelTags.locked(id), row.lockLabel, row.layer.locked, if (row.layer.locked) "Unlock" else "Lock") {
                    onCommand(SetLayerLocked(id, !row.layer.locked))
                }
                Column(Modifier.weight(1f).testTag(LayersPanelTags.name(id)).heightIn(min = TARGET).clickable { onActiveLayer(id) }) {
                    Text(row.layer.name, style = MaterialTheme.typography.bodyLarge)
                    if (row.active) Text("Active", style = MaterialTheme.typography.labelSmall)
                }
                Text("${row.elementCount}")
                TextButton(
                    onClick = { LayersPanelModel.moveUp(scene, id)?.let(onCommand) },
                    enabled = row.canMoveUp,
                    modifier = Modifier.heightIn(min = TARGET).widthIn(min = TARGET).testTag(LayersPanelTags.up(id)).semantics { contentDescription = "Move layer ${row.layer.name} up" },
                ) { Text("Up") }
                TextButton(
                    onClick = { LayersPanelModel.moveDown(scene, id)?.let(onCommand) },
                    enabled = row.canMoveDown,
                    modifier = Modifier.heightIn(min = TARGET).widthIn(min = TARGET).testTag(LayersPanelTags.down(id)).semantics { contentDescription = "Move layer ${row.layer.name} down" },
                ) { Text("Down") }
                TextButton(
                    onClick = { renaming = id; renameError = null },
                    modifier = Modifier.heightIn(min = TARGET).testTag(LayersPanelTags.rename(id)).semantics { contentDescription = "Rename layer ${row.layer.name}" },
                ) { Text("Rename") }
                TextButton(
                    onClick = {
                        val dialog = LayersPanelModel.deleteDialog(scene, id)
                        if (dialog != null) confirming = dialog else onCommand(dev.stapler.stelekit.sketch.v2.commands.DeleteLayer(id))
                    },
                    enabled = row.deleteDisabledReason == null,
                    modifier = Modifier.heightIn(min = TARGET).testTag(LayersPanelTags.delete(id)).semantics {
                        contentDescription = row.deleteDisabledReason?.let { "Delete layer ${row.layer.name}, disabled: $it" } ?: "Delete layer ${row.layer.name}"
                    },
                ) { Text("Delete") }
            }
            row.deleteDisabledReason?.let { Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp)) }
            if (renaming == id) {
                RenameField(row.layer.name, renameError, onDone = { new ->
                    val failure = onCommand(RenameLayer(id, new))
                    if (failure == null) renaming = null else renameError = LayersPanelModel.errorText(failure) ?: "Could not rename"
                }, onCancel = { renaming = null })
            }
            if (row.active) OpacityRow(row.layer.name, row.layer.opacity) { onCommand(SetLayerOpacity(id, it)) }
        }
    }

    confirming?.let { dialog ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(dialog.title, modifier = Modifier.testTag(LayersPanelTags.DIALOG_TITLE)) },
            text = { Text("This can be undone.") },
            confirmButton = {
                Column {
                    TextButton(onClick = { onCommand(dialog.deleteAll); confirming = null }, modifier = Modifier.testTag(LayersPanelTags.DELETE_ALL)) { Text(DeleteLayerDialog.DELETE_ALL_LABEL) }
                    TextButton(onClick = { onCommand(dialog.moveToBase); confirming = null }, modifier = Modifier.testTag(LayersPanelTags.DELETE_MOVE)) { Text(DeleteLayerDialog.MOVE_LABEL) }
                }
            },
            dismissButton = { TextButton(onClick = { confirming = null }, modifier = Modifier.testTag(LayersPanelTags.DELETE_CANCEL)) { Text(DeleteLayerDialog.CANCEL_LABEL) } },
        )
    }
}

/** An eye or lock switch: one merged node that reads "Layer Walls, visible" with the Switch role (UX-17). */
@Composable
private fun SwitchCell(tag: String, label: String, on: Boolean, actionWord: String, onToggle: () -> Unit) {
    Row(
        Modifier.heightIn(min = TARGET).widthIn(min = TARGET).testTag(tag)
            .toggleable(value = on, role = Role.Switch, onValueChange = { onToggle() })
            .semantics { contentDescription = label; stateDescription = if (on) "On" else "Off" },
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(actionWord, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 4.dp)) }
}

@Composable
private fun RenameField(current: String, error: String?, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember(current) { mutableStateOf(current) }
    Column(Modifier.padding(start = 8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Layer name") },
            singleLine = true,
            isError = error != null,
            modifier = Modifier.testTag(LayersPanelTags.RENAME_FIELD),
        )
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(LayersPanelTags.RENAME_ERROR))
        Row {
            TextButton(onClick = { onDone(text) }) { Text("Save name") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/** Layer opacity: a numeric field with minus and plus buttons beside it (a slider alone would be a drag, WCAG 2.5.7). */
@Composable
private fun OpacityRow(name: String, opacity: Double, onSet: (Double) -> Unit) {
    val percent = (opacity * PERCENT).toInt()
    var text by remember(percent) { mutableStateOf(percent.toString()) }
    Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Opacity ($name)")
        TextButton(onClick = { onSet(((percent - OPACITY_STEP_PERCENT).coerceAtLeast(0)) / PERCENT.toDouble()) }, modifier = Modifier.heightIn(min = TARGET).testTag(LayersPanelTags.OPACITY_MINUS)) { Text("-10") }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; it.toIntOrNull()?.takeIf { v -> v in 0..PERCENT }?.let { v -> onSet(v / PERCENT.toDouble()) } },
            singleLine = true,
            modifier = Modifier.widthIn(max = 96.dp).testTag(LayersPanelTags.OPACITY_FIELD),
        )
        TextButton(onClick = { onSet(((percent + OPACITY_STEP_PERCENT).coerceAtMost(PERCENT)) / PERCENT.toDouble()) }, modifier = Modifier.heightIn(min = TARGET).testTag(LayersPanelTags.OPACITY_PLUS)) { Text("+10") }
    }
}
