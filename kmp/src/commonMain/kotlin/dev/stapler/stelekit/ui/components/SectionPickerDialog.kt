package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sections.SectionDefinition

/**
 * Dialog for selecting which section a page should be moved to.
 * Always includes a "(Global)" option (empty sectionId).
 * Highlights the currently assigned section.
 */
@Composable
fun SectionPickerDialog(
    sections: List<SectionDefinition>,
    currentSectionId: String,
    onSelect: (sectionId: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {

    var selected by remember(currentSectionId) { mutableStateOf(currentSectionId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to Section") },
        text = {
            LazyColumn {
                item {
                    SectionPickerRow(
                        label = "(Global)",
                        isSelected = selected.isEmpty(),
                        onClick = { selected = "" },
                    )
                }
                items(sections) { section ->
                    SectionPickerRow(
                        label = section.displayName,
                        isSelected = selected == section.id,
                        onClick = { selected = section.id },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(selected); onDismiss() }) {
                Text("Move")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun SectionPickerRow(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
