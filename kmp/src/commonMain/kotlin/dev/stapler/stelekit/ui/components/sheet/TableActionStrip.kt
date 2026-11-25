// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.EntryProfile
import dev.stapler.stelekit.table.GridMode
import dev.stapler.stelekit.table.TableDoc
import dev.stapler.stelekit.ui.components.TableMessages

/** Every control the inline strip can show; the order of declaration is the order on screen (ux.md design rule 10). */
internal enum class StripAction(val label: String) {
    Close(TableMessages.CLOSE),
    CancelEdit(TableMessages.CANCEL_EDIT),
    Done(TableMessages.DONE),
    NextColumn(TableMessages.NEXT_COLUMN),
    LineBreak(TableMessages.LINE_BREAK),
    SelectRow(TableMessages.SELECT_ROW),
    SelectColumn(TableMessages.SELECT_COLUMN),
    SelectTable(TableMessages.SELECT_TABLE),
    Extend(TableMessages.EXTEND),
    Copy(TableMessages.COPY),
    Undo(TableMessages.UNDO),
    Redo(TableMessages.REDO),
    Why(TableMessages.WHY),
    Maximize(TableMessages.MAXIMIZE),
    Resolve(TableMessages.RESOLVE),
    More(TableMessages.MORE),
    Markdown(TableMessages.MARKDOWN),
}

/** The picker rows that open from the strip; at most one is open (the one Extend mode, UX-75). */
internal enum class StripMenu { Extend, More }

/** The five ways Extend... grows a range from its anchor. */
internal enum class ExtendChoice(val label: String) {
    Right("Right"),
    Down("Down"),
    RowEnd("To row end"),
    ColumnEnd("To column end"),
    TableEnd("To table end"),
}

/** Callbacks the composable refreshes each composition; everything platform-shaped stays out of the state. */
internal class StripHooks {
    var copyToClipboard: (String) -> Unit = {}

    /** Null unless the sheet flag is on: it hides every Maximize entry and unbinds Ctrl+Shift+Enter. */
    var openSheet: (() -> Unit)? = null
    var editAsMarkdown: () -> Unit = {}
    var cellText: ((TableDoc, CellAddress) -> String)? = null
    var errorDetail: ((TableDoc, CellAddress) -> String?)? = null

    /** The first strip action dismisses the one-time tip. */
    var onStripAction: () -> Unit = {}
}

internal data class StripLayout(
    val leading: List<StripAction>,
    val scrolling: List<StripAction>,
    val overflow: List<StripAction>,
    val trailing: StripAction = StripAction.Markdown,
)

/**
 * The strip's controls in their one fixed order. [compact] (landscape) keeps the first slot, Done and Markdown and
 * moves the rest behind More. [hardwareReduced] is a held conflict in a hardware edit: Resolve, Cancel edit, Markdown.
 */
@Suppress("LongParameterList")
internal fun stripLayout(
    mode: GridMode,
    profile: EntryProfile,
    sheetEnabled: Boolean,
    hasWhy: Boolean,
    conflictHeld: Boolean,
    hardwareReduced: Boolean,
    compact: Boolean,
): StripLayout {
    if (hardwareReduced) return StripLayout(listOf(StripAction.CancelEdit), listOf(StripAction.Resolve), emptyList())
    val editing = mode == GridMode.Edit
    val leading = if (editing) listOf(StripAction.CancelEdit, StripAction.Done) else listOf(StripAction.Close)
    val body = buildList {
        if (editing && profile == EntryProfile.Touch) {
            add(StripAction.NextColumn)
            add(StripAction.LineBreak)
        }
        add(StripAction.SelectRow)
        add(StripAction.SelectColumn)
        add(StripAction.SelectTable)
        add(StripAction.Extend)
        add(StripAction.Copy)
        add(StripAction.Undo)
        add(StripAction.Redo)
        if (hasWhy) add(StripAction.Why)
        if (sheetEnabled) add(StripAction.Maximize)
        if (editing && conflictHeld) add(StripAction.Resolve)
    }
    return if (compact) StripLayout(leading, listOf(StripAction.More), body) else StripLayout(leading, body, emptyList())
}

