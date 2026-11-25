// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.components.TableMessages

/**
 * The foreign edit met an unsaved draft (S14): Keep mine, Take theirs, or Edit as markdown. Back and Esc only
 * dismiss it ([onDismiss]); the draft and the commit hold stay, and [ResolveChip] reopens it. Shared by the sheet
 * and the inline table.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TableConflictBanner(
    draftAddress: String?,
    onKeepMine: () -> Unit,
    onTakeTheirs: () -> Unit,
    onEditAsMarkdown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = modifier.fillMaxWidth().testTag("conflictBanner")) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                TableMessages.CONFLICT_TITLE,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (draftAddress != null) Text(TableMessages.conflictBannerDraft(draftAddress), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onKeepMine, modifier = Modifier.heightIn(min = 48.dp).testTag("conflictKeepMine")) { Text(TableMessages.KEEP_MINE) }
                TextButton(onClick = onTakeTheirs, modifier = Modifier.heightIn(min = 48.dp).testTag("conflictTakeTheirs")) { Text(TableMessages.TAKE_THEIRS) }
                TextButton(onClick = onEditAsMarkdown, modifier = Modifier.heightIn(min = 48.dp).testTag("conflictEditMarkdown")) { Text(TableMessages.EDIT_AS_MARKDOWN) }
            }
        }
    }
}

/** The persistent chip a dismissed banner leaves behind while commits are held. */
@Composable
internal fun ResolveChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onClick,
        label = { Text(TableMessages.RESOLVE) },
        modifier = modifier.heightIn(min = 48.dp).testTag("resolveChip"),
    )
}
