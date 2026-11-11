package dev.stapler.stelekit.ui.sketch

import dev.stapler.stelekit.sketch.SketchCommand
import dev.stapler.stelekit.sketch.SketchSheetKind
import dev.stapler.stelekit.sketch.ViewDirection
import dev.stapler.stelekit.units.Inches

// Thin command wrappers for the sheet panels; each is one committed history step.

/** Sets the selected-or-given line to a typed length; the field validates the text first (see `DimensionInput`). */
fun SketchViewModel.setLineLength(id: String, length: Inches) = commit(SketchCommand.SetLineLength(id, length))

fun SketchViewModel.addLayer(name: String) = commit(SketchCommand.AddLayer(name))

fun SketchViewModel.setLayerVisible(name: String, visible: Boolean) = commit(SketchCommand.SetLayerVisible(name, visible))

fun SketchViewModel.setLayerLocked(name: String, locked: Boolean) = commit(SketchCommand.SetLayerLocked(name, locked))

fun SketchViewModel.setSelectedLayer(layer: String) {
    uiState.value.selectedId?.let { commit(SketchCommand.SetPrimitiveLayer(it, layer)) }
}

fun SketchViewModel.setSheetKind(kind: SketchSheetKind) = commit(SketchCommand.SetSheetKind(kind))

fun SketchViewModel.setViewDirection(direction: ViewDirection) = commit(SketchCommand.SetViewDirection(direction))

fun SketchViewModel.setScale(sixteenths: Long) = commit(SketchCommand.SetScale(sixteenths))

fun SketchViewModel.setZoneTotal(key: String, isTotal: Boolean) = commit(SketchCommand.SetZoneTotal(key, isTotal))

fun SketchViewModel.removeZone(key: String) = commit(SketchCommand.RemoveZone(key))
