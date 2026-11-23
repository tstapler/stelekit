// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.ResizeElement
import dev.stapler.stelekit.sketch.v2.constraints.SheetKind
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchDimensionStrings as S

object DimensionPillTags {
    fun pill(kind: PillKind) = "sketch.pill.${kind.name.lowercase()}"
    const val EDITOR = "sketch.pill.editor"
    const val FIELD = "sketch.pill.field"
    const val ERROR = "sketch.pill.error"
    const val LOCKED = "sketch.pill.locked"
    const val SCRIM = "sketch.pill.scrim"
    const val CHIP_DIMENSIONS = "sketch.chip.dimensions"
    const val CHIP_CONSTRAINTS = "sketch.chip.constraints"
    const val CHIP_TECHNICAL = "sketch.chip.technicaldata"
}

private const val PILL_RADIUS_DP = 22
private const val EDITOR_WIDTH_DP = 232.0
private const val EDITOR_HEIGHT_DP = 112.0
private const val EDITOR_RADIUS_DP = 12
private const val EDITOR_PAD_DP = 8

/**
 * Dimension pills on a technical sheet (touch and mouse): one tap target per size of the single selected shape, outside the
 * shape and inside the viewport, following pan and zoom. Tapping one opens an inline field beside it (above the soft keyboard)
 * that takes "12 3/16\"" and commits one undo step. Draws nothing for a locked sheet, a multi-selection, another tool than
 * Select or a sheet that is not technical.
 */
@Composable
internal fun DimensionPillsOverlay(controller: SketchEditorController, extras: EditorExtras) {
    val state = extras.pills
    val selection = controller.ui.selection
    val technical = controller.sheetKind == SheetKind.Technical
    val element = selection.singleOrNull()?.let { controller.doc.scene[ElementId(it)] }
    val shown = element != null && DimensionPillModel.shown(technical, controller.readOnly, state.visible, selection.size, controller.ui.activeTool)
    // An edit holds the element it opened on; once the element changed (undo, a drag) or the pills went away it must not commit over it.
    SideEffect { if (!shown || state.edit?.element != element) state.close() }
    if (!shown || element == null) return
    val viewport = controller.ui.viewport
    val density = LocalDensity.current.density
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val canvas = PillCanvas(
            toScreenDp = { x, y -> ((x * viewport.zoom + viewport.panX) / density) to ((y * viewport.zoom + viewport.panY) / density) },
            dpPerUnit = viewport.zoom / density,
            width = maxWidth.value.toDouble(),
            height = maxHeight.value.toDouble(),
        )
        val pills = remember(element, viewport, density, canvas.width, canvas.height) { DimensionPillModel.layout(element, canvas) }
        pills.forEach { pill -> PillButton(pill, element.base.locked) { state.open(element, pill.kind) } }
        val edit = state.edit?.takeIf { it.element.id == element.id }
        if (edit != null) PillEditorLayer(edit, element, pills.firstOrNull { it.kind == edit.current }, controller, state)
    }
}

@Composable
private fun PillButton(pill: PlacedPill, locked: Boolean, onTap: () -> Unit) {
    val label = DimensionPillModel.label(pill.kind)
    Surface(
        modifier = Modifier.offset(pill.left.dp, pill.top.dp).size(pill.widthDp.dp, pill.heightDp.dp).testTag(DimensionPillTags.pill(pill.kind))
            .semantics { contentDescription = S.Pill.description(label, pill.text); role = Role.Button }
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(PILL_RADIUS_DP.dp),
        color = if (locked) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        tonalElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(pill.text, style = MaterialTheme.typography.labelLarge, maxLines = 1, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** A scrim that cancels on an outside tap, and the editor box that stays above the soft keyboard. */
@Composable
private fun PillEditorLayer(edit: DimensionPillEdit, element: SceneElement, anchor: PlacedPill?, controller: SketchEditorController, state: DimensionPillsState) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().testTag(DimensionPillTags.SCRIM).pointerInput(Unit) { detectTapGestures { state.close() } })
        PillEditorBox(edit, element, anchor, controller, state)
    }
}

@Composable
private fun PillEditorBox(edit: DimensionPillEdit, element: SceneElement, anchor: PlacedPill?, controller: SketchEditorController, state: DimensionPillsState) {
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val w = EDITOR_WIDTH_DP
        val edge = DimensionPillModel.EDGE_DP
        val centreX = anchor?.centreX ?: (maxWidth.value / 2.0)
        // The field's bottom lines up with the pill's, so it grows up and away from the shape it is sizing.
        val wanted = anchor?.let { it.bottom - EDITOR_HEIGHT_DP } ?: edge
        val top = wanted.coerceIn(edge, maxOf(edge, maxHeight.value - EDITOR_HEIGHT_DP - edge))
        val left = (centreX - w / 2).coerceIn(edge, maxOf(edge, maxWidth.value - w - edge))
        Surface(
            Modifier.offset(left.dp, top.dp).width(w.dp).testTag(DimensionPillTags.EDITOR),
            shape = RoundedCornerShape(EDITOR_RADIUS_DP.dp),
            tonalElevation = 6.dp,
            shadowElevation = 4.dp,
        ) {
            if (element.base.locked) {
                LockedNote(state::close)
            } else {
                PillField(edit, onDone = { command -> controller.submit(command) }, onClose = state::close)
            }
        }
    }
}

@Composable
private fun LockedNote(onClose: () -> Unit) {
    Text(
        S.Pill.LOCKED,
        Modifier.defaultMinSize(minHeight = 44.dp).padding(EDITOR_PAD_DP.dp).testTag(DimensionPillTags.LOCKED).clickable(role = Role.Button, onClick = onClose),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun PillField(edit: DimensionPillEdit, onDone: (ResizeElement) -> CommandError?, onClose: () -> Unit) {
    val kind = edit.current
    // The text is owned here and keyed on the field only, so a pan, a zoom or a pointer move never rewrites what was typed.
    val initial = remember(edit, kind) { edit.textOf(kind) }
    var field by remember(edit, kind) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    var error by remember(edit, kind) { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(edit, kind) { focus.requestFocus() }
    fun submit() {
        when (val step = edit.submit(field.text)) {
            is PillStep.Invalid -> error = step.message
            is PillStep.Next -> Unit
            is PillStep.Done -> {
                val refused = step.command?.let(onDone)
                if (refused == null) onClose() else error = S.Pill.REFUSED
            }
        }
    }
    val label = DimensionPillModel.label(kind)
    val hint = if (edit.isLast) S.Pill.APPLY_HINT else S.Pill.nextHint(DimensionPillModel.label(edit.kinds[edit.kinds.indexOf(kind) + 1]))
    Column(Modifier.padding(EDITOR_PAD_DP.dp)) {
        OutlinedTextField(
            value = field,
            onValueChange = { field = it; error = null },
            label = { Text(label) },
            singleLine = true,
            isError = error != null,
            supportingText = { Text(error ?: hint, Modifier.testTag(if (error != null) DimensionPillTags.ERROR else DimensionPillTags.EDITOR + ".hint").semantics { liveRegion = LiveRegionMode.Polite }, color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = if (edit.isLast) ImeAction.Done else ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { submit() }, onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(DimensionPillTags.FIELD).onPreviewKeyEvent { event ->
                when {
                    event.type != KeyEventType.KeyDown -> false
                    event.key == Key.Escape -> { onClose(); true }
                    event.key == Key.Tab -> { submit(); true }
                    else -> false
                }
            },
        )
    }
}
