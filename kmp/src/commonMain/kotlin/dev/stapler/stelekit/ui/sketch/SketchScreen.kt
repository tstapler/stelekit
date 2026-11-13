package dev.stapler.stelekit.ui.sketch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.sketch.SketchPrimitive
import dev.stapler.stelekit.sketch.export.SketchExporter
import dev.stapler.stelekit.sketch.Zone
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Host for the home-sketch editor. Later sketch stories (3.5 to 3.7, 4.4, 5.9) each add one
 * reference to their own composable here.
 */
@Composable
fun SketchScreen(
    viewModel: SketchViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    exporter: SketchExporter? = null,
) {
    val state by viewModel.uiState.collectAsState()
    var panelsOpen by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onNavigateBack) { Text("Back") }
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SketchTool.entries.forEach { tool ->
                    FilterChip(selected = state.tool == tool, onClick = { viewModel.setTool(tool) }, label = { Text(tool.label()) })
                }
                SketchExportButton(state, exporter)
                TextButton(onClick = { panelsOpen = !panelsOpen }) { Text(if (panelsOpen) "Hide sheet panels" else "Sheet panels") }
                Text(state.statusText(), style = MaterialTheme.typography.labelMedium)
            }
            // Undo and redo stay pinned: they must never scroll off a phone-width toolbar.
            TextButton(onClick = viewModel::undo, enabled = state.canUndo) { Text("Undo") }
            TextButton(onClick = viewModel::redo, enabled = state.canRedo) { Text("Redo") }
        }
        if (panelsOpen) {
            SketchPanels(state, viewModel, Modifier.heightIn(max = PANELS_MAX_HEIGHT).verticalScroll(rememberScrollState()))
        }
        Box(Modifier.fillMaxSize()) {
            if (state.phase == SketchPhase.READY) {
                SketchNonDragMode(state, viewModel, Modifier.fillMaxSize())
                state.document.scene.primitives.firstOrNull { it.id == state.selectedId }?.let { selected ->
                    SelectionBar(selected, viewModel, Modifier.align(Alignment.TopStart).padding(8.dp))
                }
            } else {
                Text(state.error ?: "Loading sketch", Modifier.align(Alignment.Center).padding(16.dp))
            }
        }
    }
}

/** What the selected shape offers right now: delete and exact typed sizes, kept on the canvas where the finger already is. */
@Composable
private fun SelectionBar(selected: SketchPrimitive, vm: SketchViewModel, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp, shadowElevation = 2.dp) {
        Column(Modifier.padding(vertical = 4.dp)) {
            TextButton(onClick = vm::deleteSelected) { Text("Delete") }
            when (selected) {
                is SketchPrimitive.Line -> DimensionField(initial = selected.axisLengthText(), onCommit = { vm.setLineLength(selected.id, it) })
                is SketchPrimitive.Rect -> {
                    DimensionField(initial = lengthTextOf(selected.b.x.sixteenths - selected.a.x.sixteenths), label = "Width", onCommit = { vm.setRectSize(selected.id, it, null) })
                    DimensionField(initial = lengthTextOf(selected.b.y.sixteenths - selected.a.y.sixteenths), label = "Height", onCommit = { vm.setRectSize(selected.id, null, it) })
                }
            }
        }
    }
}

@Composable
private fun SketchPanels(state: SketchUiState, vm: SketchViewModel, modifier: Modifier = Modifier) {
    val sheet = state.document.sheet
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetPropertiesPanel(sheet, vm::setSheetKind, vm::setViewDirection, vm::setScale)
        LayerPanel(sheet, state.selectedId != null, vm::addLayer, vm::setLayerVisible, vm::setLayerLocked, vm::setSelectedLayer)
        ZonePanel(state.document.zones, state.zoneReport, { vm.addZone(Zone(it)) }, vm::typeZoneText, vm::setZoneTotal, vm::removeZone)
    }
}

private val PANELS_MAX_HEIGHT = 240.dp

/** The line's length as text when it is horizontal or vertical; a slanted line starts the field empty. */
private fun SketchPrimitive.Line.axisLengthText(): String {
    val dx = b.x.sixteenths - a.x.sixteenths
    val dy = b.y.sixteenths - a.y.sixteenths
    return if (dx == 0L || dy == 0L) lengthTextOf(dx + dy) else ""
}

private fun SketchTool.label() = when (this) {
    SketchTool.LINE -> "Line"
    SketchTool.RECTANGLE -> "Rectangle"
    SketchTool.ZONE_SELECT -> "Select"
}

private fun SketchUiState.statusText(): String = when {
    error != null && saveStatus != SaveStatus.FAILED -> "Not applied: $error"
    zoneReport?.sumViolated == true -> "Zones do not add up to the total"
    saveStatus == SaveStatus.PENDING -> "Saving"
    saveStatus == SaveStatus.FAILED -> "Save failed"
    else -> "Saved"
}
