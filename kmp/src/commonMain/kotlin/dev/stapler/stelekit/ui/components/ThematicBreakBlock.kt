package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.clickable
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
@Composable
internal fun ThematicBreakBlock(
    onStartEditing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalDivider(
        modifier = modifier
            .padding(vertical = 8.dp)
            .clickable { onStartEditing() },
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
