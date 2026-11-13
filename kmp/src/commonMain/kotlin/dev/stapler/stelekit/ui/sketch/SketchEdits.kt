package dev.stapler.stelekit.ui.sketch

import dev.stapler.stelekit.sketch.SketchCommand
import dev.stapler.stelekit.sketch.SketchPoint
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

/** Non-drag "Move vertex N": one committed history step. */
fun SketchViewModel.moveVertex(vertex: VertexRef, to: SketchPoint) = commit(SketchCommand.MoveVertex(vertex.primitiveId, vertex.index, to))

/** Sets a rectangle's width and/or height from typed lengths; null leaves that side alone. */
fun SketchViewModel.setRectSize(id: String, width: Inches?, height: Inches?) = commit(SketchCommand.SetRectSize(id, width, height))

/** Applies a finished drag: draws a new shape, or commits the moved handle or shape as one history step. */
fun SketchViewModel.commitDrag(intent: DragIntent, preview: DragPreview) {
    when (intent) {
        DragIntent.Pan -> Unit
        is DragIntent.Draw -> drawShape(intent.start, preview.point)
        is DragIntent.MoveVertex -> {
            select(intent.ref.primitiveId)
            moveVertex(intent.ref, preview.point)
        }
        is DragIntent.MoveShape -> {
            select(intent.id)
            val before = uiState.value.document.scene.primitives.firstOrNull { it.id == intent.id }?.vertices?.firstOrNull() ?: return
            val dx = preview.shape.vertices.first().x.sixteenths - before.x.sixteenths
            val dy = preview.shape.vertices.first().y.sixteenths - before.y.sixteenths
            if (dx != 0L || dy != 0L) commit(SketchCommand.MoveShape(intent.id, dx, dy))
        }
    }
}
