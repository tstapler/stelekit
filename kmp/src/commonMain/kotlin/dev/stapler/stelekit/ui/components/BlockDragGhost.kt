package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Translucent ghost composable shown while dragging one or more blocks.
 * Rendered as a floating overlay — position is handled by the caller.
 */
@Composable
fun BlockDragGhost(
    draggedCount: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .graphicsLayer(alpha = 0.75f)
            .widthIn(min = 120.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = if (draggedCount == 1) "1 block" else "$draggedCount blocks",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
