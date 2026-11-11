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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.projectbridge.AttachedSheetRow

/** The project's attached drawings with Add and Remove. Remove detaches only; the drawing file stays. */
@Composable
fun AttachSheetsPanel(
    rows: List<AttachedSheetRow>,
    onAdd: () -> Unit,
    onRemove: (contentHash: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(DrawingText.HEADING, style = MaterialTheme.typography.titleMedium)
        if (rows.isEmpty()) Text(DrawingText.EMPTY)
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(row.summary)
                TextButton(onClick = { onRemove(row.contentHash) }) { Text("Remove") }
            }
        }
        TextButton(onClick = onAdd) { Text(DrawingText.ADD) }
    }
}
