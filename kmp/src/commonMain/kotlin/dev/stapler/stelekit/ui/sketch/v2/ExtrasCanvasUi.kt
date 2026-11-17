// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.tools.ToolId

private const val DIMENSION_EDITOR_TOP_DP = 56
private const val CHIP_BOTTOM_DP = 72
private const val PANEL_BOTTOM_DP = 8
private const val EDITOR_PAD_DP = 12
private const val STATUS_PAD_DP = 8

/**
 * The surfaces the host layers over the canvas (Task 6.2.0d): the file drop target, constraint glyphs, the dimension suggestion
 * chip, the dimension label editor, the solve progress and the Align, Order and Transform panels. Each draws nothing when it has
 * nothing to show, and a `Hidden` solver tier removes every constraint aid.
 */
@Composable
internal fun BoxScope.ExtrasCanvasOverlays(controller: SketchEditorController, extras: EditorExtras, pointer: PointerClass) {
    val clipboard = LocalClipboardManager.current
    SideEffect { extras.clipboard.port = ClipboardPort({ clipboard.getText()?.text }, { clipboard.setText(AnnotatedString(it)) }) }
    Box(Modifier.fillMaxSize().canvasFileDrop(extras.importer::importDropped))
    if (extras.constraintsAvailable) {
        GlyphOverlay(controller, extras, pointer)
        SuggestionChip(controller, extras)
    }
    DimensionEditorOverlay(extras)
    SolveStatus(controller)
    ExtraPanelHost(controller, extras, Modifier.align(Alignment.BottomCenter).padding(bottom = PANEL_BOTTOM_DP.dp))
}

@Composable
private fun GlyphOverlay(controller: SketchEditorController, extras: EditorExtras, pointer: PointerClass) {
    val doc = controller.doc
    if (doc.constraints.isEmpty()) return
    val viewport = controller.ui.viewport
    val density = LocalDensity.current.density
    val selected = controller.ui.selection.mapTo(LinkedHashSet(), ::ElementId)
    val layout = remember(controller.revision, viewport, selected, pointer, density) {
        val anchors = ConstraintAnchors.anchors(doc) { x, y -> ((x * viewport.zoom + viewport.panX) / density) to ((y * viewport.zoom + viewport.panY) / density) }
        ConstraintGlyphLayout.layout(anchors, viewport.zoom, pointer, selected, showAll = false)
    }
    ConstraintGlyphOverlay(
        doc = doc,
        layout = layout,
        technical = false,
        onSelect = { id -> selectGlyph(controller, id) },
        onEdit = extras::editDimension,
    )
}

private fun selectGlyph(controller: SketchEditorController, id: ConstraintId) {
    controller.constraints.highlighted = setOf(id)
    controller.ui.panelTab = PanelTab.Constraints
}

@Composable
private fun BoxScope.SuggestionChip(controller: SketchEditorController, extras: EditorExtras) {
    val selection = controller.ui.selection
    val idle = extras.dimension.state == DimensionEditState.Closed && controller.constraints.picker == null && controller.ui.activeTool == ToolId.Select
    if (!idle || selection.isEmpty() || controller.readOnly) return
    val dof = controller.constraintState.analysis.dof
    val suggestion = remember(controller.revision, selection, dof, extras.tier) {
        DimensionChips.suggestion(controller.doc, selection.map(::ElementId), dof, extras.tier) { ConstraintId(controller.ids.next().value) }
    } ?: return
    DimensionSuggestionChip(suggestion, onAccept = { extras.dimension.openSuggestion(controller.doc, suggestion) }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = CHIP_BOTTOM_DP.dp))
}

@Composable
private fun BoxScope.DimensionEditorOverlay(extras: EditorExtras) {
    if (extras.dimension.state == DimensionEditState.Closed) return
    Surface(Modifier.align(Alignment.TopCenter).padding(top = DIMENSION_EDITOR_TOP_DP.dp), tonalElevation = 6.dp) {
        DimensionLabelEditor(
            extras.dimension,
            DimensionEditorActions(onSubmit = extras::submitDimension, onChoose = extras::chooseDimension, onMakeDriving = extras::makeDimensionDriving),
            Modifier.padding(EDITOR_PAD_DP.dp),
        )
    }
}

@Composable
private fun BoxScope.SolveStatus(controller: SketchEditorController) {
    val state by controller.solveState.collectAsState()
    SolveProgressIndicator(state, controller::cancelSolve, Modifier.align(Alignment.TopEnd).padding(STATUS_PAD_DP.dp))
}

/** The Import and Export dialogs; each draws nothing until its controller opens it. */
@Composable
internal fun ExtrasDialogs(extras: EditorExtras) {
    val clipboard = LocalClipboardManager.current
    ExcalidrawImportDialogs(extras.importer, onCopyDetails = { clipboard.setText(AnnotatedString(it)) }, canCreateSheet = extras.canCreateSheet)
    ExportDialog(extras.exporter)
    LabelDialog(extras.engine)
}
