package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
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
    modifier: Modifier = Modifier,
    /** docs/ux/block-reorder-permutations.md §8 gap #1: the nearest candidate under the
     * pointer is inside the dragged selection's own subtree — releasing here is a no-op.
     * Swaps to an error treatment so that's visible during the drag, not only discovered
     * after release. */
    isBlocked: Boolean = false,
) {
    Card(
        modifier = modifier
            .graphicsLayer(alpha = 0.75f)
            .widthIn(min = 120.dp),
        colors = if (isBlocked) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (isBlocked) Icons.Default.Block else Icons.Default.ContentCopy,
                contentDescription = if (isBlocked) "Can't drop here" else null,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = if (draggedCount == 1) "1 block" else "$draggedCount blocks",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
