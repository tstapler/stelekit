// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings

object ToastTags {
    const val LANE = "sketch.toasts"
    const val OVERFLOW_COUNT = "sketch.toasts.more"
    fun row(key: String) = "sketch.toast.$key"
    fun primary(key: String) = "sketch.toast.$key.primary"
    fun more(key: String) = "sketch.toast.$key.more"
    fun moreItem(key: String, label: String) = "sketch.toast.$key.more.${label.lowercase().replace(' ', '-')}"
}

/** What a toast row offers under More, in order: its secondary actions, Details when the notice has any, and Dismiss last (D-86). */
object ToastMenu {
    fun items(notice: SketchNotice): List<String> =
        notice.secondaryActions + listOfNotNull(notice.details?.let { SketchStrings.Chrome.DETAILS }) + SketchStrings.Chrome.DISMISS
}

/** Which toasts the lane shows: the first [laneCount] in priority order, the rest counted as "+N more". */
object ToastLaneModel {
    fun shown(toasts: List<VisibleNotice>, laneCount: Int): List<VisibleNotice> = toasts.take(laneCount)

    fun hidden(toasts: List<VisibleNotice>, laneCount: Int, queued: Int): Int = (toasts.size - laneCount).coerceAtLeast(0) + queued
}

class ToastActions(
    val onPrimary: (SketchNotice) -> Unit = {},
    val onMoreItem: (SketchNotice, String) -> Unit = { _, _ -> },
)

/**
 * What the shell needs to show toasts: the notice queue, a clock in milliseconds and the actions. [refresh] tells the shell the
 * queue changed (a toast was posted or dismissed), since the queue itself is not observable state.
 */
class ToastLaneHost(
    val queue: SketchNoticeQueue,
    val clock: () -> Long,
    val actions: ToastActions = ToastActions(),
) {
    var revision by mutableIntStateOf(0)
        private set

    fun refresh() {
        revision += 1
    }
}

/** The lane in the shell: sized by `ChromeBudget.toastCount` for the canvas height, with a lane of one replacing its toast. */
@Composable
fun ToastLaneOverlay(host: ToastLaneHost, canvasHeightDp: Float, modifier: Modifier = Modifier) {
    val fontScale = LocalDensity.current.fontScale.toDouble()
    val laneCount = ChromeBudget.toastCount(canvasHeightDp.toInt(), fontScale)
    key(host.revision) {
        val now = host.clock()
        host.queue.fitLane(laneCount, now)
        ToastLane(host.queue.visible(now), laneCount, host.queue.hiddenCount(now), host.actions, modifier)
    }
}

/**
 * The toast lane (Story 3.1.4, UX-128, WCAG 2.4.11): up to [laneCount] toasts stacked under the banner slot, each ONE row of
 * text, at most one visible action and a More button, with "+N more" for the rest. A toast never takes focus on arrival.
 * [laneCount] comes from `ChromeBudget.toastCount`; the host calls `SketchNoticeQueue.fitLane` first.
 */
@Composable
fun ToastLane(
    toasts: List<VisibleNotice>,
    laneCount: Int,
    queuedBehind: Int,
    actions: ToastActions,
    modifier: Modifier = Modifier,
) {
    if (toasts.isEmpty()) return
    val shown = ToastLaneModel.shown(toasts, laneCount)
    val hidden = ToastLaneModel.hidden(toasts, laneCount, queuedBehind)
    Column(modifier.testTag(ToastTags.LANE), verticalArrangement = Arrangement.spacedBy(ChromeBudget.TOAST_GAP_DP.dp)) {
        for (item in shown) ToastRow(item.notice, actions)
        if (hidden > 0) {
            Text(
                SketchStrings.Chrome.moreNotices(hidden),
                Modifier.testTag(ToastTags.OVERFLOW_COUNT).padding(horizontal = ChromeBudget.GUTTER_DP.dp),
            )
        }
    }
}

@Composable
private fun ToastRow(notice: SketchNotice, actions: ToastActions) {
    val fontScale = LocalDensity.current.fontScale.toDouble()
    val rowHeight = ChromeText.toastRowDp(fontScale).dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val text = notice.shortTitle?.let { ChromeText.toastText(notice.text, it, maxWidth.value.toInt(), fontScale) } ?: notice.text
        Surface(
            Modifier.fillMaxWidth().heightIn(max = rowHeight).testTag(ToastTags.row(notice.key)),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ) {
            Row(
                Modifier.padding(horizontal = ChromeBudget.GUTTER_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ChromeBudget.TOAST_GAP_DP.dp),
            ) {
                Text(
                    text,
                    Modifier.weight(1f).semantics { contentDescription = notice.text },
                    maxLines = ChromeBudget.BANNER_LINES,
                    style = MaterialTheme.typography.bodyMedium,
                )
                notice.action?.let { label ->
                    TextButton(
                        onClick = { actions.onPrimary(notice) },
                        modifier = Modifier.width(ChromeBudget.TOAST_PRIMARY_DP.dp)
                            .defaultMinSize(minHeight = ChromeBudget.TOOL_TARGET_DP.dp).testTag(ToastTags.primary(notice.key)),
                    ) { Text(label, maxLines = PRIMARY_LINES) }
                }
                ToastMore(notice, actions)
            }
        }
    }
}

@Composable
private fun ToastMore(notice: SketchNotice, actions: ToastActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.size(ChromeBudget.TOOL_TARGET_DP.dp).testTag(ToastTags.more(notice.key))
                .semantics { contentDescription = SketchStrings.Chrome.moreFor(notice.shortTitle ?: notice.text) },
        ) { Text(MORE_GLYPH) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (item in ToastMenu.items(notice)) {
                DropdownMenuItem(
                    text = { Text(item) },
                    onClick = { open = false; actions.onMoreItem(notice, item) },
                    modifier = Modifier.defaultMinSize(minHeight = ChromeBudget.TOOL_TARGET_DP.dp).testTag(ToastTags.moreItem(notice.key, item)),
                )
            }
        }
    }
}

private const val MORE_GLYPH = "⋯"
private const val PRIMARY_LINES = 2
