// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.calibration.LensMode
import dev.stapler.stelekit.calibration.MultiPointCalibration
import dev.stapler.stelekit.model.MeasurementUnit

/** Everything the ruler tick panel can ask the editor to do. */
class RulerTickPanelActions(
    val onValueChange: (index: Int, text: String) -> Unit,
    val onRemove: (index: Int) -> Unit,
    val onUnitChange: (MeasurementUnit) -> Unit,
    val onLensModeChange: (LensMode) -> Unit,
    val onApply: () -> Unit,
    val onApplyAndSaveProfile: () -> Unit = {},
    val onCancel: () -> Unit,
)

private val LENS_CHOICES: List<Pair<String, LensMode>> = listOf(
    "Profile" to LensMode.Profile,
    "Auto" to LensMode.Auto,
    "Off" to LensMode.Off,
    "Typical phone" to LensMode.Fixed(MultiPointCalibration.PRESET_K.getValue("Typical phone")),
)

/**
 * Panel for the "Mark ruler ticks" calibration: one value field per tapped tick, the live fit
 * (scale, worst tick error, chosen lens k) and Apply / Cancel. Ticks are placed by tapping the image
 * with the Calibrate tool, so the nudge, loupe and zoom/pan placement aids all apply.
 */
@Composable
fun RulerTickPanel(
    session: RulerTickSession,
    preview: RulerTickPreview,
    actions: RulerTickPanelActions,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ruler ticks", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                UnitPicker(session.unit, actions.onUnitChange)
            }
            Text(
                text = "Tap each tick on the ruler, then type its position (for example 0, 10, 20, 30). More ticks over a longer span is more accurate.",
                style = MaterialTheme.typography.bodySmall,
            )
            Column(
                modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                session.entries.forEachIndexed { index, entry ->
                    TickRow(index, entry, session.unit, preview.entryResidualsPx.getOrNull(index), actions)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Lens", style = MaterialTheme.typography.labelMedium)
                LENS_CHOICES.forEach { (label, mode) ->
                    FilterChip(
                        selected = session.lensMode == mode,
                        onClick = { actions.onLensModeChange(mode) },
                        label = { Text(label) },
                    )
                }
            }
            Text(preview.summary, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = actions.onCancel) { Text("Cancel") }
                if (session.lensMode == LensMode.Auto) {
                    TextButton(onClick = actions.onApplyAndSaveProfile, enabled = preview.canApply) { Text("Apply and save camera profile") }
                }
                Button(onClick = actions.onApply, enabled = preview.canApply) { Text("Apply calibration") }
            }
        }
    }
}

@Composable
private fun TickRow(index: Int, entry: RulerTickEntry, unit: MeasurementUnit, residualPx: Double?, actions: RulerTickPanelActions) {
    val valid = parseTickPositionToMeters(entry.valueText, unit) != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Tick ${index + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(56.dp))
        OutlinedTextField(
            value = entry.valueText,
            onValueChange = { actions.onValueChange(index, it) },
            label = { Text("Position (${unit.symbol()})") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            isError = entry.valueText.isNotBlank() && !valid,
            supportingText = residualPx?.let { { Text("off by ${tickResidualText(it)}") } },
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { actions.onRemove(index) }) {
            Icon(Icons.Default.Close, contentDescription = "Remove tick ${index + 1}")
        }
    }
}

@Composable
private fun UnitPicker(unit: MeasurementUnit, onUnitChange: (MeasurementUnit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(unit.symbol()) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MeasurementUnit.entries.filter { it != MeasurementUnit.FEET_INCHES }.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.symbol()) },
                    onClick = {
                        onUnitChange(choice)
                        expanded = false
                    },
                )
            }
        }
    }
}
