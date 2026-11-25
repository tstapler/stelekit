// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.CollectionInfo
import dev.stapler.stelekit.sketch.v2.constraints.SheetKind
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.ui.components.typedItemsIndexed
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchOutlineStrings as S

object OutlineTags {
    const val DIALOG = "sketch.outline"
    const val LIST = "sketch.outline.list"
    const val SUMMARY = "sketch.outline.summary"
    const val CLOSE = "sketch.outline.close"
    const val RENAME_FIELD = "sketch.outline.rename.field"
    const val RENAME_SAVE = "sketch.outline.rename.save"
    const val RENAME_CANCEL = "sketch.outline.rename.cancel"
    fun row(position: Int) = "sketch.outline.row.$position"
}

object ShapeAtTags {
    const val DIALOG = "sketch.shapeat"
    const val CONFIRM = "sketch.shapeat.confirm"
    const val CANCEL = "sketch.shapeat.cancel"
    fun kind(kind: ShapeAtKind) = "sketch.shapeat.kind.${kind.name}"
    fun field(field: ShapeAtField) = "sketch.shapeat.field.${field.name}"
}

/** The 48 dp touch-target floor of every control in these dialogs (Material and WCAG 2.5.8 guidance). */
private const val TARGET_DP = 48
private val LIST_MAX_HEIGHT = 360.dp

/**
 * The Sketch outline (E12): every element in reading order as a plain list a screen reader or a switch can walk without the
 * canvas. A row selects the element and reveals it; its custom actions Select and Rename are the same two things for TalkBack's
 * action menu. [onRename] returns the refusal text or null when the name was saved.
 */
@Composable
fun SketchOutlineDialog(
    entries: List<OutlineEntry>,
    onSelect: (OutlineEntry) -> Unit,
    onRename: (ElementId, String) -> String?,
    onDismiss: () -> Unit,
    /** Whether the rename field takes focus when it opens; a Compose test cannot wait out a focused field's cursor and passes false. */
    autoFocus: Boolean = true,
) {
    var renaming by remember { mutableStateOf<OutlineEntry?>(null) }
    var renameText by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(OutlineTags.DIALOG),
        title = { Text(S.OUTLINE_TITLE, Modifier.semantics { heading() }) },
        text = {
            Column {
                val summary = if (entries.isEmpty()) S.OUTLINE_EMPTY else S.summary(entries.size)
                Text(
                    summary,
                    Modifier.testTag(OutlineTags.SUMMARY).padding(bottom = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                )
                renaming?.let { target ->
                    RenameBar(
                        text = renameText,
                        error = renameError,
                        onText = { renameText = it; renameError = null },
                        onSave = {
                            val refusal = onRename(target.id, renameText)
                            if (refusal == null) renaming = null else renameError = refusal
                        },
                        onCancel = { renaming = null },
                        autoFocus = autoFocus,
                    )
                }
                LazyColumn(
                    Modifier.heightIn(max = LIST_MAX_HEIGHT).testTag(OutlineTags.LIST)
                        .semantics { collectionInfo = CollectionInfo(rowCount = entries.size, columnCount = 1) },
                ) {
                    typedItemsIndexed(entries, key = { _, entry -> entry.id.value }) { index, entry ->
                        Text(
                            entry.label,
                            Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = TARGET_DP.dp)
                                .testTag(OutlineTags.row(index + 1))
                                .clickable(onClickLabel = S.SELECT_ACTION) { onSelect(entry) }
                                .padding(vertical = 14.dp)
                                .semantics {
                                    role = Role.Button
                                    contentDescription = entry.spoken
                                    customActions = listOf(
                                        CustomAccessibilityAction(S.SELECT_ACTION) { onSelect(entry); true },
                                        CustomAccessibilityAction(S.RENAME_ACTION) {
                                            renaming = entry
                                            renameText = entry.label
                                            renameError = null
                                            true
                                        },
                                    )
                                },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onDismiss, Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(OutlineTags.CLOSE)) { Text(S.CLOSE) }
        },
    )
}

/**
 * "Add shape at..." (E12): the form that places a rectangle, ellipse or diamond from typed numbers, so a user who cannot drag
 * can still draw. [plan] validates the form; on success [onAdd] gets the plan and the host commits and announces it.
 */
@Composable
fun AddShapeAtDialog(
    technical: Boolean,
    format: LengthFormat,
    plan: (ShapeAtForm) -> arrow.core.Either<ShapeAtError, ShapeAtPlan>,
    onAdd: (ShapeAtPlan) -> Unit,
    onDismiss: () -> Unit,
    /** A focused field blinks its cursor forever, which a Compose test cannot wait out; a test passes false. */
    autoFocus: Boolean = true,
) {
    var form by remember { mutableStateOf(ShapeAtForm()) }
    var error by remember { mutableStateOf<ShapeAtError?>(null) }
    val firstField = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) runCatching { firstField.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ShapeAtTags.DIALOG),
        title = { Text(S.ADD_TITLE, Modifier.semantics { heading() }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(S.ADD_HELP, style = MaterialTheme.typography.bodySmall)
                if (technical) Text(S.unitHint(format.displayName), style = MaterialTheme.typography.bodySmall)
                Column(Modifier.selectableGroup().semantics { contentDescription = S.KIND_LABEL }) {
                    ShapeAtKind.entries.forEach { kind ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = TARGET_DP.dp)
                                .testTag(ShapeAtTags.kind(kind))
                                .selectable(selected = form.kind == kind, role = Role.RadioButton) { form = form.copy(kind = kind) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = form.kind == kind, onClick = null)
                            Text(kind.label, Modifier.padding(start = 8.dp))
                        }
                    }
                }
                AddShapeAtFields(form, error, onForm = { form = it; error = null }, firstField = firstField)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { plan(form).fold({ error = it }, onAdd) },
                modifier = Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(ShapeAtTags.CONFIRM),
            ) { Text(S.ADD_CONFIRM) }
        },
        dismissButton = {
            TextButton(onDismiss, Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(ShapeAtTags.CANCEL)) { Text(S.ADD_CANCEL) }
        },
    )
}

