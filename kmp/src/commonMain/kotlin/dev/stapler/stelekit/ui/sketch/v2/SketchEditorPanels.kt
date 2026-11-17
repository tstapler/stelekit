// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.stapler.stelekit.sketch.constraints.SolverTier
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.util.UuidGenerator

/** One panel tab's content, fed by the controller's state and calling back into it (Story 6.2.0b). */
@Composable
internal fun SketchPanels(controller: SketchEditorController, tab: PanelTab) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        when (tab) {
            PanelTab.Style -> StyleTab(controller)
            PanelTab.Layers -> LayersTab(controller)
            PanelTab.Constraints -> ConstraintsTab(controller)
            PanelTab.Elements -> ElementsTab(controller)
            PanelTab.Library -> Unit
        }
    }
}

private fun SketchEditorController.selectedElements() = ui.selection.mapNotNull { doc.scene[ElementId(it)] }

@Composable
private fun StyleTab(controller: SketchEditorController) {
    StylePanel(
        scene = controller.doc.scene,
        selection = controller.selectedElements(),
        defaults = controller.defaultStyle,
        tool = controller.ui.activeTool,
        onCommand = { controller.submit(it) },
        onDefaults = controller::setDefaults,
        modifier = Modifier.fillMaxWidth(),
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
        onVisibilityChanged = { layer, visible -> if (!visible) controller.afterHide(layer) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ElementsTab(controller: SketchEditorController) {
    ElementsPanel(
        scene = controller.doc.scene,
        selection = controller.ui.selection.mapTo(LinkedHashSet(), ::ElementId),
        onSelect = { controller.select(listOf(it)) },
        onCommand = { controller.submit(it) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ConstraintsTab(controller: SketchEditorController) {
    val creation = controller.constraints
    ConstraintPanel(
        input = ConstraintPanelInput(
            doc = controller.doc,
            analysis = controller.analysis,
            technical = false,
            selection = controller.selectedElements(),
            tier = SolverTier.Full,
            card = controller.conflictCard,
        ),
        actions = ConstraintPanelActions(
            onConstrain = { item ->
                controller.chooseTool(ToolId.Select)
                creation.arm(item)
            },
            onRowSelected = { row -> creation.highlighted = row?.let { setOf(it.id) }.orEmpty() },
            onDelete = creation::remove,
            onSetReference = creation::setReference,
            onConflictAction = controller::onConflictAction,
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
