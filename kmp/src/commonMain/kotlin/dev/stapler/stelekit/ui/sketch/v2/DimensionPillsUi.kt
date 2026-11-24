// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
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
import dev.stapler.stelekit.ui.sketch.lengthTextOf
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchDimensionStrings as S
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object DimensionPillTags {
    fun pill(kind: PillKind) = "sketch.pill.${kind.name.lowercase()}"
    const val EDITOR = "sketch.pill.editor"
    const val FIELD = "sketch.pill.field"
    const val ERROR = "sketch.pill.error"
    const val CANCEL = "sketch.pill.cancel"
    const val LOCKED = "sketch.pill.locked"
    const val READING = "sketch.pill.reading"
    const val ROUNDED = "sketch.pill.rounded"
    const val CHIP_DIMENSIONS = "sketch.chip.dimensions"
    const val CHIP_DIMENSION_SCOPE = "sketch.chip.dimensions.scope"
    const val CHIP_CONSTRAINTS = "sketch.chip.constraints"
    const val CHIP_TECHNICAL = "sketch.chip.technicaldata"
}

private const val PILL_RADIUS_DP = 22
private const val EDITOR_WIDTH_DP = 304.0
private const val EDITOR_HEIGHT_DP = 212.0
private const val EDITOR_RADIUS_DP = 12
private const val EDITOR_PAD_DP = 8

/** How far the soft keyboard covers the bottom of a layer whose bottom edge is at [layerBottomPx] in the window. */
@Composable
private fun imeOverlapDp(layerBottomPx: Float, simulated: Float?): Double {
    simulated?.let { return it.toDouble() }
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    if (imeBottomPx <= 0) return 0.0
    val imeTopPx = LocalWindowInfo.current.containerSize.height - imeBottomPx
    return with(density) { max(0f, layerBottomPx - imeTopPx).toDp().value.toDouble() }
}

/**
 * Dimension pills on a technical sheet (touch and mouse): one tap target per size of the single selected shape, outside the
 * shape and inside the viewport, following pan and zoom. Tapping one opens an inline field that never covers the shape and sits
 * above the soft keyboard (the view pans if the shape is under it), takes "12 3/16\"" and commits one undo step. Draws nothing
 * for a locked sheet, a multi-selection, another tool than Select or a sheet that is not technical.
 */
@Composable
internal fun DimensionPillsOverlay(controller: SketchEditorController, extras: EditorExtras, autoOpen: Boolean = true) {
    val state = extras.pills
    val selection = controller.ui.selection
    val technical = controller.sheetKind == SheetKind.Technical
    val element = selection.singleOrNull()?.let { controller.doc.scene[ElementId(it)] }
    val shown = element != null && DimensionPillModel.shown(technical, controller.readOnly, state.visible, selection.size, controller.ui.activeTool)
    // An edit holds the element it opened on; once the element changed (undo, a drag) or the pills went away it must not commit over it.
    SideEffect {
        if (!shown || state.edit?.element != element) state.close()
        if (state.autoOpenFor != null && (!autoOpen || selection.singleOrNull() != state.autoOpenFor?.value)) state.autoOpenFor = null
    }
    if (!shown || element == null) return
    LaunchedEffect(state.autoOpenFor, element) {
        if (state.autoOpenFor == element.id) {
            state.autoOpenFor = null
            if (!element.base.locked) state.open(element, PillKind.Width)
        }
    }
    val viewport = controller.ui.viewport
    val density = LocalDensity.current.density
    var layerBottomPx by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { layerBottomPx = it.positionInWindow().y + it.size.height }) {
        val editing = state.edit?.takeIf { it.element.id == element.id }
        val visibleH = if (editing != null) PillEditorPlacement.visibleHeight(maxHeight.value.toDouble(), imeOverlapDp(layerBottomPx, state.imeOverlapOverrideDp)) else maxHeight.value.toDouble()
        val canvas = PillCanvas(
            toScreenDp = { x, y -> ((x * viewport.zoom + viewport.panX) / density) to ((y * viewport.zoom + viewport.panY) / density) },
            dpPerUnit = viewport.zoom / density,
            width = maxWidth.value.toDouble(),
            height = visibleH,
        )
        val format = extras.lengthUnit.format
        val pills = remember(element, viewport, density, canvas.width, canvas.height, format) { DimensionPillModel.layout(element, canvas, format) }
        val requesters = remember { PillKind.entries.associateWith { FocusRequester() } }
        pills.forEach { pill -> PillButton(pill, element.base.locked, requesters.getValue(pill.kind)) { state.open(element, pill.kind) } }
        LaunchedEffect(state.refocus) {
            state.refocus?.let { runCatching { requesters.getValue(it).requestFocus() } }
            state.refocus = null
        }
        if (editing != null) {
            val shape = PillEditorPlacement.shapeBounds(element, canvas)
            val editorWidth = min(EDITOR_WIDTH_DP, canvas.width - 2 * DimensionPillModel.EDGE_DP)
            val plan = shape?.let { PillEditorPlacement.plan(it, pills.firstOrNull { p -> p.kind == editing.current }, canvas.width, visibleH, editorWidth, EDITOR_HEIGHT_DP * max(1f, LocalDensity.current.fontScale)) }
            LaunchedEffect(plan?.panDy) {
                if (plan != null && abs(plan.panDy) > 0.5) controller.ui.viewport = controller.ui.viewport.panBy(0.0, plan.panDy * density)
            }
            PillEditorBox(editing, element, plan, controller, state, extras.lengthUnit, editorWidth, visibleH - 2 * DimensionPillModel.EDGE_DP)
        }
    }
}

