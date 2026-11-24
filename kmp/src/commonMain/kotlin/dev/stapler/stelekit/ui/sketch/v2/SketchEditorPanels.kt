// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.util.UuidGenerator

/** One panel tab's content, fed by the controller's state and calling back into it (Story 6.2.0b). */
@Composable
internal fun SketchPanels(controller: SketchEditorController, tab: PanelTab, extras: EditorExtras, technical: TechnicalPanelState? = null) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        when (tab) {
            PanelTab.Technical -> technical?.let { TechnicalTab(controller, it, Modifier.fillMaxWidth(), extras.lengthUnit) }
            PanelTab.Style -> StyleTab(controller, extras)
            PanelTab.Layers -> LayersTab(controller)
            PanelTab.Constraints -> ConstraintsTab(controller, extras)
            PanelTab.Elements -> ElementsTab(controller)
            PanelTab.Library -> LibraryPanel(controller, extras, Modifier.fillMaxWidth())
        }
    }
}

private fun SketchEditorController.selectedElements() = ui.selection.mapNotNull { doc.scene[ElementId(it)] }

@Composable
private fun StyleTab(controller: SketchEditorController, extras: EditorExtras) {
    Column(Modifier.fillMaxWidth()) {
        // An open dimension label was written in the old unit; it must not be committed in the new one.
        SheetKindRow(controller, onChange = extras.dimension::cancel)
        SheetStyleRow(controller)
        StylePanelContent(controller)
        // Position, size and angle of the one selected element; several elements have no single size to show.
        controller.selectedElements().singleOrNull()?.let { element ->
            ElementStatsPanel(
                element = element,
                technical = controller.sheetKind == dev.stapler.stelekit.sketch.v2.constraints.SheetKind.Technical,
                onCommand = { controller.submit(it) },
                modifier = Modifier.fillMaxWidth(),
                format = extras.lengthUnit.format,
                onFormatCycle = extras.lengthUnit::cycle,
            )
        }
    }
}

@Composable
private fun StylePanelContent(controller: SketchEditorController) {
    StylePanel(
        scene = controller.doc.scene,
        selection = controller.selectedElements(),
        defaults = controller.defaultStyle,
        tool = controller.ui.activeTool,
        onCommand = { controller.submit(it) },
        onDefaults = controller::setDefaults,
        modifier = Modifier.fillMaxWidth(),
        metrics = controller.textMetrics,
        onTextSize = { applyTextSize(controller, it) },
    )
}

@Composable
private fun LayersTab(controller: SketchEditorController) {
    LayersPanel(
        scene = controller.doc.scene,
        activeLayer = controller.activeLayer,
        onActiveLayer = { controller.activeLayer = it },
        onCommand = { controller.submit(it) },
        onNewLayer = { controller.submit(LayersPanelModel.newLayer(controller.doc.scene, LayerId("layer-${UuidGenerator.generateV7()}"))) },
        onVisibilityChange = { layer, visible -> if (!visible) controller.view.afterHide(layer) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ElementsTab(controller: SketchEditorController) {
    Column(Modifier.fillMaxWidth()) {
        if (controller.ui.selection.isNotEmpty()) ArrangeControls(controller, Modifier.fillMaxWidth())
        ElementsPanel(
            scene = controller.doc.scene,
            selection = controller.ui.selection.mapTo(LinkedHashSet(), ::ElementId),
            onSelect = { controller.tools.select(listOf(it)) },
            onCommand = { controller.submit(it) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ConstraintsTab(controller: SketchEditorController, extras: EditorExtras) {
    val creation = controller.constraints
    ConstraintPanel(
        input = ConstraintPanelInput(
            doc = controller.doc,
            analysis = controller.constraintState.analysis,
            technical = controller.sheetKind == dev.stapler.stelekit.sketch.v2.constraints.SheetKind.Technical,
            selection = controller.selectedElements(),
            tier = extras.tier,
            card = extras.dimension.card ?: controller.constraintState.card,
        ),
        actions = ConstraintPanelActions(
            onConstrain = { item ->
                controller.tools.chooseTool(ToolId.Select)
                creation.arm(item)
            },
            onRowSelected = { row -> creation.highlighted = row?.let { setOf(it.id) }.orEmpty() },
            onDelete = creation::remove,
            onSetReference = creation::setReference,
            onConflictAction = { action ->
                extras.dimension.dismissCard()
                controller.onConflictAction(action)
            },
            onEditDimension = extras::editDimension,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The armed picker's markers over the canvas; nothing while no picker is armed. */
@Composable
internal fun ConstraintPickerOverlay(controller: SketchEditorController, pointer: PointerClass) {
    val state = controller.constraints.picker ?: return
    val viewport = controller.ui.viewport
    ConstraintTargetOverlay(
        candidates = controller.constraints.candidates(),
        state = state,
        pointer = pointer,
        toScreen = { x, y -> viewport.toScreen(dev.stapler.stelekit.sketch.v2.scene.ScenePoint(unit(x), unit(y))).let { it.x to it.y } },
        actions = TargetPickerActions(
            onPick = controller.constraints::pick,
            onState = controller.constraints::setState,
            onCancel = controller.constraints::cancel,
        ),
    )
}

private fun unit(v: Double) = dev.stapler.stelekit.sketch.v2.scene.SceneUnit.of(v).getOrNull() ?: dev.stapler.stelekit.sketch.v2.scene.SceneUnit.ZERO
