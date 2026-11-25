// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.focusable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.table.LadderKey
import dev.stapler.stelekit.ui.LocalWindowSizeClass
import dev.stapler.stelekit.ui.WindowSizeClass
import dev.stapler.stelekit.ui.components.TableMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.CoroutineContext

/** Full width below 600dp and between 600dp and 839dp (with the inline edit bar), about 90% from 840dp (UX-12). */
internal fun sheetWidthFraction(sizeClass: WindowSizeClass): Float =
    if (sizeClass == WindowSizeClass.EXPANDED) EXPANDED_FRACTION else 1f

private const val EXPANDED_FRACTION = 0.9f

/**
 * The maximized table: a `Dialog` (ADR-006) that owns one [TableSheetSession] while composed. Observes only the
 * block's feed (version dropped here: only the content reaches the sync policy), commits through the session's
 * single committer, and closes itself on every lifecycle trigger of Story 5.1 via [onClose].
 * Dialog windows do not forward key events to the page's global handler (Unresolved Question 3, see
 * `TableSheetHostTest`), so Ctrl+Z and Ctrl+Y are bound here and routed through the depth-gated committer.
 */
@Suppress("LongParameterList") // the host's collaborators are all distinct ports; wrapping them hides nothing
@Composable
internal fun TableSheetHost(
    blockUuid: BlockUuid,
    initialContent: String,
    title: String?,
    feed: Flow<Block?>,
    port: TableManagerPort,
    context: CoroutineContext,
    flushScope: CoroutineScope,
    flush: suspend () -> Unit,
    recovered: RecoveredDraftStore,
    sheetEnabled: Boolean,
    onClose: (SheetCloseReason) -> Unit,
    modifier: Modifier = Modifier,
    graphKey: String? = null,
    startVersion: Long = 0L,
    readOnly: Boolean = false,
    isChangingConfiguration: () -> Boolean = { false },
    recoveredActions: RecoveredDraftActions? = null,
    overflow: SheetOverflowActions = SheetOverflowActions(),
    onSessionReady: (TableSheetSession) -> Unit = {},
    onChangeLoss: (String) -> Unit = {},
    body: SheetBody = { PlaceholderSheetBody(it) },
) {
    val latestOnClose = rememberUpdatedState(onClose)
    val latestChangeLoss = rememberUpdatedState(onChangeLoss)
    val openedForGraph = remember { graphKey }
    val session = remember(blockUuid) {
        TableSheetSession(
            blockUuid = blockUuid,
            initialContent = initialContent,
            port = port,
            context = context,
            readOnly = readOnly,
            recovered = recovered,
            startVersion = startVersion,
            flushScope = flushScope,
            flush = flush,
            onCloseRequest = { latestOnClose.value(it) },
            onChangeLoss = { latestChangeLoss.value(it) },
        )
    }
    val latestReady = rememberUpdatedState(onSessionReady)
    val latestChanging = rememberUpdatedState(isChangingConfiguration)
    LaunchedEffect(session) { latestReady.value(session) }
    LaunchedEffect(session) { feed.collect { session.onBlock(it) } }
    LaunchedEffect(session) { port.undoRecordCount.collect { session.onUndoRecordCount(it) } }
    LaunchedEffect(session) { port.editingBlockUuid.collect { session.onEditingBlock(it) } }
    LaunchedEffect(session, graphKey) { if (graphKey != openedForGraph) session.close(SheetCloseReason.GraphChanged) }
    LaunchedEffect(session, sheetEnabled) { if (!sheetEnabled) session.close(SheetCloseReason.FlagOff) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(session, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) session.onStop(latestChanging.value())
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            session.close(SheetCloseReason.User)
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(session) { runCatching { focus.requestFocus() } }
    val fraction = sheetWidthFraction(LocalWindowSizeClass.current)
    val escGate = remember { EscDismissGate() }
    Dialog(
        onDismissRequest = { if (!escGate.swallowDismiss()) session.onBack(LadderKey.Back) },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight(if (fraction < 1f) fraction else 1f)
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { escGate.observe(it.key == Key.Escape, it.type == KeyEventType.KeyDown); false }
                .onKeyEvent { sheetKey(session, it) }
                .testTag("tableSheetHost"),
        ) {
            Box {
                TableSheetScreen(
                    session = session,
                    title = title ?: TableMessages.SHEET_TITLE_FALLBACK,
                    overflow = overflow,
                    recoveredActions = recoveredActions,
                    body = body,
                )
            }
        }
    }
}

/**
 * Esc never closes the sheet (UX-71, UX-93), but a platform `Dialog` may report an unconsumed Esc as a dismiss request,
 * the same call Android Back makes. The gate remembers an Esc key-down so that one dismiss request is ignored.
 * UNVERIFIED on a real desktop or browser window: whether the platform dialog reports Esc this way is pending the owner.
 */
internal class EscDismissGate {
    private var escapeDown = false

    fun observe(isEscape: Boolean, isDown: Boolean) {
        if (isEscape) escapeDown = isDown
    }

    /** True once per Esc press: the dismiss request that follows it is not a Back. */
    fun swallowDismiss(): Boolean = escapeDown.also { escapeDown = false }
}

/**
 * Bubbling key handler for what the focused body did not take: Ctrl+Z undo, Ctrl+Y and Ctrl+Shift+Z redo, Esc up
 * the ladder. Bubbling (not preview) so a cell in `Edit` keeps its field-scoped Ctrl+Z (UX-86).
 */
internal fun sheetKey(session: TableSheetSession, event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val mod = event.isCtrlPressed || event.isMetaPressed
    return when {
        mod && event.key == Key.Z && !event.isShiftPressed -> true.also { session.undo() }
        mod && event.key == Key.Z && event.isShiftPressed -> true.also { session.redo() }
        mod && event.key == Key.Y -> true.also { session.redo() }
        event.key == Key.Escape -> session.onBack(LadderKey.Esc) != dev.stapler.stelekit.table.LadderAction.NotHandled
        else -> false
    }
}
