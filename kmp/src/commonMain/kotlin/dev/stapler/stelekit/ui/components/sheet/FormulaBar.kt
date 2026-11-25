// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.table.GridCommand
import dev.stapler.stelekit.table.GridMode
import dev.stapler.stelekit.table.formula.FormulaParse
import dev.stapler.stelekit.table.formula.FormulaParser
import dev.stapler.stelekit.ui.components.TableMessages

private val COMPACT_WIDTH = 600.dp

/** Shows a line break as the visible text `<br>` (ux.md S7) while the draft keeps its real newline. */
internal object BreakVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val shown = raw.replace("\n", "<br>")
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = offset + 3 * raw.take(offset).count { it == '\n' }

            override fun transformedToOriginal(offset: Int): Int {
                var shownAt = 0
                for (i in raw.indices) {
                    val width = if (raw[i] == '\n') BR_WIDTH else 1
                    if (offset < shownAt + width) return if (offset - shownAt >= 2 && width > 1) i + 1 else i
                    shownAt += width
                }
                return raw.length
            }
        }
        return TransformedText(AnnotatedString(shown, text.spanStyles, text.paragraphStyles), mapping)
    }

    private const val BR_WIDTH = 4
}

/**
 * The edit bar (ux.md S7): literal text of the active cell, the one live field of an edit, Cancel edit and Done,
 * the point-mode indicator with Stop inserting and Extend..., and the error or syntax message line. At phone width
 * a (...) menu carries Insert line break, Next column and Stop inserting. It sits above the grid, so the IME never
 * covers it (conservative pinned-editor default; UNVERIFIED on device until spike 0.4a).
 */
@Composable
internal fun FormulaBar(state: SheetState, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val compact = maxWidth < COMPACT_WIDTH
        Column(Modifier.fillMaxWidth().testTag("formulaBar")) {
            BarFieldRow(state, compact)
            BarMessages(state)
        }
    }
}

@Composable
private fun BarFieldRow(state: SheetState, compact: Boolean) {
    val editing = state.mode == GridMode.Edit
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.barFocusToken) {
        if (state.mode == GridMode.Edit) runCatching { focus.requestFocus() }
    }
    val text = if (editing) state.fieldValue.text else state.barText
    val style = MaterialTheme.typography.bodyLarge.copy(
        color = MaterialTheme.colorScheme.onSurface,
        fontFamily = if (text.startsWith("=")) FontFamily.Monospace else FontFamily.Default,
    )
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(TableMessages.FORMULA_MARKER, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
        BasicTextField(
            value = if (editing) state.fieldValue else androidx.compose.ui.text.input.TextFieldValue(text),
            onValueChange = { if (editing) state.onFieldChange(it) },
            readOnly = !editing,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            visualTransformation = BreakVisualTransformation,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { state.onImeNext() }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .semantics { contentDescription = TableMessages.EDIT_BAR_LABEL }
                .testTag("barField")
                .onPreviewKeyEvent { event ->
                    if (!editing || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (val decision = decideSheetKey(event, GridMode.Edit, state.composing)) {
                        // Esc goes up the ladder (banner, menu, then Cancel) through the host's bubbling handler.
                        is SheetKeyDecision.Run -> decision.command != GridCommand.Cancel && state.onKey(decision)
                        else -> false
                    }
                },
        )
        if (editing) {
            IconButton(onClick = state::cancelEdit, modifier = Modifier.size(48.dp).testTag("barCancel")) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = TableMessages.CANCEL_EDIT)
            }
            IconButton(onClick = state::done, modifier = Modifier.size(48.dp).testTag("barDone")) {
                Icon(Icons.Filled.Check, contentDescription = TableMessages.DONE)
            }
        }
        if (compact && editing) BarOverflow(state)
    }
}

@Composable
private fun BarOverflow(state: SheetState) {
    var open by remember { mutableStateOf(false) }
    DisposableEffect(open) {
        val unregister = if (open) state.registerMenu { open = false } else ({})
        onDispose(unregister)
    }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp).testTag("barOverflow")) {
            Icon(Icons.Filled.MoreVert, contentDescription = TableMessages.MORE_OPTIONS)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(TableMessages.INSERT_LINE_BREAK) },
                onClick = { open = false; state.perform(GridCommand.InsertLineBreak) },
                modifier = Modifier.testTag("menuInsertLineBreak"),
            )
            DropdownMenuItem(
                text = { Text(TableMessages.NEXT_COLUMN) },
                onClick = { open = false; state.nextColumn() },
                modifier = Modifier.testTag("menuNextColumn"),
            )
            if (state.pointIndicator != null) {
                DropdownMenuItem(
                    text = { Text(TableMessages.STOP_INSERTING) },
                    onClick = { open = false; state.stopInserting() },
                    modifier = Modifier.testTag("menuStopInserting"),
                )
            }
            DropdownMenuItem(
                text = { Text(TableMessages.UNDO) },
                onClick = { open = false; state.draftUndo() },
                modifier = Modifier.testTag("menuFieldUndo"),
            )
            DropdownMenuItem(
                text = { Text(TableMessages.REDO) },
                onClick = { open = false; state.draftRedo() },
                modifier = Modifier.testTag("menuFieldRedo"),
            )
        }
    }
}

@Composable
private fun BarMessages(state: SheetState) = Column {
    val pad = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    if (state.readOnly) Text(TableMessages.READ_ONLY_STATE, style = MaterialTheme.typography.bodySmall, modifier = pad.testTag("barReadOnly"))
    state.errorDetail?.let { detail ->
        Text(detail, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = pad.testTag("barMessage"))
    }
    if (state.mode == GridMode.Edit) {
        val draftText = state.fieldValue.text
        if (FormulaParser.isFormulaShaped(draftText) && FormulaParser.parse(draftText) is FormulaParse.Failure) {
            Text(
                TableMessages.errorDetail(dev.stapler.stelekit.table.formula.CellError.Parse),
                style = MaterialTheme.typography.bodySmall,
                modifier = pad.testTag("barSyntax"),
            )
        }
        val indicator = state.pointIndicator
        if (indicator != null) PointModeRow(state, indicator)
    }
}

@Composable
private fun PointModeRow(state: SheetState, indicator: String) {
    var picker by remember { mutableStateOf(false) }
    DisposableEffect(picker) {
        val unregister = if (picker) state.registerMenu { picker = false } else ({})
        onDispose(unregister)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(indicator, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).testTag("pointIndicator"))
        if (state.pointMode.justInserted) {
            TextButton(onClick = { picker = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("extendButton")) { Text(TableMessages.EXTEND) }
            DropdownMenu(expanded = picker, onDismissRequest = { picker = false }) {
                ExtendChoice.entries.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(choice.label) },
                        onClick = { picker = false; state.extendReference(choice) },
                        modifier = Modifier.testTag("extend-${choice.name}"),
                    )
                }
            }
        }
        TextButton(onClick = state::stopInserting, modifier = Modifier.heightIn(min = 48.dp).testTag("stopInserting")) {
            Text(TableMessages.STOP_INSERTING)
        }
    }
}
