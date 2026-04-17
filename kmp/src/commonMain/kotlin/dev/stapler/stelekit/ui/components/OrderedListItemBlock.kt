package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
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
@Composable
internal fun OrderedListItemBlock(
    content: String,
    number: Int,
    linkColor: Color,
    onStartEditing: () -> Unit,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strippedContent = remember(content) {
        content.trimStart().dropWhile { it.isDigit() }.removePrefix(".").removePrefix(")").trimStart()
    }

    Row(modifier = modifier.clickable { onStartEditing() }) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.width(32.dp),
        )
        WikiLinkText(
            text = strippedContent,
            textColor = MaterialTheme.colorScheme.onBackground,
            linkColor = linkColor,
            resolvedRefs = emptyMap(),
            onLinkClick = onLinkClick,
            onClick = onStartEditing,
            modifier = Modifier.weight(1f),
        )
    }
}
