package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * Context menu shown on right-click / long-press of a page-name suggestion span.
 *
 * Uses a non-focusable Popup (same as [LinkSuggestionPopup]) to avoid the
 * focusable-popup race condition where the menu immediately dismisses because
 * a DropdownMenu's outside-click handler fires on the same release event that
 * opened it.
 *
 * Provides three actions:
 * - Link this occurrence as [[Page Name]]
 * - Skip (dismiss) this suggestion
 * - Navigate all suggestions on screen (opens [SuggestionNavigatorPanel])
 */
@Composable
internal fun SuggestionContextMenu(
    canonicalName: String,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onLink: () -> Unit,
    onSkip: () -> Unit,
    onNavigateAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!expanded) return
    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false),
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 4.dp,
            shadowElevation = 4.dp,
            modifier = modifier.widthIn(min = 200.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(
                    text = "Link as [[$canonicalName]]",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDismiss(); onLink() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
                HorizontalDivider()
                Text(
                    text = "Skip this suggestion",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDismiss(); onSkip() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
                HorizontalDivider()
                Text(
                    text = "Navigate all suggestions…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDismiss(); onNavigateAll() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}
