// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchTextSizeStrings as S

object TextSizeTags {
    const val SMALLER = "sketch.textsize.smaller"
    const val LARGER = "sketch.textsize.larger"
    const val ROW_SMALLER = "sketch.textsize.row.smaller"
    const val ROW_LARGER = "sketch.textsize.row.larger"
    const val FIELD = "sketch.textsize.field"
    const val ERROR = "sketch.textsize.error"
    const val BADGE = "sketch.textsize.badge"
}

private const val SIZE_CHIP_WIDTH_DP = 56
private val TARGET = 44.dp
private val FIELD_MAX_WIDTH = 96.dp

/** A-/A+ in the thumb-reach chip row while stand-alone text is selected; each step is one undo step and names the new size. */
internal fun textSizeChips(controller: SketchEditorController): List<ActionChip> {
    if (controller.readOnly) return emptyList()
    val selection = controller.ui.selection.mapNotNull { controller.doc.scene[ElementId(it)] }
    val current = TextSizeModel.current(selection) ?: return emptyList()
    return listOf(
        ActionChip(TextSizeTags.SMALLER, S.SMALLER, S.SMALLER_NAME, minWidthDp = SIZE_CHIP_WIDTH_DP) { applyTextSize(controller, TextSizeModel.smaller(current)) },
        ActionChip(TextSizeTags.LARGER, S.LARGER, S.LARGER_NAME, minWidthDp = SIZE_CHIP_WIDTH_DP) { applyTextSize(controller, TextSizeModel.larger(current)) },
    )
}

/** Applies [change] to the selected text and says what happened: the new size, or the limit it hit, or why it was refused. */
internal fun applyTextSize(controller: SketchEditorController, change: TextSizeChange) {
    when (change) {
        is TextSizeChange.Reject -> controller.notifier.toast(S.TOAST_KEY, change.message)
        is TextSizeChange.Apply -> {
            val selection = controller.ui.selection.mapNotNull { controller.doc.scene[ElementId(it)] }
            val command = TextSizeModel.commandFor(selection, change, controller.textMetrics)
            if (command != null) controller.submit(command)
            (change.notice ?: S.size(TextSizeModel.format(change.size)).takeIf { command != null })?.let { controller.notifier.toast(S.TOAST_KEY, it) }
        }
    }
}

/** The size a text is being dragged to, shown over the canvas while a corner handle scales it; null when no text is being scaled. */
internal fun scaledTextSize(controller: SketchEditorController): Double? {
    val shown = controller.tools.preview?.elements?.singleOrNull() as? TextElement ?: return null
    val before = controller.doc.scene[shown.id] as? TextElement ?: return null
    return shown.fontSize.takeIf { it != before.fontSize }
}

@Composable
internal fun BoxScope.TextSizeBadge(controller: SketchEditorController) {
    val size = scaledTextSize(controller) ?: return
    val text = S.size(TextSizeModel.format(size))
    Surface(
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).testTag(TextSizeTags.BADGE).semantics { contentDescription = text },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
    ) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The Style tab's size control: A- and A+ buttons around an editable size. The field applies on Done, not per key, so typing
 * "24" never passes through a clamped "2". [current] is the first selected text's size.
 */
@Composable
internal fun TextSizeRow(current: Double, onChange: (TextSizeChange) -> Unit) {
    var text by remember(current) { mutableStateOf(TextSizeModel.format(current)) }
    var error by remember(current) { mutableStateOf<String?>(null) }
    val commit = {
        when (val change = TextSizeModel.typed(text, current)) {
            is TextSizeChange.Reject -> error = change.message
            is TextSizeChange.Apply -> { error = null; onChange(change) }
        }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(S.LABEL, Modifier.widthIn(min = 72.dp))
            TextButton(
                onClick = { onChange(TextSizeModel.smaller(current)) },
                modifier = Modifier.heightIn(min = TARGET).widthIn(min = TARGET).testTag(TextSizeTags.ROW_SMALLER).semantics { contentDescription = S.SMALLER_NAME },
            ) { Text(S.SMALLER) }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; error = null },
                singleLine = true,
                isError = error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier.widthIn(max = FIELD_MAX_WIDTH).testTag(TextSizeTags.FIELD).semantics { contentDescription = S.LABEL },
            )
            TextButton(
                onClick = { onChange(TextSizeModel.larger(current)) },
                modifier = Modifier.heightIn(min = TARGET).widthIn(min = TARGET).testTag(TextSizeTags.ROW_LARGER).semantics { contentDescription = S.LARGER_NAME },
            ) { Text(S.LARGER) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag(TextSizeTags.ERROR)) }
    }
}
