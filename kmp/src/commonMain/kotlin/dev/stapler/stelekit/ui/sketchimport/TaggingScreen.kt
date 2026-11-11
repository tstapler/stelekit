// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketchimport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import dev.stapler.stelekit.ui.components.typedItems
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketchimport.KeyError
import dev.stapler.stelekit.sketchimport.TagChoice
import dev.stapler.stelekit.sketchimport.TaggingItem

/** What the PNG calibration step needs from its host: the picked image and the two ways to change state. */
class RasterControls(
    val image: ByteArray?,
    val onEdit: ((RasterState) -> RasterState) -> Unit,
    val onBind: (choice: TagChoice, toReference: Boolean) -> Unit,
)

/** Manual tagging of the labels the binder could not place (Story 4.4). Stateless: state and actions come from the caller. */
@Composable
fun TaggingScreen(
    state: SketchImportUiState,
    onChoose: (ref: String, choice: TagChoice) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    raster: RasterControls? = null,
) {
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val calibrating = state.raster
        if (calibrating == null) {
            Text("Tag labels from ${state.fileName}")
            Text("${state.boundCount} labels matched automatically; ${state.items.size} need a dimension.")
        } else {
            Text("Calibrate ${state.fileName}")
        }
        state.error?.let { Text(it) }
        state.keyError?.let { Text(keyErrorText(it)) }
        if (calibrating != null && raster != null) {
            RasterTaggingPanel(calibrating, state.registryKeys, raster.image, raster.onEdit, raster.onBind, Modifier.weight(1f, fill = false))
        } else {
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                typedItems(state.items, key = { it.ref }) { item ->
                    TaggingRow(item, state.choices[item.ref], state.registryKeys, onChoose)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = state.phase == ImportPhase.Tagging) { Text("Save") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
internal fun TaggingRow(
    item: TaggingItem,
    choice: TagChoice?,
    keys: List<String>,
    onChoose: (String, TagChoice) -> Unit,
) {
    var newKey by remember(item.ref) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        Text("\"${item.text}\"")
        val shown = (item.candidates + keys).distinct()
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            shown.take(MAX_CHOICES).forEach { key ->
                TextButton(onClick = { onChoose(item.ref, TagChoice.Existing(key)) }) {
                    Text(if (choice == TagChoice.Existing(key)) "[$key]" else key)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(newKey, { newKey = it }, Modifier.weight(1f), label = { Text("New key") }, singleLine = true)
            TextButton(onClick = { onChoose(item.ref, TagChoice.New(newKey, newKey)) }, enabled = newKey.isNotBlank()) { Text("Create") }
            TextButton(onClick = { onChoose(item.ref, TagChoice.Skip) }) { Text("Skip") }
        }
    }
}

internal fun keyErrorText(e: KeyError): String = when (e) {
    KeyError.Blank -> "Enter a key name"
    KeyError.BadFormat -> "Use lower-case letters, digits, dot, dash or underscore, e.g. closet.rod.width"
    KeyError.TooLong -> "That key is too long"
    is KeyError.Duplicate -> "${e.key} already exists; pick it from the list"
    is KeyError.Unknown -> "${e.key} is not in the registry"
}

private const val MAX_CHOICES = 6

/** Hosts [TaggingScreen] over a view model that is closed when this leaves composition. */
@Composable
fun SketchImportHost(viewModel: SketchImportViewModel, modifier: Modifier = Modifier) {
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }
    val state by viewModel.state.collectAsState()
    val raster = remember(viewModel, state.raster != null) { RasterControls(viewModel.rasterImage(), viewModel::editRaster, viewModel::bindRaster) }
    TaggingScreen(state, viewModel::choose, viewModel::save, viewModel::cancel, modifier, raster)
}
