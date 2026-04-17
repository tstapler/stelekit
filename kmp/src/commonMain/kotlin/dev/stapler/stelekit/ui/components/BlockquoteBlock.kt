package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Renders a blockquote block with a left accent bar and inline markdown support.
 * One level of `> ` prefix is stripped from each line before rendering.
 */
@Composable
internal fun BlockquoteBlock(
    content: String,
    linkColor: Color,
    onStartEditing: () -> Unit,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strippedContent = remember(content) {
        content.lines().joinToString("\n") { line ->
            when {
                line.startsWith("> ") -> line.removePrefix("> ")
                line.startsWith(">") -> line.removePrefix(">")
                else -> line
            }
        }.trim()
    }

    Row(
        modifier = modifier
            .padding(vertical = 4.dp)
            .width(IntrinsicSize.Max),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        )
        Spacer(modifier = Modifier.width(8.dp))
        WikiLinkText(
            text = strippedContent,
            textColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
            linkColor = linkColor,
            resolvedRefs = emptyMap(),
            onLinkClick = onLinkClick,
            onClick = onStartEditing,
            modifier = Modifier.weight(1f),
        )
    }
}
