// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.export.DrawingScale
import dev.stapler.stelekit.sketch.v2.export.Orientation
import dev.stapler.stelekit.sketch.v2.export.PaperSize
import dev.stapler.stelekit.sketch.v2.export.ScalePresets
import dev.stapler.stelekit.sketch.v2.export.SheetPlan
import dev.stapler.stelekit.sketch.v2.export.SheetSetup
import dev.stapler.stelekit.sketch.v2.export.TitleBlock
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportFormat
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportRequest
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchImportExportStrings.Export as Strings

object ExportPageTags {
    const val PAGE_ON = "sketch.export.page.on"
    const val PREVIEW = "sketch.export.page.preview"
    const val FIT = "sketch.export.page.fit"
    const val FIT_BUTTON = "sketch.export.page.fitbutton"
    const val SUGGESTION = "sketch.export.page.suggestion"
    const val TILED = "sketch.export.page.tiled"
    const val SCALE_BAR = "sketch.export.page.scalebar"
    const val NORTH = "sketch.export.page.north"
    const val TITLE_BLOCK = "sketch.export.page.titleblock"
    const val SCALE_CUSTOM = "sketch.export.page.scalecustom"
    const val PAPER_CUSTOM = "sketch.export.page.papercustom"
    const val PAPER_CUSTOM_BUTTON = "sketch.export.page.papercustombutton"
    const val SCALE_CUSTOM_BUTTON = "sketch.export.page.scalecustombutton"
    const val TITLE_EDIT = "sketch.export.page.titleedit"
    const val TITLE_SHOWN = "sketch.export.page.titleshown"
    const val FIELDS_DIALOG = "sketch.export.page.fields"
    const val FIELDS_APPLY = "sketch.export.page.fields.apply"
    fun north(degrees: Int) = "sketch.export.page.north.$degrees"
    fun paper(id: String) = "sketch.export.page.paper.$id"
    fun orientation(o: Orientation) = "sketch.export.page.orientation.${o.name.lowercase()}"
    fun margin(index: Int) = "sketch.export.page.margin.$index"
    fun scale(ratio: String) = "sketch.export.page.scale.$ratio"
    fun field(name: String) = "sketch.export.page.field.$name"
    fun dpi(value: Int) = "sketch.export.page.dpi.$value"
}

private const val PAGE_TARGET_DP = 44
private const val FIELD_RADIUS_DP = 4
private const val FIELD_PADDING_DP = 8
private const val FIELD_TITLE = "title"
private const val FIELD_AUTHOR = "author"
private const val FIELD_DATE = "date"
private const val FIELD_SHEET = "sheet"
private const val FIELD_NOTES = "notes"
private const val DEFAULT_NORTH = 0
private val NORTH_TURNS = listOf(0, 45, 90, 135, 180, 225, 270, 315)
private const val PREVIEW_HEIGHT_DP = 220
private const val DASH_ON = 6f
private const val DASH_OFF = 4f
private const val BORDER_PX = 2f
private const val TINT = 0.25f
private const val WASH = 0.08f

