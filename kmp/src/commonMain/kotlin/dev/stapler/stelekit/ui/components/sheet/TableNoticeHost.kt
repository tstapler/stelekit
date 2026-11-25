// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.table.Notice
import dev.stapler.stelekit.table.NoticeQueue
import dev.stapler.stelekit.ui.components.TableMessages
import kotlinx.coroutines.delay

/** What the pinned slot's three actions do for one [RecoveredDraft]. */
internal class RecoveredDraftActions(
    val onCopy: (RecoveredDraft) -> Unit,
    val onApply: (RecoveredDraft) -> Unit,
    val canApply: (RecoveredDraft) -> Boolean,
    val onDiscard: (RecoveredDraft) -> Unit,
)

/**
 * The sheet's notices (ux.md 3.10): one visible at a time from the shared [NoticeQueue], a polite live region,
 * action-bearing notices stay until acted on or dismissed (WCAG 2.2.1), informational ones leave after
 * [NoticeQueue.AUTO_DISMISS_MS] with the timer paused while the pointer hovers or focus is inside. The
 * [recovered] draft has its own pinned slot above them. The countdown steps by delay, so a test clock drives it.
 */
@Composable
internal fun TableNoticeHost(
    notices: TableNotices,
    modifier: Modifier = Modifier,
    recovered: RecoveredDraft? = null,
    recoveredActions: RecoveredDraftActions? = null,
    landscape: Boolean = false,
) {
    val visible = notices.visible
    Column(modifier = modifier.fillMaxWidth().testTag("tableNoticeHost")) {
        if (recovered != null && recoveredActions != null) RecoveredSlot(recovered, recoveredActions)
        if (visible != null) {
            NoticeRow(
                notice = visible,
                moreCount = notices.moreCount,
                maxLines = if (landscape) 1 else 2,
                onAction = notices::activate,
                onDismiss = notices::dismiss,
            )
        }
    }
}

@Composable
private fun NoticeRow(notice: Notice, moreCount: Int, maxLines: Int, onAction: () -> Unit, onDismiss: () -> Unit) {
    val hoverSource = remember(notice.id) { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    var focused by remember(notice.id) { mutableStateOf(false) }
    val latestDismiss by rememberUpdatedState(onDismiss)
    val paused = hovered || focused
    if (!notice.isActionBearing) {
        var remaining by remember(notice.id) { mutableStateOf(NoticeQueue.AUTO_DISMISS_MS) }
        LaunchedEffect(notice.id, paused) {
            while (!paused && remaining > 0) {
                delay(TICK_MS)
                remaining -= TICK_MS
            }
            if (!paused && remaining <= 0) latestDismiss()
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tableNotice")
            .onFocusChanged { focused = it.hasFocus }
            .hoverable(hoverSource),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp).heightIn(min = 48.dp),
        ) {
            Text(
                text = notice.text,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }.testTag("noticeText"),
            )
            if (moreCount > 0) Text(TableMessages.moreNotices(moreCount), style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("noticeMore"))
            notice.actionLabel?.let { label ->
                TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp).testTag("noticeAction")) { Text(label) }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("noticeDismiss")) { Text(TableMessages.DISMISS) }
        }
    }
}

@Composable
internal fun RecoveredSlot(item: RecoveredDraft, actions: RecoveredDraftActions) {
    val address = item.draft.address.toString()
    val canApply = actions.canApply(item)
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth().testTag("recoveredDraft")) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp)) {
            Text(
                TableMessages.recoveredDraft(address),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { actions.onCopy(item) }, modifier = Modifier.heightIn(min = 48.dp).testTag("recoveredCopy")) { Text(TableMessages.COPY) }
                TextButton(
                    onClick = { actions.onApply(item) },
                    enabled = canApply,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("recoveredApply"),
                ) { Text(TableMessages.applyTo(address)) }
                TextButton(onClick = { actions.onDiscard(item) }, modifier = Modifier.heightIn(min = 48.dp).testTag("recoveredDiscard")) { Text(TableMessages.DISCARD) }
            }
            if (!canApply) Text(TableMessages.RECOVERED_APPLY_DISABLED, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private const val TICK_MS = 100L
