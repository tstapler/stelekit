// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.ui.sketchimport.ImportPhase
import kotlinx.coroutines.launch

/** Picks a drawing and opens the existing import flow for it (Story 5.9d). */
fun ProjectListViewModel.addDrawing() {
    val action = drawings ?: return
    val project = state.value.projects.firstOrNull() ?: return setMessage("Create a project first")
    if (drawingSession.value != null) return
    scope.launch {
        when (val start = action.start(project.uuid)) {
            DrawingStart.Cancelled -> Unit
            is DrawingStart.Refused -> setMessage(start.message)
            is DrawingStart.Started -> drawingSession.value = start.session
        }
    }
}

/** Called once the import screen reports Save or Cancel; only a saved drawing is recorded. */
fun ProjectListViewModel.finishDrawing(phase: ImportPhase, registry: DimensionRegistry?) {
    val session = drawingSession.value ?: return
    drawingSession.value = null
    val action = drawings
    if (phase != ImportPhase.Done || action == null) return
    scope.launch {
        action.finish(session, registry)?.let(::setMessage)
        reloadBound()
    }
}

fun ProjectListViewModel.removeDrawing(contentHash: String) {
    val service = sheets ?: return
    val project = state.value.projects.firstOrNull() ?: return
    scope.launch {
        service.detach(project.uuid, contentHash)
        reloadBound()
    }
}