/** The page part of the export dialog: paper, orientation, margins, drawing scale, furniture, resolution, tiling, the fit check and the page preview. */
@Composable
internal fun PageSection(model: ExportDialogModel, open: ExportUiState.Open, update: (ExportRequest) -> Unit) {
    val request = open.request
    var editor by remember { mutableStateOf<FieldsEditor?>(null) }
    editor?.let { FieldsDialog(it) { editor = null } }
    if (!model.usesPage(request.format)) return
    if (model.pageIsOptional(request.format)) {
        OptionGroup(Strings.PAGE) { CheckRow(Strings.PAGE_ON, ExportPageTags.PAGE_ON, request.sheet != null) { update(model.withPage(request, it)) } }
    }
    val setup = request.sheet ?: return
    val change = { next: SheetSetup -> update(request.copy(sheet = next)) }
    PaperOptions(setup, change) { editor = it }
    OptionGroup(Strings.ORIENTATION) {
        for (o in Orientation.entries) {
            Option(if (o == Orientation.Portrait) Strings.PORTRAIT else Strings.LANDSCAPE, setup.orientation == o, null, ExportPageTags.orientation(o)) { change(setup.copy(orientation = o)) }
        }
    }
    OptionGroup(Strings.MARGINS) {
        SheetSetup.margins(setup.paper).forEachIndexed { i, margin ->
            val label = listOf(Strings.MARGIN_NARROW, Strings.MARGIN_NORMAL, Strings.MARGIN_WIDE)[i]
            Option(label, setup.margin == margin, null, ExportPageTags.margin(i)) { change(setup.copy(margin = margin)) }
        }
    }
    ScaleOptions(model, request, setup, change, update) { editor = it }
    FurnitureOptions(setup, change) { editor = it }
    if (request.format == ExportFormat.Png) {
        OptionGroup(Strings.RESOLUTION) {
            for (dpi in ExportRequest.DPIS) Option(Strings.dpi(dpi), request.dpi == dpi, null, ExportPageTags.dpi(dpi)) { update(request.copy(dpi = dpi)) }
        }
    }
    val preview = open.preview as? ExportPreview.Page ?: return
    FitReport(model, preview, request, update)
    PagePreview(preview.plan)
    preview.pixelText?.let { Text("${Strings.PREVIEW}: $it", Modifier.testTag(ExportTags.PREVIEW), style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun PaperOptions(setup: SheetSetup, change: (SheetSetup) -> Unit, edit: (FieldsEditor) -> Unit) {
    val custom = PaperSize.presets.none { it.id == setup.paper.id }
    OptionGroup(Strings.PAPER) {
        for (paper in PaperSize.presets) {
            Option(paper.label, setup.paper.id == paper.id, null, ExportPageTags.paper(paper.id)) {
                val margin = if (setup.margin == SheetSetup.defaultMargin(setup.paper)) SheetSetup.defaultMargin(paper) else setup.margin
                change(setup.copy(paper = paper, margin = margin))
            }
        }
        if (custom) Option(setup.paper.label, true, null, ExportPageTags.paper(setup.paper.id)) {}
        TextButton(
            onClick = {
                val field = FieldSpec(Strings.PAPER_CUSTOM, if (custom) setup.paper.id else "", ExportPageTags.PAPER_CUSTOM, singleLine = true)
                edit(FieldsEditor(Strings.PAPER, listOf(field), { PaperSize.parse(it.single()) != null }) { v ->
                    PaperSize.parse(v.single())?.let { change(setup.copy(paper = it, margin = SheetSetup.defaultMargin(it))) }
                })
            },
            modifier = Modifier.defaultMinSize(minHeight = PAGE_TARGET_DP.dp).testTag(ExportPageTags.PAPER_CUSTOM_BUTTON),
        ) { Text(Strings.CUSTOM_BUTTON) }
    }
}

@Composable
private fun ScaleOptions(model: ExportDialogModel, request: ExportRequest, setup: SheetSetup, change: (SheetSetup) -> Unit, update: (ExportRequest) -> Unit, edit: (FieldsEditor) -> Unit) {
    val presets = ScalePresets.family(model.metric)
    OptionGroup(Strings.DRAWING_SCALE) {
        for (preset in presets) {
            Option(preset.label, setup.scale == preset.scale, null, ExportPageTags.scale(preset.scale.ratio)) { change(setup.copy(scale = preset.scale)) }
        }
        if (presets.none { it.scale == setup.scale }) Option(setup.scale.label, true, null, ExportPageTags.scale(setup.scale.ratio)) {}
        TextButton(
            onClick = {
                val field = FieldSpec(Strings.SCALE_CUSTOM, setup.scale.ratio, ExportPageTags.SCALE_CUSTOM, singleLine = true)
                edit(FieldsEditor(Strings.DRAWING_SCALE, listOf(field), { DrawingScale.parse(it.single()) != null }) { v ->
                    DrawingScale.parse(v.single())?.let { change(setup.copy(scale = it)) }
                })
            },
            modifier = Modifier.defaultMinSize(minHeight = PAGE_TARGET_DP.dp).testTag(ExportPageTags.SCALE_CUSTOM_BUTTON),
        ) { Text(Strings.CUSTOM_BUTTON) }
        TextButton(onClick = { update(model.fitted(request)) }, Modifier.defaultMinSize(minHeight = PAGE_TARGET_DP.dp).testTag(ExportPageTags.FIT_BUTTON)) { Text(Strings.FIT_TO_PAGE) }
    }
}

@Composable
private fun FurnitureOptions(setup: SheetSetup, change: (SheetSetup) -> Unit, edit: (FieldsEditor) -> Unit) {
    CheckRow(Strings.SCALE_BAR, ExportPageTags.SCALE_BAR, setup.scaleBar) { change(setup.copy(scaleBar = it)) }
    CheckRow(Strings.NORTH_ARROW, ExportPageTags.NORTH, setup.northDegrees != null) { change(setup.copy(northDegrees = if (it) DEFAULT_NORTH else null)) }
    setup.northDegrees?.let { degrees ->
        OptionGroup(Strings.NORTH_ANGLE) {
            for (turn in NORTH_TURNS) Option(Strings.degrees(turn), degrees == turn, null, ExportPageTags.north(turn)) { change(setup.copy(northDegrees = turn)) }
        }
    }
    CheckRow(Strings.TITLE_BLOCK, ExportPageTags.TITLE_BLOCK, setup.titleBlock != null) { change(setup.copy(titleBlock = if (it) TitleBlock() else null)) }
    val block = setup.titleBlock ?: return
    TextButton(
        onClick = {
            val fields = listOf(
                FieldSpec(Strings.FIELD_TITLE, block.title, ExportPageTags.field(FIELD_TITLE), singleLine = true),
                FieldSpec(Strings.FIELD_AUTHOR, block.author, ExportPageTags.field(FIELD_AUTHOR), singleLine = true),
                FieldSpec(Strings.FIELD_DATE, block.date, ExportPageTags.field(FIELD_DATE), singleLine = true),
                FieldSpec(Strings.FIELD_SHEET, block.sheet, ExportPageTags.field(FIELD_SHEET), singleLine = true),
                FieldSpec(Strings.FIELD_NOTES, block.notes, ExportPageTags.field(FIELD_NOTES), singleLine = false),
            )
            edit(FieldsEditor(Strings.TITLE_BLOCK, fields, { true }) { v -> change(setup.copy(titleBlock = TitleBlock(v[0], v[1], v[2], v[3], v[4]))) })
        },
        modifier = Modifier.defaultMinSize(minHeight = PAGE_TARGET_DP.dp).testTag(ExportPageTags.TITLE_EDIT),
    ) { Text(Strings.TITLE_BLOCK_EDIT) }
    if (block.title.isNotBlank()) Text(block.title, Modifier.testTag(ExportPageTags.TITLE_SHOWN), style = MaterialTheme.typography.bodySmall)
}

/** One text field of a [FieldsEditor]. */
private class FieldSpec(val label: String, val initial: String, val tag: String, val singleLine: Boolean)

/** What the small field dialog edits: [fields], whether the values are [valid] to apply, and what [apply] does with them. */
private class FieldsEditor(val title: String, val fields: List<FieldSpec>, val valid: (List<String>) -> Boolean, val apply: (List<String>) -> Unit)

/**
 * A dialog of text fields of its own. The fields are not inline in the export dialog: a text field inside that dialog's scrolling
 * column left its window re-measuring without end under Robolectric, and a field-only dialog is the shape the Go to element dialog
 * already proves. Apply is offered only for values [FieldsEditor.valid] accepts, so nothing half-typed reaches the setup.
 */
@Composable
private fun FieldsDialog(editor: FieldsEditor, onDismiss: () -> Unit) {
    var values by remember { mutableStateOf(editor.fields.map { it.initial }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ExportPageTags.FIELDS_DIALOG),
        title = { Text(editor.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                editor.fields.forEachIndexed { i, field ->
                    PageField(field.label, values[i], field.tag, field.singleLine) { typed -> values = values.toMutableList().also { it[i] = typed } }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.CANCEL) } },
        confirmButton = {
            TextButton(onClick = { editor.apply(values); onDismiss() }, enabled = editor.valid(values), modifier = Modifier.testTag(ExportPageTags.FIELDS_APPLY)) { Text(Strings.APPLY) }
        },
    )
}

/** A labelled text field on [BasicTextField] (the Material field adds nothing the dialog needs). */
@Composable
private fun PageField(label: String, value: String, tag: String, singleLine: Boolean, onChange: (String) -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = PAGE_TARGET_DP.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(FIELD_RADIUS_DP.dp)).padding(FIELD_PADDING_DP.dp).testTag(tag),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = ink),
            singleLine = singleLine,
            cursorBrush = SolidColor(ink),
        )
    }
}

