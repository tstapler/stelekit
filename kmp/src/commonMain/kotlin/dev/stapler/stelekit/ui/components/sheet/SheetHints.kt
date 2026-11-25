// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import dev.stapler.stelekit.table.GridMode
import dev.stapler.stelekit.ui.components.LocalTablePreferences
import kotlinx.coroutines.delay

/** How the user first drove the sheet; it picks the one-time tip (H2 on touch, H3 with a keyboard). */
internal enum class SheetInput { Touch, Keyboard }

/** Reports the first touch down or key press under this node without consuming either. */
internal fun Modifier.sheetInputProfile(onInput: (SheetInput) -> Unit): Modifier = this
    .onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown) onInput(SheetInput.Keyboard)
        false
    }
    .pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type == PointerType.Touch || down.type == PointerType.Stylus) onInput(SheetInput.Touch)
        }
    }

/** A hint waits this long for a quiet sheet (no notice, no edit), and is dropped for the session after [MAX_DEFERRALS] busy checks. */
internal const val HINT_IDLE_MS = 2_000L
internal const val MAX_DEFERRALS = 3

/**
 * Tips H2 and H3 (ux.md 3.9): one non-modal chip per install, shown once the sheet has been quiet for [HINT_IDLE_MS],
 * never over a notice and never during an edit. Marked seen when shown; a deferred tip that never finds a quiet moment
 * is dropped for this session without being marked, so it can still appear next time.
 */
@Composable
internal fun SheetHintHost(session: TableSheetSession, state: SheetState, input: SheetInput?) {
    val hints = LocalTablePreferences.current.hints
    val hint = when (input) {
        SheetInput.Touch -> TableHint.H2
        SheetInput.Keyboard -> TableHint.H3
        null -> null
    }
    val noticeVisible = session.notices.visible != null
    val editing = state.mode == GridMode.Edit
    var shown by remember { mutableStateOf<TableHint?>(null) }
    var deferrals by remember { mutableIntStateOf(0) }
    LaunchedEffect(hint, noticeVisible, editing) {
        if (hint == null || shown != null || deferrals >= MAX_DEFERRALS) return@LaunchedEffect
        if (hints.isSeen(hint)) return@LaunchedEffect
        if (noticeVisible || editing) {
            deferrals++
            return@LaunchedEffect
        }
        delay(HINT_IDLE_MS)
        hints.markSeen(hint)
        shown = hint
    }
    val current = shown
    if (current != null && !noticeVisible && !editing) TableHintChip(current.text) { shown = null }
}
