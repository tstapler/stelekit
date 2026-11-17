// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConstraintStrings

object DimensionEditorTags {
    const val ROOT = "sketch.dimension.editor"
    const val FIELD = "sketch.dimension.field"
    const val SET = "sketch.dimension.set"
    const val CANCEL = "sketch.dimension.cancel"
    const val ERROR = "sketch.dimension.error"
    const val READ_ONLY = "sketch.dimension.readonly"
    const val MAKE_DRIVING = "sketch.dimension.makedriving"
    const val FAILURE = "sketch.dimension.failure"
    const val FAILURE_MESSAGE = "sketch.dimension.failure.message"
    fun failureAction(kind: DimensionFailureKind) = "sketch.dimension.failure.${kind.name.lowercase()}"
}

/** What the editor asks its host to do; the host runs the suspend call of [DimensionEditor] on its scope and applies the outcome. */
class DimensionEditorActions(
    val onSubmit: () -> Unit = {},
    val onChoose: (DimensionFailureKind) -> Unit = {},
    val onMakeDriving: () -> Unit = {},
)

/**
 * The dimension label editor (Task 4.2.7b, UX-99): the value field in place with its number selected, Enter or Set commits,
 * Esc or Cancel commits nothing; an invalid value keeps the field open with "Not a length. Try 12 3/16\""; a reference
 * dimension shows "Reference: driven by other constraints" with Make driving; a value the solver refuses shows the failure
 * card with Keep editing, Revert and Make reference. Nothing is drawn while [DimensionEditor.state] is closed.
 */
@Composable
fun DimensionLabelEditor(editor: DimensionEditor, actions: DimensionEditorActions, modifier: Modifier = Modifier) {
    val escape = Modifier.onPreviewKeyEvent { e -> isEscape(e).also { if (it) editor.cancel() } }
    when (val state = editor.state) {
        DimensionEditState.Closed -> Unit
        is DimensionEditState.Editing -> EditingField(state, editor, actions, modifier.then(escape))
        is DimensionEditState.ReadOnly -> ReadOnlyLabel(state, actions, modifier.then(escape))
        is DimensionEditState.Failed -> FailureCard(state, actions, modifier.then(escape))
    }
}

private fun isEscape(e: KeyEvent): Boolean = e.type == KeyEventType.KeyDown && e.key == Key.Escape

private fun isEnter(e: KeyEvent): Boolean = e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)

@Composable
private fun EditingField(state: DimensionEditState.Editing, editor: DimensionEditor, actions: DimensionEditorActions, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    var field by remember(state.subject.constraint.id) { mutableStateOf(TextFieldValue(state.text, TextRange(0, state.text.length))) }
    LaunchedEffect(state.subject.constraint.id) { focus.requestFocus() }
    Row(
        modifier.testTag(DimensionEditorTags.ROOT).semantics { contentDescription = SketchConstraintStrings.Dimension.EDITOR_REGION },
        horizontalArrangement = Arrangement.spacedBy(FIELD_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = field,
            onValueChange = { field = it; editor.typed(it.text) },
            label = { Text(state.subject.fieldLabel) },
            singleLine = true,
            isError = state.error != null,
            supportingText = {
                state.error?.let { Text(it, Modifier.testTag(DimensionEditorTags.ERROR).semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { actions.onSubmit() }),
            modifier = Modifier.width(FIELD_WIDTH_DP.dp).focusRequester(focus).testTag(DimensionEditorTags.FIELD)
                .onPreviewKeyEvent { e -> isEnter(e).also { if (it) actions.onSubmit() } },
        )
        TextButton(actions.onSubmit, Modifier.defaultMinSize(minHeight = ChromeBudget.TOOL_TARGET_DP.dp).testTag(DimensionEditorTags.SET)) {
            Text(SketchConstraintStrings.Dimension.SET)
        }
        TextButton(editor::cancel, Modifier.defaultMinSize(minHeight = ChromeBudget.TOOL_TARGET_DP.dp).testTag(DimensionEditorTags.CANCEL)) {
            Text(SketchConstraintStrings.Dimension.CANCEL)
        }
    }
}

@Composable
private fun ReadOnlyLabel(state: DimensionEditState.ReadOnly, actions: DimensionEditorActions, modifier: Modifier = Modifier) {
    Column(modifier.testTag(DimensionEditorTags.ROOT)) {
        Text(state.message, Modifier.testTag(DimensionEditorTags.READ_ONLY).semantics { liveRegion = LiveRegionMode.Polite })
        TextButton(actions.onMakeDriving, Modifier.defaultMinSize(minHeight = ChromeBudget.TOOL_TARGET_DP.dp).testTag(DimensionEditorTags.MAKE_DRIVING)) {
            Text(state.actionLabel)
        }
    }
}

@Composable
private fun FailureCard(state: DimensionEditState.Failed, actions: DimensionEditorActions, modifier: Modifier = Modifier) {
    Column(modifier.testTag(DimensionEditorTags.FAILURE)) {
        Text(state.message, Modifier.testTag(DimensionEditorTags.FAILURE_MESSAGE).semantics { liveRegion = LiveRegionMode.Assertive })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(FIELD_GAP_DP.dp)) {
            for (action in state.actions) {
                TextButton(
                    { actions.onChoose(action.kind) },
                    Modifier.defaultMinSize(minHeight = ChromeBudget.TOOL_TARGET_DP.dp).testTag(DimensionEditorTags.failureAction(action.kind)),
                ) { Text(action.label) }
            }
        }
    }
}

private const val FIELD_WIDTH_DP = 168
private const val FIELD_GAP_DP = 8
