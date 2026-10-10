package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Renders an ordered list item (e.g. `1. Text`) with a numeric marker
 * and inline markdown support via [WikiLinkText].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OrderedListItemBlock(
    content: String,
    number: Int,
    linkColor: Color,
    onStartEditing: () -> Unit,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isInSelectionMode: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onLongPressSelect: (() -> Unit)? = null,
) {
    val strippedContent = remember(content) {
        content.trimStart().dropWhile { it.isDigit() }.removePrefix(".").removePrefix(")").trimStart()
    }

    Row(modifier = modifier.height(IntrinsicSize.Min)) {
        // The number marker is a disjoint tap region from WikiLinkText below (fixed 32dp
        // width vs. weight(1f) for the rest of the row), so giving it its own
        // combinedClickable doesn't reintroduce a dueling-recognizer race -- it's a second
        // recognizer over different pixels, not the same ones. Previously this label had no
        // gesture handling of its own (was only covered by the row-level `clickable` this
        // fix's consolidation removed without replacing), so tapping it silently did nothing.
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.width(32.dp).fillMaxHeight().combinedClickable(
                onLongClick = onLongPressSelect,
                onClick = { if (isInSelectionMode) onToggleSelect() else onStartEditing() },
            ),
        )
        WikiLinkText(
            text = strippedContent,
            textColor = MaterialTheme.colorScheme.onBackground,
            linkColor = linkColor,
            resolvedRefs = emptyMap(),
            onLinkClick = onLinkClick,
            onClick = onStartEditing,
            isInSelectionMode = isInSelectionMode,
            onToggleSelect = onToggleSelect,
            onLongPressSelect = onLongPressSelect,
            modifier = Modifier.weight(1f),
        )
    }
}
