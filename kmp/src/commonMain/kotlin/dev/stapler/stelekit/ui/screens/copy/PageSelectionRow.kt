// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.copy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One selectable page. The whole row is a `toggleable(role = Checkbox)` node; its label (full
 * name, never truncated) is in `contentDescription`, so a screen reader says e.g. "Roadmap,
 * 14 blocks, checked". Space toggles; Enter is swallowed so it never acts from inside the list.
 * Grows with font scale (name wraps to 2 lines, then ellipsises) and keeps a 48dp target.
 */
@Composable
fun PageSelectionRow(
    row: PageRowState,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onPreviewKeyEvent { e ->
                when {
                    e.type != KeyEventType.KeyDown -> false
                    e.key == Key.Spacebar -> { onToggle(); true }
                    e.key == Key.Enter || e.key == Key.NumPadEnter -> true
                    else -> false
                }
            }
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics(mergeDescendants = true) { contentDescription = row.label }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.size(24.dp))
        Text(
            text = row.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val detail = listOfNotNull(
            if (row.isJournal) "journal" else null,
            row.blockCount?.let { "$it ${if (it == 1) "block" else "blocks"}" },
            row.subtitle,
        ).joinToString(" - ")
        if (detail.isNotEmpty()) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Placeholder row shown while the first page loads; fixed height so nothing jumps. No animation. */
@Composable
fun PageSelectionSkeletonRow(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 16.dp)) {
        Box(Modifier.fillMaxWidth(0.6f).height(16.dp).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}
