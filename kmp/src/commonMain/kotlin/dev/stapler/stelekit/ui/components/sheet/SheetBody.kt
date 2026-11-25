// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.table.NoticeType
import dev.stapler.stelekit.ui.components.TableMessages
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The sheet's body: the edit bar over the lazy grid. Large tables parse and run their first calc off the main
 * thread behind an "Opening table..." indicator (UX-66); small ones parse inline so nothing flashes. [parse] is a
 * seam for tests that need to hold the parse. [sink] receives the commands this body does not own.
 */
@Composable
internal fun SheetBodyContent(
    session: TableSheetSession,
    modifier: Modifier = Modifier,
    sink: SheetCommandSink = NoOpSheetCommandSink,
    parse: (String) -> ParsedSheet = ::parseSheet,
    onState: (SheetState) -> Unit = {},
) {
    val initial = remember(session) { session.content }
    val inline = remember(session) { if (SheetLoadingState.parsesOffThread(initial)) null else parse(initial) }
    val latestParse by rememberUpdatedState(parse)
    val offThread by produceState<ParsedSheet?>(null, session) {
        if (inline == null) value = withContext(PlatformDispatcher.Default) { latestParse(initial) }
    }
    val parsed = inline ?: offThread
    if (parsed == null) {
        OpeningTable(modifier)
        return
    }
    val failure = parsed.failure
    if (failure != null) {
        Column(modifier.padding(16.dp)) {
            Text(TableMessages.cannotEditAsGrid(failure))
            TextButton(onClick = session::editAsMarkdown, modifier = Modifier.heightIn(min = 48.dp)) { Text(TableMessages.EDIT_AS_MARKDOWN) }
        }
        return
    }
    val state = rememberSaveable(parsed, session, saver = SheetState.saver(parsed, session, sink)) { SheetState(parsed, session, sink) }
    SideEffect {
        state.sink = sink
        onState(state)
    }
    DisposableEffect(state, session) {
        session.bind(state)
        state.rebuild(session.content, null)
        state.onDiscarded = { d ->
            session.notices.withAction(NoticeType.Undo, TableMessages.DISCARDED_EDIT, TableMessages.UNDO) {
                state.restoreDraft(d.address, d.text)
            }
        }
        onDispose { }
    }
    SheetBackHandler(state)
    SheetBodyLayout(state, session, modifier)
}

@Composable
private fun OpeningTable(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().testTag("openingTable"), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(32.dp))
        Text(TableMessages.OPENING_TABLE, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun SheetBodyLayout(state: SheetState, session: TableSheetSession, modifier: Modifier = Modifier) {
    val gridFocus = remember { FocusRequester() }
    LaunchedEffect(state.gridFocusToken) { runCatching { gridFocus.requestFocus() } }
    val saving by rememberDelayedFlag(session.pending.collectAsState().value > 0, SheetLoadingState.SAVING_INDICATOR_MS)
    val applying by rememberDelayedFlag(state.applying, SheetLoadingState.APPLYING_INDICATOR_MS)
    Column(modifier.fillMaxSize().testTag("sheetBodyContent")) {
        FormulaBar(state)
        Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
            Box(Modifier.fillMaxSize().focusRequester(gridFocus).focusable().sheetGridKeys(state).testTag("sheetGridFocus")) {
                SheetGrid(state)
            }
        }
        SheetBusyLine(SheetLoadingState.statusText(saving, applying))
        Box(
            Modifier.size(0.dp).semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = state.announcement ?: ""
            }.testTag("announcement"),
        )
    }
}

/** The status line's busy text ("Saving...", "Applying..."); the selection stats of Story 5.4 share this row. */
@Composable
private fun SheetBusyLine(text: String?) {
    if (text == null) return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("busyLine"),
    )
}

/** True once [active] has held for [afterMs] without a break; flips back at once. Driven by `delay`, so a test clock moves it. */
@Composable
internal fun rememberDelayedFlag(active: Boolean, afterMs: Long): State<Boolean> {
    val flag = remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            delay(afterMs)
            flag.value = true
        } else {
            flag.value = false
        }
    }
    return flag
}
