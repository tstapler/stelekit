// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import dev.stapler.stelekit.table.GridMode
import dev.stapler.stelekit.table.TableShortcutMap
import dev.stapler.stelekit.ui.components.TableMessages

/** The body slot: the grid fills it (PR-C 5.2+); the host only guarantees [session] outlives the composition of the body. */
internal typealias SheetBody = @Composable (session: TableSheetSession) -> Unit

/** What the overflow's two body-owned actions do; null disables the item until the body provides them. */
internal class SheetOverflowActions(
    val copyAsFormulas: (() -> Unit)? = null,
)

/**
 * The sheet's chrome: top bar (close, title, overflow), then the conflict banner or its Resolve chip, the [body],
 * and the notices pinned at the bottom. It reads [session] only; the body commits through `session.commit`.
 */
@Composable
internal fun TableSheetScreen(
    session: TableSheetSession,
    title: String,
    modifier: Modifier = Modifier,
    overflow: SheetOverflowActions = SheetOverflowActions(),
    recoveredActions: RecoveredDraftActions? = null,
    landscape: Boolean = false,
    body: SheetBody,
) {
    var showShortcuts by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize().testTag("tableSheet")) {
        SheetTopBar(
            session = session,
            title = title,
            onClose = { session.close(SheetCloseReason.User) },
            onEditAsMarkdown = session::editAsMarkdown,
            overflow = overflow,
            onShortcuts = { showShortcuts = true },
        )
        if (session.conflictHeld) {
            if (session.bannerOpen) {
                TableConflictBanner(
                    draftAddress = null,
                    onKeepMine = session::keepMine,
                    onTakeTheirs = session::takeTheirs,
                    onEditAsMarkdown = session::editAsMarkdown,
                )
            } else {
                ResolveChip(onClick = session::resolveChipTapped, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth().testTag("sheetBody")) { body(session) }
        TableNoticeHost(
            notices = session.notices,
            recovered = session.recovered.pinned?.takeIf { it.blockUuid == session.blockUuid },
            recoveredActions = recoveredActions,
            landscape = landscape,
        )
    }
    if (showShortcuts) ShortcutsPanel(onDismiss = { showShortcuts = false })
}

@Composable
private fun SheetTopBar(
    session: TableSheetSession,
    title: String,
    onClose: () -> Unit,
    onEditAsMarkdown: () -> Unit,
    overflow: SheetOverflowActions,
    onShortcuts: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val labels = rememberUndoRedoLabels(session)
    val copyAsFormulas = overflow.copyAsFormulas ?: session.bodyCopyAsFormulas
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Below 360dp the two controls become the first two overflow items, still labelled and still dimmed with the same notice.
        val narrow = maxWidth < NARROW_TOP_BAR
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp).testTag("sheetTopBar"),
        ) {
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp).testTag("sheetClose")) {
                Icon(Icons.Filled.Close, contentDescription = TableMessages.CLOSE)
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("sheetTitle"),
            )
            if (!narrow) {
                UndoRedoButton(labels.undo, labels.undoDimmed, labels.undoNote, session::undoControl, Modifier.testTag("sheetUndo"))
                UndoRedoButton(labels.redo, labels.redoDimmed, labels.redoNote, session::redoControl, Modifier.testTag("sheetRedo"))
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp).testTag("sheetOverflow")) {
                    Icon(Icons.Filled.MoreVert, contentDescription = TableMessages.MORE_OPTIONS)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (narrow) {
                        DropdownMenuItem(
                            text = { Text(labels.undo) },
                            onClick = { menuOpen = false; session.undoControl() },
                            modifier = Modifier.semantics { labels.undoNote?.let { stateDescription = it } }.testTag("menuUndo"),
                        )
                        DropdownMenuItem(
                            text = { Text(labels.redo) },
                            onClick = { menuOpen = false; session.redoControl() },
                            modifier = Modifier.semantics { labels.redoNote?.let { stateDescription = it } }.testTag("menuRedo"),
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(TableMessages.EDIT_AS_MARKDOWN) },
                        onClick = { menuOpen = false; onEditAsMarkdown() },
                        modifier = Modifier.testTag("menuEditAsMarkdown"),
                    )
                    DropdownMenuItem(
                        text = { Text(TableMessages.COPY_AS_FORMULAS) },
                        enabled = copyAsFormulas != null,
                        onClick = { menuOpen = false; copyAsFormulas?.invoke() },
                        modifier = Modifier.testTag("menuCopyAsFormulas"),
                    )
                    DropdownMenuItem(
                        text = { Text(TableMessages.SHORTCUTS) },
                        onClick = { menuOpen = false; onShortcuts() },
                        modifier = Modifier.testTag("menuShortcuts"),
                    )
                    DropdownMenuItem(
                        text = { Text(TableMessages.COLUMN_WIDTHS_RESET, style = MaterialTheme.typography.bodySmall) },
                        enabled = false,
                        onClick = {},
                        modifier = Modifier.semantics { contentDescription = TableMessages.COLUMN_WIDTHS_RESET }.testTag("menuColumnWidthsNote"),
                    )
                }
            }
        }
    }
}

private val NARROW_TOP_BAR = 360.dp

/** Every Navigate chord from the one shortcut map (ADR-009), plus the undo scope rule. */
@Composable
private fun ShortcutsPanel(onDismiss: () -> Unit) {
    val rows = remember { TableShortcutMap.bindings.filter { it.mode == GridMode.Navigate } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(TableMessages.DONE) } },
        title = { Text(TableMessages.SHORTCUTS) },
        text = {
            Column {
                Text(TableMessages.SHEET_UNDO_SCOPE_NOTE, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp).testTag("shortcutRows")) {
                    items(rows) { row ->
                        Text("${row.chord}  ${row.command}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        modifier = Modifier.testTag("shortcutsPanel"),
    )
}

/** Stand-in until the grid lands: shows the table text so the host is exercisable on its own. */
@Composable
internal fun PlaceholderSheetBody(session: TableSheetSession) {
    Text(session.content, modifier = Modifier.padding(16.dp).testTag("sheetPlaceholder"))
}