/** The rename field of the outline with its Save and Cancel; split out so a test can compose it without a dialog window. */
@Composable
internal fun RenameBar(text: String, error: String?, onText: (String) -> Unit, onSave: () -> Unit, onCancel: () -> Unit, autoFocus: Boolean) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) runCatching { focus.requestFocus() } }
    OutlinedTextField(
        text,
        onText,
        Modifier.fillMaxWidth().focusRequester(focus).testTag(OutlineTags.RENAME_FIELD),
        label = { Text(S.RENAME_FIELD) },
        isError = error != null,
        singleLine = true,
    )
    error?.let { FieldError(it) }
    Row {
        TextButton(onSave, Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(OutlineTags.RENAME_SAVE)) { Text(S.RENAME_SAVE) }
        TextButton(onCancel, Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(OutlineTags.RENAME_CANCEL)) { Text(S.RENAME_CANCEL) }
    }
}

@Composable
private fun FieldError(message: String) {
    Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

/** The four typed fields of "Add shape at..."; split out so a test can compose them without a dialog window. */
@Composable
internal fun AddShapeAtFields(form: ShapeAtForm, error: ShapeAtError?, onForm: (ShapeAtForm) -> Unit, firstField: FocusRequester) {
    ShapeAtField.entries.forEach { field ->
        val message = error?.takeIf { it.field == field }?.message
        OutlinedTextField(
            form.text(field),
            { onForm(form.with(field, it)) },
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = TARGET_DP.dp)
                .testTag(ShapeAtTags.field(field))
                .let { if (field == ShapeAtField.X) it.focusRequester(firstField) else it },
            label = { Text(field.label) },
            isError = message != null,
            singleLine = true,
        )
        message?.let { FieldError(it) }
    }
}

@Composable
internal fun OutlineHost(controller: SketchEditorController) {
    val selection = controller.ui.selection.mapTo(LinkedHashSet(), ::ElementId)
    val entries = remember(controller.revision, selection) { SketchOutlineModel.build(controller.doc.scene, selection) }
    SketchOutlineDialog(
        entries = entries,
        onSelect = { entry ->
            controller.view.reveal(entry.id)
            controller.notifier.announce(S.selectedAnnouncement(entry.label))
            controller.overlay = null
        },
        onRename = { id, text ->
            controller.submit(ElementsPanelModel.rename(id, text))?.let { ElementsPanelModel.renameError(it) ?: it.toString() }
        },
        onDismiss = { controller.overlay = null },
    )
}

@Composable
internal fun AddShapeAtHost(controller: SketchEditorController, extras: EditorExtras) {
    val technical = controller.sheetKind == SheetKind.Technical
    val format = extras.lengthUnit.format
    val id = remember { controller.ids.next() }
    AddShapeAtDialog(
        technical = technical,
        format = format,
        plan = { form -> ShapeAtModel.plan(form, technical, format, id, controller.defaultStyle) },
        onAdd = { plan ->
            controller.commitToolCommand(plan.command)
            controller.view.reveal(plan.command.element.id)
            controller.notifier.announce(plan.announcement)
            controller.overlay = null
        },
        onDismiss = { controller.overlay = null },
    )
}
