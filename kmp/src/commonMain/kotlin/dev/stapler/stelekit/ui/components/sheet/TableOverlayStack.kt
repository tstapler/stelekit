// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.stapler.stelekit.table.Notice
import dev.stapler.stelekit.table.NoticeQueue
import dev.stapler.stelekit.table.NoticeType
import dev.stapler.stelekit.ui.components.TableMessages
import kotlinx.coroutines.delay

/**
 * The inline table's notices: one [NoticeQueue] plus the callbacks behind each action-bearing notice.
 * Observable through [visible] and [moreCount]; not thread-safe (composition thread only).
 */
internal class TableNotices {
    private val queue = NoticeQueue()
    private val actions = HashMap<Long, () -> Unit>()
    private var nextId = 1L

    var visible: Notice? by mutableStateOf(null)
        private set
    var moreCount: Int by mutableIntStateOf(0)
        private set

    fun info(text: String) = publish(Notice(nextId++, text, NoticeType.Info))

    fun withAction(type: NoticeType, text: String, actionLabel: String, onAction: () -> Unit) {
        val notice = Notice(nextId++, text, type, actionLabel)
        actions[notice.id] = onAction
        publish(notice)
    }

    /** Runs the visible notice's action (if any), then shows the next one. */
    fun activate() {
        val current = queue.visible ?: return
        val action = actions.remove(current.id)
        queue.advance()
        sync()
        action?.invoke()
    }

    /** Drops the visible notice without running its action. */
    fun dismiss() {
        val current = queue.visible ?: return
        actions.remove(current.id)
        queue.advance()
        sync()
    }

    /** Hides the visible notice and everything waiting (the strip hid: nothing left to act on). */
    fun clear() {
        while (queue.visible != null) dismiss()
    }

    private fun publish(notice: Notice) {
        queue.offer(notice)
        sync()
    }

    private fun sync() {
        visible = queue.visible
        moreCount = queue.moreCount
    }
}

/**
 * One stack at the bottom of the window (ux.md design rule 13): the optional hint chip, then a notice, then
 * the strip. A notice beats the hint, and landscape suppresses the hint and keeps the notice to one line.
 */
@Composable
internal fun TableOverlayStack(
    notice: Notice?,
    moreCount: Int,
    hint: (@Composable () -> Unit)?,
    compact: Boolean,
    onNoticeAction: () -> Unit,
    onNoticeDismiss: () -> Unit,
    picker: (@Composable () -> Unit)?,
    strip: (@Composable () -> Unit)?,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            if (hint != null && notice == null && !compact) hint()
            if (notice != null) NoticeRow(notice, moreCount, compact, onNoticeAction, onNoticeDismiss)
            if (picker != null) picker()
            if (strip != null) strip()
        }
    }
}

@Composable
private fun NoticeRow(notice: Notice, moreCount: Int, compact: Boolean, onAction: () -> Unit, onDismiss: () -> Unit) {
    val autoDismiss = notice.type == NoticeType.Info
    val latestDismiss by rememberUpdatedState(onDismiss)
    if (autoDismiss) LaunchedEffect(notice.id) {
        delay(NoticeQueue.AUTO_DISMISS_MS)
        latestDismiss()
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp).heightIn(min = 48.dp),
    ) {
        Text(
            text = notice.text,
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (moreCount > 0) Text("$moreCount more", style = MaterialTheme.typography.labelSmall)
        notice.actionLabel?.let { label ->
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp).testTag("noticeAction")) { Text(label) }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("noticeDismiss")) { Text(TableMessages.DISMISS) }
        }
    }
}

/** Where the popup's top-left goes so its bottom edge sits on the IME top (the window bottom when the IME is hidden). */
internal fun overlayTopY(windowHeight: Int, popupHeight: Int, imeBottom: Int): Int =
    (windowHeight - imeBottom - popupHeight).coerceAtLeast(0)

/** Anchors a popup to the window's bottom edge, above [imeBottom] pixels of keyboard, full width. */
internal class WindowBottomPositionProvider(private val imeBottom: () -> Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(0, overlayTopY(windowSize.height, popupContentSize.height, imeBottom()))
}

/** Hosts [content] in a non-focusable window-anchored popup, so the cell's text field keeps the keyboard. */
@Composable
internal fun WindowBottomOverlay(imeBottom: () -> Int, content: @Composable () -> Unit) {
    val latestIme by rememberUpdatedState(imeBottom)
    Popup(
        popupPositionProvider = remember { WindowBottomPositionProvider { latestIme() } },
        properties = PopupProperties(focusable = false, clippingEnabled = false),
        content = content,
    )
}
