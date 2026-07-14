package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Renders a thematic break (horizontal rule) for `---`, `***`, `___` blocks.
 * Tapping the divider enters edit mode for the block.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ThematicBreakBlock(
    onStartEditing: () -> Unit,
    modifier: Modifier = Modifier,
    isInSelectionMode: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onLongPressSelect: (() -> Unit)? = null,
) {
    HorizontalDivider(
        modifier = modifier
            .padding(vertical = 8.dp)
            .combinedClickable(
                onLongClick = onLongPressSelect,
                onClick = { if (isInSelectionMode) onToggleSelect() else onStartEditing() },
            ),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
