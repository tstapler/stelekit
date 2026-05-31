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
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
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
 * Active tool is highlighted with the primaryContainer color background.
 * Measurement tools (DISTANCE, AREA, ANGLE, LABEL, GRID_REF) are disabled until the image is
 * calibrated ([isCalibrated] = true). SELECT and CALIBRATE are always enabled.
 * Undo/redo buttons are disabled (alpha reduced) when no history is available.
 */
@Composable
fun AnnotationToolbar(
    currentTool: AnnotationTool,
    canUndo: Boolean,
    canRedo: Boolean,
    displayUnit: MeasurementUnit?,
    isCalibrated: Boolean,
    onToolSelect: (AnnotationTool) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDeleteSelect: () -> Unit,
    onCalibrate: () -> Unit,
    onUnitSelect: (MeasurementUnit) -> Unit,
    hasSelection: Boolean,
    showLabels: Boolean = true,
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
                enabled = true,
                showLabels = showLabels,
            )
            ToolButton(
                tool = AnnotationTool.DISTANCE,
                icon = Icons.Default.LinearScale,
                label = "Distance",
                isActive = currentTool == AnnotationTool.DISTANCE,
                onSelect = onToolSelect,
                enabled = isCalibrated,
                showLabels = showLabels,
            )
            ToolButton(
                tool = AnnotationTool.AREA,
                icon = Icons.Default.Crop,
                label = "Area",
                isActive = currentTool == AnnotationTool.AREA,
                onSelect = onToolSelect,
                enabled = isCalibrated,
                showLabels = showLabels,
            )
            ToolButton(
                tool = AnnotationTool.ANGLE,
                icon = Icons.Default.GridOn,
                label = "Angle",
                isActive = currentTool == AnnotationTool.ANGLE,
                onSelect = onToolSelect,
                enabled = isCalibrated,
                showLabels = showLabels,
            )
            ToolButton(
                tool = AnnotationTool.LABEL,
                icon = Icons.Default.Label,
                label = "Label",
                isActive = currentTool == AnnotationTool.LABEL,
                onSelect = onToolSelect,
                enabled = isCalibrated,
                showLabels = showLabels,
            )
            ToolButton(
                tool = AnnotationTool.GRID_REF,
                icon = Icons.Default.GridOn,
                label = "Ref",
                isActive = currentTool == AnnotationTool.GRID_REF,
                onSelect = onToolSelect,
                enabled = isCalibrated,
                showLabels = showLabels,
            )
            CalibrateButton(
                onClick = onCalibrate,
                showLabels = showLabels,
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
                    onUnitSelect = onUnitSelect,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolButton(
    tool: AnnotationTool,
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onSelect: (AnnotationTool) -> Unit,
    enabled: Boolean = true,
    showLabels: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val bg = if (isActive && enabled) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val tint = when {
        isActive && enabled -> MaterialTheme.colorScheme.onPrimaryContainer
        !enabled -> Color(0xFF555555)
        else -> Color(0xFFBBBBBB)
    }
    val tooltipState = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text("$label (${toolShortcut(tool)})") } },
        state = tooltipState,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg),
            ) {
                IconButton(onClick = { onSelect(tool) }, enabled = enabled) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = tint,
                    )
                }
            }
            if (showLabels) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive && enabled) MaterialTheme.colorScheme.onPrimaryContainer
                            else if (!enabled) Color(0xFF555555)
                            else Color(0xFFBBBBBB),
                    maxLines = 1,
                )
            }
        }
    }
}

private fun toolShortcut(tool: AnnotationTool): String = when (tool) {
    AnnotationTool.SELECT -> "S"
    AnnotationTool.DISTANCE -> "D"
    AnnotationTool.AREA -> "A"
    AnnotationTool.ANGLE -> "G"
    AnnotationTool.LABEL -> "L"
    AnnotationTool.GRID_REF -> "R"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalibrateButton(
    onClick: () -> Unit,
    showLabels: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val tooltipState = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text("Calibrate (C)") } },
        state = tooltipState,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Transparent),
            ) {
                IconButton(onClick = onClick, enabled = true) {
                    Icon(
                        imageVector = Icons.Default.Straighten,
                        contentDescription = "Calibrate",
                        tint = Color(0xFFBBBBBB),
                    )
                }
            }
            if (showLabels) {
                Text(
                    text = "Calibrate",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFBBBBBB),
                    maxLines = 1,
                )
            }
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
