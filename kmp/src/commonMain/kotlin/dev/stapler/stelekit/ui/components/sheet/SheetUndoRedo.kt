// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.components.TableMessages

/** What the Undo and Redo controls say and whether they are dimmed; the top bar, the strip and the overflow share it. */
internal data class UndoRedoLabels(val undo: String, val redo: String, val undoDimmed: Boolean, val redoDimmed: Boolean, val undoNote: String?, val redoNote: String?)

@Composable
internal fun rememberUndoRedoLabels(session: TableSheetSession): UndoRedoLabels {
    val history by session.history.collectAsState()
    if (session.fieldScopedUndo) {
        return UndoRedoLabels(TableMessages.UNDO, TableMessages.REDO, false, false, null, null)
    }
    return UndoRedoLabels(
        undo = TableMessages.undoLabel(history.nextUndoLabel),
        redo = TableMessages.redoLabel(history.nextRedoLabel),
        undoDimmed = history.undoDepth == 0,
        redoDimmed = history.redoDepth == 0,
        undoNote = TableMessages.NOTHING_TO_UNDO_TABLE.takeIf { history.undoDepth == 0 },
        redoNote = TableMessages.NOTHING_TO_REDO_TABLE.takeIf { history.redoDepth == 0 },
    )
}

/** A labelled 48dp control; when [dimmed] it stays focusable and clickable, and activating it explains why nothing happens. */
@Composable
internal fun UndoRedoButton(text: String, dimmed: Boolean, note: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        colors = if (dimmed) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) else ButtonDefaults.textButtonColors(),
        modifier = modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).semantics { if (dimmed && note != null) stateDescription = note },
    ) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
    }
}