private val FIRST_SLOT_WIDTH = 112.dp
private val MIN_TARGET = 48.dp

/** The window-anchored bar: first slot, Done, a scrolling middle, and Markdown pinned at the end. */
@Composable
internal fun TableActionStrip(state: InlineTableState, sheetEnabled: Boolean, hasWhy: Boolean, compact: Boolean) {
    val policy = state.stripPolicy
    val layout = stripLayout(state.mode, state.entryProfile, sheetEnabled, hasWhy, state.conflictHeld, policy.reduced, compact)
    val extendFocus = remember { FocusRequester() }
    val moreFocus = remember { FocusRequester() }
    val returnTo = state.menuFocusReturn
    LaunchedEffect(returnTo) {
        val target = when (returnTo) {
            StripMenu.Extend -> extendFocus
            StripMenu.More -> moreFocus
            null -> return@LaunchedEffect
        }
        try {
            target.requestFocus()
        } catch (_: IllegalStateException) {
            // The invoker left composition; nothing to focus.
        }
        state.menuFocusReturned()
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().testTag("tableStrip").semantics { isTraversalGroup = true },
    ) {
        layout.leading.forEachIndexed { index, action ->
            StripButton(action, state, if (index == 0) Modifier.width(FIRST_SLOT_WIDTH) else Modifier)
        }
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            layout.scrolling.forEach { action ->
                val invoker = when (action) {
                    StripAction.Extend -> Modifier.focusRequester(extendFocus)
                    StripAction.More -> Modifier.focusRequester(moreFocus)
                    else -> Modifier
                }
                StripButton(action, state, invoker)
            }
        }
        StripButton(layout.trailing, state, Modifier)
    }
}

/** One of the picker rows above the strip: Extend's five choices, or the controls landscape moved behind More. */
@Composable
internal fun TableStripPicker(state: InlineTableState, sheetEnabled: Boolean, hasWhy: Boolean) {
    val menu = state.openMenu ?: return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("tablePicker:${menu.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (menu) {
            StripMenu.Extend -> ExtendChoice.entries.forEach { choice ->
                StripText(choice.label, onClick = { state.performExtend(choice) }, modifier = Modifier.testTag("extend:${choice.name}"))
            }
            StripMenu.More -> {
                val rest = stripLayout(state.mode, state.entryProfile, sheetEnabled, hasWhy, state.conflictHeld, false, compact = true).overflow
                rest.forEach { StripButton(it, state, Modifier) }
            }
        }
    }
}

@Composable
private fun StripButton(action: StripAction, state: InlineTableState, modifier: Modifier = Modifier) {
    val (text, dimmed, description) = when (action) {
        StripAction.Undo -> Triple(state.undoLabel, !state.undoEnabled, notingNothing(state, undo = true))
        StripAction.Redo -> Triple(state.redoLabel, !state.redoEnabled, notingNothing(state, undo = false))
        StripAction.Resolve -> Triple(action.label, true, "Conflict resolution arrives with the sheet release")
        else -> Triple(action.label, false, null)
    }
    StripText(
        text = text,
        onClick = { state.perform(action) },
        modifier = modifier.testTag("strip:${action.name}").semantics { if (dimmed && description != null) stateDescription = description },
        dimmed = dimmed,
    )
}

private fun notingNothing(state: InlineTableState, undo: Boolean): String {
    val inCell = state.mode == GridMode.Edit
    return when {
        undo && inCell -> TableMessages.NOTHING_TO_UNDO_CELL
        undo -> TableMessages.NOTHING_TO_UNDO_TABLE
        inCell -> TableMessages.NOTHING_TO_REDO_CELL
        else -> TableMessages.NOTHING_TO_REDO_TABLE
    }
}

/** A dimmed control stays focusable and clickable: activating it explains why nothing happens. */
@Composable
private fun StripText(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, dimmed: Boolean = false) {
    TextButton(
        onClick = onClick,
        colors = if (dimmed) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) else ButtonDefaults.textButtonColors(),
        modifier = modifier.heightIn(min = MIN_TARGET).widthIn(min = MIN_TARGET),
    ) {
        Text(text, maxLines = 1, modifier = Modifier.padding(horizontal = 0.dp))
    }
}
