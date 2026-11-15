// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.metersToDisplayString
import kotlinx.coroutines.launch

/** Everything the Known distances sheet can ask its host to do; the host wires these to the ViewModel (Task 3.6d). */
data class KnownDistancesActions(
    val onDismiss: () -> Unit,
    /** Closes the sheet and arms DISTANCE with `pendingKnownDistance` (F11). */
    val onDrawKnownDistance: () -> Unit,
    val onUsePaper: () -> Unit,
    /** Opens the value editor for the row's measurement in known-distance mode. */
    val onEditValue: (constraintId: String) -> Unit,
    val onRename: (constraintId: String, name: String) -> Unit,
    /** The owner confirmed the scale-change warning; delete the row and re-fit. */
    val onDeleteConfirmed: (constraintId: String) -> Unit,
    /** The Undo snackbar after a delete was tapped. */
    val onUndoDelete: () -> Unit,
    val onUseAnyway: (constraintId: String) -> Unit,
    val onRemeasure: (constraintId: String) -> Unit,
    /** Opens the existing Calibrate sheet; the way out of a legacy-basis photo. */
    val onCalibrateLegacy: () -> Unit,
    val onUseBle: (() -> Unit)? = null,
    val onMarkRulerTicks: (() -> Unit)? = null,
)

/** Modal host of [KnownDistancesContent]; [onDismiss][KnownDistancesActions.onDismiss] covers the [x], Back and scrim exits. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnownDistancesSheet(
    presentation: CalibrationPresentation,
    displayUnit: MeasurementUnit,
    actions: KnownDistancesActions,
    modifier: Modifier = Modifier,
    affectedMeasurementCount: Int = 0,
    footer: String? = null,
    /** True while touch exploration or a switch service is on; makes the Undo snackbar indefinite (UX-61). */
    assistiveServiceActive: Boolean = false,
) {
    ModalBottomSheet(onDismissRequest = actions.onDismiss, modifier = modifier) {
        KnownDistancesContent(presentation, displayUnit, actions, affectedMeasurementCount = affectedMeasurementCount, footer = footer, assistiveServiceActive = assistiveServiceActive)
    }
}

@Composable
fun KnownDistancesContent(
    presentation: CalibrationPresentation,
    displayUnit: MeasurementUnit,
    actions: KnownDistancesActions,
    modifier: Modifier = Modifier,
    affectedMeasurementCount: Int = 0,
    footer: String? = null,
    /** True while touch exploration or a switch service is on; makes the Undo snackbar indefinite (UX-61). */
    assistiveServiceActive: Boolean = false,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<ConstraintRow?>(null) }
    var renaming by remember { mutableStateOf<ConstraintRow?>(null) }

    Box(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SheetHeader(presentation, actions.onDismiss)
            if (presentation.rows.isNotEmpty()) {
                Column(Modifier.testTag("known_distance_rows")) {
                    for (row in presentation.rows) {
                        DistanceRow(row, displayUnit, actions, onDelete = { pendingDelete = row }, onRename = { renaming = row })
                    }
                }
            }
            presentation.emptyState?.let { Text(it.explanation, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp)) }
            for (hint in presentation.hints) {
                Text(hint, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
            }
            footer?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            AddActions(presentation, actions)
            MoreWays(actions)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    pendingDelete?.let { row ->
        CalibrationChangeWarningDialog(
            existingAnnotationCount = affectedMeasurementCount,
            onKeepCurrentScale = { pendingDelete = null },
            onChangeScale = {
                pendingDelete = null
                actions.onDeleteConfirmed(row.id)
                scope.launch {
                    val message = "Known distance removed. $affectedMeasurementCount ${if (affectedMeasurementCount == 1) "measurement" else "measurements"} changed."
                    if (snackbar.showUndo(message, assistiveServiceActive)) actions.onUndoDelete()
                }
            },
        )
    }
    renaming?.let { row -> RenameDialog(row, onDone = { name -> renaming = null; if (name != null) actions.onRename(row.id, name) }) }
}

@Composable
private fun SheetHeader(presentation: CalibrationPresentation, onDismiss: () -> Unit) = Column {
    val heading = presentation.emptyState?.heading ?: "Known distances"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            heading,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f).testTag("sheet_heading").semantics { heading() },
        )
        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
    }
    if (presentation.pill.text != heading) {
        Text(presentation.pill.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
    }
    presentation.pill.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun DistanceRow(
    row: ConstraintRow,
    unit: MeasurementUnit,
    actions: KnownDistancesActions,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag("known_distance_row").heightIn(min = 56.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Edit value") { actions.onEditValue(row.id) },
                verticalArrangement = Arrangement.Center,
            ) {
                Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                val residual = row.residualPct?.let { " - ${tenths(it)}% off" }.orEmpty()
                Text(metersToDisplayString(row.distanceMeters, unit) + residual, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onRename, modifier = Modifier.heightIn(min = 48.dp)) { Text("Rename") }
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete ${row.name}")
            }
        }
        if (row.flagged && row.flagText != null) {
            Text(row.flagText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.onRemeasure(row.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Re-measure") }
                OutlinedButton(onClick = { actions.onUseAnyway(row.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Use anyway") }
            }
        }
    }
}

@Composable
private fun AddActions(presentation: CalibrationPresentation, actions: KnownDistancesActions) = Column {
    val enabled = presentation.knownDistancesEnabled
    presentation.disabledReason?.let { reason ->
        Text(reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        TextButton(onClick = actions.onCalibrateLegacy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Calibrate") }
    }
    Button(
        onClick = actions.onDrawKnownDistance,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp),
    ) { Text(presentation.emptyState?.primaryAction ?: "Draw a known distance") }
    OutlinedButton(
        onClick = actions.onUsePaper,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(presentation.emptyState?.secondaryAction ?: "Use a card or sheet of paper") }
}

@Composable
private fun MoreWays(actions: KnownDistancesActions) = Column {
    if (actions.onUseBle == null && actions.onMarkRulerTicks == null) return@Column
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
        Text("More ways", modifier = Modifier.padding(start = 4.dp))
    }
    if (!open) return@Column
    actions.onMarkRulerTicks?.let {
        OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Mark ruler ticks (most accurate)") }
    }
    actions.onUseBle?.let {
        OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Use BLE laser") }
    }
}

@Composable
private fun RenameDialog(row: ConstraintRow, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf(row.name) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Rename known distance") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onDone(name.trim().ifEmpty { null }) }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}

private fun tenths(v: Double): String {
    val t = kotlin.math.round(v * 10).toLong()
    return "${t / 10}.${t % 10}"
}
