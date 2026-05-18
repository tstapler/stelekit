package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.model.MeasurementUnit

/**
 * Bottom toolbar providing tool selection, undo/redo, unit selection, and annotation deletion.
 *
 * Active tool is highlighted with the primary color background.
 * Undo/redo buttons are disabled (alpha reduced) when no history is available.
 */
@Composable
fun AnnotationToolbar(
    currentTool: AnnotationTool,
    canUndo: Boolean,
    canRedo: Boolean,
    displayUnit: MeasurementUnit?,
    onToolSelect: (AnnotationTool) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDeleteSelect: () -> Unit,
    hasSelection: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xEE1A1A1A))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Tool selection row
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(
                tool = AnnotationTool.SELECT,
                icon = Icons.Default.PanTool,
                label = "Select",
                isActive = currentTool == AnnotationTool.SELECT,
                onSelect = onToolSelect,
            )
            ToolButton(
                tool = AnnotationTool.DISTANCE,
                icon = Icons.Default.LinearScale,
                label = "Distance",
                isActive = currentTool == AnnotationTool.DISTANCE,
                onSelect = onToolSelect,
            )
            ToolButton(
                tool = AnnotationTool.AREA,
                icon = Icons.Default.Crop,
                label = "Area",
                isActive = currentTool == AnnotationTool.AREA,
                onSelect = onToolSelect,
            )
            ToolButton(
                tool = AnnotationTool.ANGLE,
                icon = Icons.Default.GridOn,
                label = "Angle",
                isActive = currentTool == AnnotationTool.ANGLE,
                onSelect = onToolSelect,
            )
            ToolButton(
                tool = AnnotationTool.LABEL,
                icon = Icons.Default.Label,
                label = "Label",
                isActive = currentTool == AnnotationTool.LABEL,
                onSelect = onToolSelect,
            )
            ToolButton(
                tool = AnnotationTool.GRID_REF,
                icon = Icons.Default.GridOn,
                label = "Ref",
                isActive = currentTool == AnnotationTool.GRID_REF,
                onSelect = onToolSelect,
            )
        }

        Spacer(Modifier.height(4.dp))

        // Action row: undo, redo, unit selector, delete
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(
                    Icons.Default.Undo,
                    contentDescription = "Undo",
                    tint = if (canUndo) Color.White else Color.Gray,
                )
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(
                    Icons.Default.Redo,
                    contentDescription = "Redo",
                    tint = if (canRedo) Color.White else Color.Gray,
                )
            }

            if (displayUnit != null) {
                Spacer(Modifier.width(8.dp))
                UnitDropdown(
                    currentUnit = displayUnit,
                    onUnitSelect = { /* forwarded via callback in a real app */ },
                )
            }

            if (hasSelection) {
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDeleteSelect) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete selected annotation",
                        tint = Color(0xFFEF5350),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolButton(
    tool: AnnotationTool,
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onSelect: (AnnotationTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f) else Color.Transparent
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg),
    ) {
        IconButton(onClick = { onSelect(tool) }) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) Color.White else Color(0xFFBBBBBB),
            )
        }
    }
}

@Composable
private fun UnitDropdown(
    currentUnit: MeasurementUnit,
    onUnitSelect: (MeasurementUnit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(onClick = { expanded = true }) {
            Text(currentUnit.symbol(), color = Color.White)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            MeasurementUnit.entries.forEach { unit ->
                DropdownMenuItem(
                    text = { Text("${unit.name.lowercase().replaceFirstChar { it.uppercase() }} (${unit.symbol()})") },
                    onClick = {
                        onUnitSelect(unit)
                        expanded = false
                    },
                )
            }
        }
    }
}