@Composable
private fun PillButton(pill: PlacedPill, locked: Boolean, focus: FocusRequester, onTap: () -> Unit) {
    val label = DimensionPillModel.label(pill.kind)
    Surface(
        modifier = Modifier.offset(pill.left.dp, pill.top.dp).size(pill.widthDp.dp, pill.heightDp.dp).testTag(DimensionPillTags.pill(pill.kind))
            .focusRequester(focus)
            .semantics(mergeDescendants = true) { contentDescription = S.Pill.description(label, pill.text); role = Role.Button }
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

/**
 * The editor box. There is no scrim: pan and pinch keep working while a field is open, and a tap on the canvas outside it
 * changes the selection, which ends the edit (Esc does too).
 */
@Composable
private fun PillEditorBox(edit: DimensionPillEdit, element: SceneElement, plan: EditorPlan?, controller: SketchEditorController, state: DimensionPillsState, units: LengthUnitState, widthDp: Double, maxHeightDp: Double) {
    val left = plan?.left ?: DimensionPillModel.EDGE_DP
    val top = plan?.top ?: DimensionPillModel.EDGE_DP
    Surface(
        Modifier.offset(left.dp, top.dp).width(widthDp.dp).heightIn(max = maxHeightDp.dp).testTag(DimensionPillTags.EDITOR),
        shape = RoundedCornerShape(EDITOR_RADIUS_DP.dp),
        tonalElevation = 6.dp,
        shadowElevation = 4.dp,
    ) {
        if (element.base.locked) {
            LockedNote(state::close)
        } else {
            PillField(edit, units, onDone = { command -> controller.submit(command) }, onClose = state::finish, onFocus = { state.fieldFocused = it }, onDraft = { kind, text, touched -> state.draft = PillDraft(edit, kind, text, touched) })
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
private fun PillField(edit: DimensionPillEdit, units: LengthUnitState, onDone: (ResizeElement) -> CommandError?, onClose: () -> Unit, onFocus: (Boolean) -> Unit, onDraft: (PillKind, String, Boolean) -> Unit) {
    val kind = edit.current
    val format = units.format
    // The text is owned here and keyed on the field only, so a pan, a zoom or a pointer move never rewrites what was typed.
    val initial = remember(edit, kind) { edit.textOf(kind, format) }
    var field by remember(edit, kind) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    // The text the app itself wrote last (the prefill, or a unit switch's rewrite): while it is on screen the whole field is selected so typing replaces it.
    var generated by remember(edit, kind) { mutableStateOf(initial) }
    // Set by any change of the text (a typed identical value still passes through its first keystroke), so "untouched" means the prefill was never edited.
    var touched by remember(edit, kind) { mutableStateOf(false) }
    val reading = DimensionHint.reading(field.text, format)
    fun cycleUnits() {
        units.cycle()
        // A text that reads as a length is rewritten in the new units; one that does not is left for the user to finish.
        // A text that had to be rounded keeps its wording, so the notice stays honest until Next or Done.
        DimensionHint.reading(field.text, units.format)?.takeIf { it.roundedNotice == null }?.let {
            val rewritten = lengthTextOf(it.typed.inches.sixteenths, units.format)
            val untouched = field.text == generated
            generated = rewritten
            field = TextFieldValue(rewritten, if (untouched) TextRange(0, rewritten.length) else TextRange(rewritten.length))
        }
    }
    var error by remember(edit, kind) { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    var shiftDown by remember { mutableStateOf(false) }
    LaunchedEffect(edit, kind) { focus.requestFocus() }
    SideEffect { onDraft(kind, field.text, touched) }
    val latestFocus by rememberUpdatedState(onFocus)
    DisposableEffect(Unit) { onDispose { latestFocus(false) } }
    fun submit() {
        when (val step = edit.submit(field.text, untouched = !touched)) {
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
    // Scrolls when a large font or a short window leaves less room than the field, hint and keys need.
    Column(Modifier.verticalScroll(rememberScrollState()).padding(EDITOR_PAD_DP.dp)) {
        OutlinedTextField(
            value = field,
            onValueChange = { next ->
                touched = touched || next.text != field.text
                field = next
                error = null
            },
            label = { Text(label) },
            trailingIcon = {
                Box(
                    Modifier.size(48.dp).clickable(role = Role.Button, onClick = onClose).semantics { contentDescription = S.Pill.CANCEL }.testTag(DimensionPillTags.CANCEL),
                    contentAlignment = Alignment.Center,
                ) { Text("\u2715") }
            },
            singleLine = true,
            isError = error != null,
            supportingText = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        reading?.let { Text(it.text, Modifier.testTag(DimensionPillTags.READING), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface) }
                        reading?.roundedNotice?.let { Text(it, Modifier.testTag(DimensionPillTags.ROUNDED), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary) }
                        Text(error ?: hint, Modifier.testTag(if (error != null) DimensionPillTags.ERROR else DimensionPillTags.EDITOR + ".hint").semantics { liveRegion = LiveRegionMode.Polite }, color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LengthUnitChip(format, ::cycleUnits, LengthUnitTags.INLINE_CHIP, keepFieldFocus = true)
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = if (edit.isLast) ImeAction.Done else ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { submit() }, onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(DimensionPillTags.FIELD).onFocusChanged { onFocus(it.isFocused) }.onPreviewKeyEvent { event ->
                val shift = event.key == Key.ShiftLeft || event.key == Key.ShiftRight
                if (shift) shiftDown = event.type == KeyEventType.KeyDown
                when {
                    event.type != KeyEventType.KeyDown -> false
                    event.key == Key.Escape -> { onClose(); true }
                    event.key == Key.Tab && (event.isShiftPressed || shiftDown) -> { edit.back(field.text, untouched = !touched); true }
                    event.key == Key.Tab -> { submit(); true }
                    else -> false
                }
            },
        )
        LengthKeyRow(
            onKey = { key ->
                val typed = LengthKeyInsertion.apply(CaretText(field.text, field.selection.start, field.selection.end), key)
                touched = true
                field = TextFieldValue(typed.text, TextRange(typed.caret))
                error = null
            },
            modifier = Modifier.padding(top = KEY_ROW_GAP_DP.dp),
        )
    }
}

private const val KEY_ROW_GAP_DP = 4
