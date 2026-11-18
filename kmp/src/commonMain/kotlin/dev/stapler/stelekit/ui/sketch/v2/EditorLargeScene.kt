// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.DeleteElements
import dev.stapler.stelekit.sketch.v2.commands.ImportScene
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.diagnostics.DiagnosticsPort
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchHostStrings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * What the editor does about a big drawing (Stories 2.2.3b and 2.2.4, UX-73): past the soft threshold of [SceneLimits] it draws
 * Clean, hides the live constraint glyphs and raises the `LargeDrawing` banner ([degradation]); at the hard cap a refused add
 * shows [hardCap] with its three ways forward. It never changes the drawing. Owns its scope, so [close] ends it.
 */
class EditorLargeScene(
    private val controller: SketchEditorController,
    diagnostics: DiagnosticsPort,
    private val services: HostServices,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + controller.adoption + CoroutineExceptionHandler { _, t ->
            if (t !is CancellationException) controller.log.persistFailed(t::class.simpleName.orEmpty())
            controller.notifier.toast(SketchHostStrings.Errors.EXTRA_FAILED_KEY, SketchHostStrings.Errors.EXTRA_FAILED)
        },
    )
    private val degrader = LargeSceneDegradation(diagnostics, services.limits)

    /** The steps in force for the size of the scene now. */
    var degradation: Degradation by mutableStateOf(Degradation.None)
        private set

    /** The notice of a refused add at the element cap, until it is answered. */
    var hardCap: HardCapMessage? by mutableStateOf(null)
        private set

    init {
        controller.notifier.onRefused = ::onRefused
    }

    /** Reads the counts of the document; the host calls it after every committed edit. */
    fun refresh() {
        val doc = controller.doc
        degradation = degrader.onCounts(doc.scene.order.size, doc.constraints.size)
    }

    /** The banner's button: the effects come back for this session. */
    fun reenableEffects() {
        degrader.reenableEffects()
        refresh()
    }

    /** What an answer of the hard-cap notice does. */
    fun answer(action: CapacityAction) {
        hardCap = null
        when (action) {
            CapacityAction.Ok -> Unit
            CapacityAction.DeleteSome -> controller.ui.panelTab = PanelTab.Elements
            CapacityAction.SplitIntoNewSketch -> splitSelection()
        }
    }

    fun close() = scope.cancel()

    private fun onRefused(error: CommandError) {
        hardCap = CapacityMessages.hardCapFor(error, controller.ui.selection.size, services.limits, canSplit = services.createSheet != null)
    }

    /** Moves the selection into a new sheet, then deletes it here as one undo step; the source keeps it if the new sheet fails. */
    private fun splitSelection() {
        val ids = controller.ui.selection.map(::ElementId)
        val scene = controller.doc.scene
        val moved = ids.mapNotNull { scene[it] }
        if (moved.isEmpty()) return
        scope.launch {
            val document = ImportScene(moved, scene.layers).apply(SketchDocumentV2()).getOrNull()?.doc
            val created = document?.let { services.newSheet(it) }
            if (created == null || created.isLeft()) {
                controller.notifier.toast(SketchHostStrings.Split.FAILED_KEY, SketchHostStrings.Split.FAILED)
            } else {
                controller.submit(DeleteElements(ids))
                controller.notifier.toast(SketchHostStrings.Split.DONE_KEY, SketchHostStrings.Split.done(moved.size))
            }
        }
    }
}
