// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.calibration.HeightOffset
import dev.stapler.stelekit.model.GeometryCalibration
import dev.stapler.stelekit.model.MeasurementUnit

/**
 * Dialog for the object-height offset: how far the measured edge sits above the surface the ruler
 * lies on. Stateless; [draft] and [preview] come from the view model so the same rules drive the preview and Apply.
 */
@Composable
fun HeightOffsetDialog(
    draft: HeightOffsetDraft,
    preview: HeightOffsetPreview,
    onDraftChange: (HeightOffsetDraft) -> Unit,
    onApply: (GeometryCalibration) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Object height above the ruler") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(HeightOffsetCopy.EXPLANATION, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HeightOffset.PRESETS.forEach { (label, meters) ->
                        AssistChip(
                            onClick = { onDraftChange(draft.copy(heightText = plainMillimetres(meters), heightUnit = MeasurementUnit.MILLIMETERS)) },
                            label = { Text(label) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft.heightText,
                        onValueChange = { onDraftChange(draft.copy(heightText = it)) },
                        label = { Text("Height (${draft.heightUnit.symbol()})") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = draft.distanceText,
                        onValueChange = { onDraftChange(draft.copy(distanceText = it)) },
                        label = { Text("Camera distance (${draft.distanceUnit.symbol()}), optional") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    preview.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (preview.canApply) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { preview.geometry?.let(onApply) }, enabled = preview.canApply) { Text("Apply") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun plainMillimetres(meters: Double): String {
    val mm = meters * 1000.0
    return if (mm == kotlin.math.round(mm)) kotlin.math.round(mm).toLong().toString() else mm.toString()
}

/** Trailing text for the calibration badge: the current height offset, or an invitation to set one. */
fun heightOffsetActionLabel(geometry: GeometryCalibration?): String =
    if (geometry != null && geometry.heightOffsetMeters > 0.0) {
        "Height offset ${plainMillimetres(geometry.heightOffsetMeters)} mm"
    } else {
        "Set object height"
    }
