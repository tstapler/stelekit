// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.calibration.PlanePreview
import dev.stapler.stelekit.calibration.PlaneReference
import dev.stapler.stelekit.calibration.PlaneSession
import dev.stapler.stelekit.calibration.ReferenceRectangle

/** What the plane reference panel can ask the editor to do. */
class PlaneReferenceActions(
    val onRectangle: (ReferenceRectangle) -> Unit,
    val onFirstEdgeLong: (Boolean) -> Unit,
    val onUndoCorner: () -> Unit,
    val onApply: () -> Unit,
    val onCancel: () -> Unit,
)

/**
 * Panel for calibrating from a sheet of paper or a card: pick the rectangle, say which edge corners 1 and 2
 * run along, tap the four corners on the image (so nudge, loupe and zoom/pan apply), then apply.
 */
@Composable
fun PlaneReferencePanel(
    session: PlaneSession,
    preview: PlanePreview,
    actions: PlaneReferenceActions,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Paper or card reference", style = MaterialTheme.typography.titleSmall)
            Text(PlaneReference.GUIDANCE, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReferenceRectangle.entries.forEach { rectangle ->
                    FilterChip(
                        selected = session.rectangle == rectangle,
                        onClick = { actions.onRectangle(rectangle) },
                        label = { Text(rectangle.name.replace("LETTER", "Letter").replace("ID1", "ID-1")) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Corners 1 to 2 run along the", style = MaterialTheme.typography.labelMedium)
                FilterChip(selected = session.firstEdgeIsLong, onClick = { actions.onFirstEdgeLong(true) }, label = { Text("long edge") })
                FilterChip(selected = !session.firstEdgeIsLong, onClick = { actions.onFirstEdgeLong(false) }, label = { Text("short edge") })
            }
            Text(preview.message, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = actions.onUndoCorner, enabled = session.corners.isNotEmpty()) { Text("Undo corner") }
                TextButton(onClick = actions.onCancel) { Text("Cancel") }
                Button(onClick = actions.onApply, enabled = preview.canApply) { Text("Apply") }
            }
        }
    }
}