@Composable
private fun CheckRow(label: String, tag: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.defaultMinSize(minHeight = PAGE_TARGET_DP.dp).testTag(tag).toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

/** The fit check in words, the scale that would fit, and for a PDF the tiling choice. */
@Composable
private fun FitReport(model: ExportDialogModel, preview: ExportPreview.Page, request: ExportRequest, update: (ExportRequest) -> Unit) {
    val check = model.fitCheck(preview.plan)
    val color = if (check.fits) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(check.text, Modifier.testTag(ExportPageTags.FIT), color = color, style = MaterialTheme.typography.bodySmall)
        if (!check.fits) {
            val suggestion = check.suggestion?.let { Strings.suggestion(it.label) } ?: Strings.NO_FIT
            Text(suggestion, Modifier.testTag(ExportPageTags.SUGGESTION), style = MaterialTheme.typography.bodySmall)
            if (model.usesTiling(request)) CheckRow(Strings.TILE_PAGES, ExportPageTags.TILED, request.tiled) { update(request.copy(tiled = it)) }
        }
        if (preview.blocked) Text(Strings.tooManyPages(preview.plan.pageCount, dev.stapler.stelekit.sketch.v2.export.SheetPlanner.PAGE_BUDGET), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

/** A small picture of the page: the sheet, its margins, the drawing at the chosen scale, and the tile grid when the drawing needs several pages. */
@Composable
private fun PagePreview(plan: SheetPlan) {
    val line = MaterialTheme.colorScheme.outline
    val ok = MaterialTheme.colorScheme.primary
    val bad = MaterialTheme.colorScheme.error
    val paper = Color.White
    val description = plan.setup.scale.label
    Canvas(
        Modifier.fillMaxWidth().height(PREVIEW_HEIGHT_DP.dp).testTag(ExportPageTags.PREVIEW)
            .semantics { contentDescription = description },
    ) {
        // The page is drawn as large as fits the box, centred: a fixed-height box, since a size that depends on the width loops inside a wrapping dialog window.
        val k = minOf(size.width / plan.pageWidth, size.height / plan.pageHeight).toFloat()
        val left = (size.width - (plan.pageWidth * k).toFloat()) / 2
        val top = (size.height - (plan.pageHeight * k).toFloat()) / 2
        fun rect(x: Double, y: Double, w: Double, h: Double) = Offset(left + (x * k).toFloat(), top + (y * k).toFloat()) to Size((w * k).toFloat(), (h * k).toFloat())
        val (pageOrigin, pageSize) = rect(0.0, 0.0, plan.pageWidth, plan.pageHeight)
        drawRect(paper, pageOrigin, pageSize)
        drawRect(line, pageOrigin, pageSize, style = Stroke(BORDER_PX))
        val dashed = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH_ON, DASH_OFF)))
        rect(plan.printable.x, plan.printable.y, plan.printable.width, plan.printable.height).let { (o, s) -> drawRect(line, o, s, style = dashed) }
        plan.titleBand?.let { rect(it.x, it.y, it.width, it.height).let { (o, s) -> drawRect(line.copy(alpha = TINT), o, s) } }
        plan.strip?.let { rect(it.x, it.y, it.width, it.height).let { (o, s) -> drawRect(line.copy(alpha = WASH), o, s) } }
        val tile = plan.tiles.first()
        val (ox, oy) = plan.origin(tile)
        val tint = if (plan.fits) ok else bad
        val (o, s) = rect(ox, oy, plan.scaledWidth, plan.scaledHeight)
        drawRect(tint.copy(alpha = TINT), o, s)
        drawRect(tint, o, s, style = Stroke(BORDER_PX))
        if (!plan.fits) {
            for (c in 1 until plan.columns) {
                val x = rect(ox + c * plan.area.width, 0.0, 0.0, 0.0).first.x
                drawLine(bad, Offset(x, o.y), Offset(x, o.y + s.height), BORDER_PX)
            }
            for (r in 1 until plan.rows) {
                val y = rect(0.0, oy + r * plan.area.height, 0.0, 0.0).first.y
                drawLine(bad, Offset(o.x, y), Offset(o.x + s.width, y), BORDER_PX)
            }
        }
    }
}
