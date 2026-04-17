package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.useLongPressForDrag

/**
 * The left-side gutter of a block row: drag handle, collapse/expand toggle,
 * bullet point, and optional debug level indicator.
 */
@Composable
internal fun BlockGutter(
    level: Int,
    isDebugMode: Boolean,
    hasChildren: Boolean,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    isSelected: Boolean = false,
    isInSelectionMode: Boolean = false,
    onToggleSelect: () -> Unit = {},
    blockUuid: String = "",
    onDragStart: (uuid: String, startY: Float) -> Unit = { _, _ -> },
    onDrag: (deltaY: Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    var isDragging by remember { mutableStateOf(false) }
    var isHovered by remember { mutableStateOf(false) }
    val useLongPress = useLongPressForDrag()

    // Drag Handle or Checkbox (in selection mode)
    if (isInSelectionMode) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggleSelect() },
            modifier = Modifier.size(18.dp)
        )
    } else {
        Icon(
            imageVector = Icons.Default.DragHandle,
            contentDescription = "Drag to move",
            modifier = Modifier
                .size(18.dp)
                .padding(end = 4.dp)
                .graphicsLayer(alpha = if (isHovered || isDragging) 1f else 0.15f)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Enter -> isHovered = true
                                PointerEventType.Exit -> isHovered = false
                                else -> {}
                            }
                        }
                    }
                }
                .pointerInput(useLongPress) {
                    if (useLongPress) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { startOffset ->
                                isDragging = true
                                isHovered = false
                                onDragStart(blockUuid, startOffset.y)
                            },
                            onDragEnd = {
                                isDragging = false
                                onDragEnd()
                            },
                            onDragCancel = {
                                isDragging = false
                                onDragEnd()
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.y)
                            }
                        )
                    } else {
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                isDragging = true
                                isHovered = false
                                onDragStart(blockUuid, startOffset.y)
                            },
                            onDragEnd = {
                                isDragging = false
                                onDragEnd()
                            },
                            onDragCancel = {
                                isDragging = false
                                onDragEnd()
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.y)
                            }
                        )
                    }
                },
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // Collapse/expand indicator (caret)
    if (hasChildren) {
        Icon(
            imageVector = if (isCollapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
            contentDescription = if (isCollapsed) "Expand" else "Collapse",
            modifier = Modifier
                .size(18.dp)
                .clickable { onToggleCollapse() }
                .padding(end = 4.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Spacer(modifier = Modifier.width(18.dp))
        Spacer(modifier = Modifier.width(4.dp))
    }

    // Bullet point (Always shown)
    Box(
        modifier = Modifier
            .padding(end = 12.dp, top = 10.dp)
            .size(6.dp)
            .background(
                color = StelekitTheme.colors.bullet,
                shape = androidx.compose.foundation.shape.CircleShape
            )
    )

    // DEBUG: Show level to diagnose indentation issues
    if (isDebugMode) {
        Text(
            text = "L$level",
            style = MaterialTheme.typography.labelSmall,
            color = androidx.compose.ui.graphics.Color.Red
        )
    }
}
