// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.view.SceneBounds
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.sketch.v2.view.ScreenRect
import dev.stapler.stelekit.sketch.v2.view.SelectionReveal
import dev.stapler.stelekit.sketch.v2.view.SketchViewport

/** What the editor does with the view and the selection's visibility: pan, zoom and fit keys, Go to element and a hidden layer (UX-13). */
class EditorView(
    private val ui: SketchEditorState,
    private val doc: () -> SketchDocumentV2,
    private val select: (List<ElementId>) -> Unit,
    private val notifier: EditorNotifier,
) {
    /** The canvas size in pixels; the host reports it so fit and page-pan know the view. */
    var canvasSize: ScreenPx by mutableStateOf(ScreenPx(0.0, 0.0))

    /** Pixels per dp of the canvas's display, which turns the 40 dp and 200 dp pan steps into pixels. */
    var pxPerDp: Double = 1.0

    /** The view that shows the whole scene, or the current one for an empty scene. */
    fun fit(): SketchViewport = ui.viewport.fit(SceneBounds.of(doc().scene.elementsInOrder.asIterable()), canvasSize.x, canvasSize.y, FIT_PADDING_PX)

    /** Zooms and pans so the selection fills the view with room around it ("Zoom to selection"); it does nothing for an empty selection. */
    fun zoomToSelection() {
        val bounds = SceneBounds.of(ui.selection.mapNotNull { doc().scene[ElementId(it)] }.asIterable()) ?: return
        ui.viewport = ui.viewport.fit(bounds, canvasSize.x, canvasSize.y, SELECTION_PADDING_PX)
    }

    /** Runs a view key; false for a key that is not a view key. */
    fun apply(action: KeyAction): Boolean {
        val canvas = CanvasSize(canvasSize.x, canvasSize.y, pxPerDp)
        val panned = KeyboardPanning.apply(action, ui.viewport, canvas, ::fit)
        if (panned != null) {
            ui.viewport = panned
            notifier.queue.announceStatus(KeyboardPanning.settledAnnouncement(panned, canvas), KeyboardPanning.STREAM_KEY)
            return true
        }
        val centre = ScreenPx(canvasSize.x / HALF, canvasSize.y / HALF)
        val viewport = ui.viewport
        ui.viewport = when (action) {
            KeyAction.ZoomIn -> viewport.zoomBy(centre, ZOOM_IN)
            KeyAction.ZoomOut -> viewport.zoomBy(centre, ZOOM_OUT)
            KeyAction.ResetZoom -> viewport.zoomTo(centre, 1.0)
            KeyAction.ZoomToFit -> fit()
            else -> return false
        }
        return true
    }

    /** Selects [id], brings it into view and leaves the Select tool holding it (Go to element). */
    fun reveal(id: ElementId) {
        val element = doc().scene[id] ?: return
        select(listOf(id))
        val bounds = SceneBounds.of(element) ?: return
        ui.viewport = SelectionReveal.reveal(ui.viewport, bounds, ScreenRect(0.0, 0.0, canvasSize.x, canvasSize.y)).viewport
    }

    /** [layer] was hidden: its elements leave the selection and a toast says so (UX-13). */
    fun afterHide(layer: LayerId) {
        val outcome = LayerSelectionRules.afterHide(doc().scene, ui.selection.mapTo(LinkedHashSet(), ::ElementId), layer)
        ui.selection = outcome.selection.mapTo(LinkedHashSet()) { it.value }
        outcome.announcement?.let { notifier.toast(HIDDEN_LAYER_KEY, it) }
    }

    private companion object {
        const val HIDDEN_LAYER_KEY = "layer.hidden"
        const val HALF = 2.0
        const val ZOOM_IN = 1.25
        const val ZOOM_OUT = 0.8
        const val FIT_PADDING_PX = 24.0
        const val SELECTION_PADDING_PX = 120.0
    }
}
