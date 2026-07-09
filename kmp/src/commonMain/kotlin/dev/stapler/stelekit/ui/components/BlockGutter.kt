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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
        // The draggable/clickable hit area must independently meet the app's 48dp minimum
        // touch target (ux.md criterion 19) — the same "small glyph, large hit box" pattern
        // IconButton uses internally. The gesture detection (pointerInput/detectDragGestures)
        // is attached to this outer 48dp Box, not the 18dp Icon, so the actual draggable
        // region is genuinely 48dp rather than a cosmetic-only padding increase around a
        // small hit area. The contentDescription lives here too so semantics bounds queries
        // (e.g. onNodeWithContentDescription("Drag to move")) measure the real hit area.
        Box(
            modifier = Modifier
                // padding is applied OUTSIDE the fixed-size touch target (as external gutter
                // spacing) — it must not be allowed to shrink the 48dp hit area itself. size()
                // therefore comes after padding so it forces an exact 48dp x 48dp region for
                // everything nested inside it (semantics + both pointerInput gesture detectors).
                .padding(end = 4.dp)
                .size(48.dp)
                .semantics { contentDescription = "Drag to move" }
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
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    // GAP-012 (Story D.3.1): 0.15 alpha made the handle near-invisible until
                    // hover/drag, hiding an already-implemented, step-count-target-meeting
                    // reorder mechanism from new users (corroborated by
                    // docs/ux/journey-map.md's prior-art finding). 0.45 keeps a visible
                    // "idle" affordance while still brightening further on hover/drag.
                    .graphicsLayer(alpha = if (useLongPress || isHovered || isDragging) 1f else 0.45f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
