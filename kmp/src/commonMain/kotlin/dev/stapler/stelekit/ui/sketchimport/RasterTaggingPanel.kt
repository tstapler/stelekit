// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketchimport

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.sketchimport.RasterInputError
import dev.stapler.stelekit.sketchimport.RasterTagging
import dev.stapler.stelekit.sketchimport.TagChoice
import dev.stapler.stelekit.sketchimport.TaggingItem
import dev.stapler.stelekit.ui.annotate.AnnotationCrosshairLayer
import dev.stapler.stelekit.ui.annotate.AnnotationPlacementControls
import dev.stapler.stelekit.ui.annotate.toScreen
import dev.stapler.stelekit.ui.placement.crosshairPoint
import dev.stapler.stelekit.units.Rational

private val POINT_COLOR = Color(0xFF1E88E5)

/**
 * Calibration and tagging of a PNG sheet (Story 4.5b): place two points and type the real length to set the scale, then place
 * two points per further dimension and bind each to a registry key. Stateless: [raster] and the callbacks come from the caller.
 */
@Composable
fun RasterTaggingPanel(
    raster: RasterState,
    keys: List<String>,
    image: ByteArray?,
    onEdit: ((RasterState) -> RasterState) -> Unit,
    onBind: (choice: TagChoice, toReference: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (raster.isCalibrated) "Scale set. Place two points on a length, then pick its dimension." else "Place two points on a length you know, then type it.")
        RasterImage(raster, image, onEdit)
        AnnotationPlacementControls(
            session = raster.placement,
            onToggle = { onEdit { it.toggled() } },
            onNudge = { direction, step -> onEdit { it.nudged(direction, step) } },
            onConfirm = { onEdit { it.placed() } },
            onCancel = { onEdit { it.toggled() } },
            modifier = Modifier.fillMaxWidth(),
        )
        raster.error?.let { Text(rasterErrorText(it)) }
        if (raster.isCalibrated) CalibratedSection(raster, keys, onEdit, onBind) else CalibrationSection(raster, onEdit)
    }
}

@Composable
private fun RasterImage(raster: RasterState, image: ByteArray?, onEdit: ((RasterState) -> RasterState) -> Unit) {
    var canvas by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(raster.size.widthPx.toFloat() / raster.size.heightPx.toFloat())
            .onSizeChanged { canvas = it }
            .pointerInput(canvas) {
                detectTapGestures { at ->
                    if (canvas.width > 0 && canvas.height > 0) {
                        val point = NormalizedPoint((at.x / canvas.width).toDouble().coerceIn(0.0, 1.0), (at.y / canvas.height).toDouble().coerceIn(0.0, 1.0))
                        onEdit { it.tapped(point) }
                    }
                }
            },
    ) {
        if (image != null) AsyncImage(model = image, contentDescription = "PNG drawing", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        PlacedPoints(raster.points, canvas)
        AnnotationCrosshairLayer(raster.placement.state.crosshairPoint, canvas)
    }
}

@Composable
private fun PlacedPoints(points: List<NormalizedPoint>, canvas: IntSize) {
    if (points.isEmpty() || canvas == IntSize.Zero) return
    Canvas(Modifier.fillMaxSize()) {
        val screen = points.map { it.toScreen(canvas) }
        if (screen.size == RasterState.LINE_POINTS) drawLine(POINT_COLOR, screen[0], screen[1], strokeWidth = 3.dp.toPx())
        screen.forEach { drawCircle(POINT_COLOR, radius = 6.dp.toPx(), center = Offset(it.x, it.y)) }
    }
}

@Composable
private fun CalibrationSection(raster: RasterState, onEdit: ((RasterState) -> RasterState) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${raster.points.size} of 2 points placed")
        OutlinedTextField(
            value = raster.typedLength,
            onValueChange = { text -> onEdit { it.typed(text) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Real length between the points, e.g. 5 3/8\" or 12'-6\"") },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onEdit { it.calibrated() } }, enabled = raster.canCalibrate) { Text("Set scale") }
            TextButton(onClick = { onEdit { it.clearedLine() } }) { Text("Clear points") }
        }
    }
}

@Composable
private fun CalibratedSection(
    raster: RasterState,
    keys: List<String>,
    onEdit: ((RasterState) -> RasterState) -> Unit,
    onBind: (TagChoice, Boolean) -> Unit,
) {
    var bindingReference by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        raster.measuredText?.let { measured ->
            Text("Measured: $measured")
            TaggingRow(rasterItem("Measured $measured"), null, keys) { _, choice -> onBind(choice, false) }
        } ?: Text("${raster.points.size} of 2 points placed")
        raster.reference?.let { ref ->
            if (raster.tags.none { !it.measured }) {
                TextButton(onClick = { bindingReference = !bindingReference }) { Text("Bind the reference (${RasterTagging.format(ref.length)}) to a dimension") }
                if (bindingReference) TaggingRow(rasterItem("Reference ${RasterTagging.format(ref.length)}"), null, keys) { _, choice -> onBind(choice, true) }
            }
        }
        raster.tags.forEach { tag ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${tag.key}: ${RasterTagging.format(tag.length)}")
                TextButton(onClick = { onEdit { it.untagged(tag.key) } }) { Text("Remove ${tag.key}") }
            }
        }
        TextButton(onClick = { onEdit { it.recalibrating() } }) { Text("Redo scale") }
    }
}

private fun rasterItem(text: String) = TaggingItem("raster", text, Rational.ZERO, Rational.ZERO, emptyList())

internal fun rasterErrorText(e: RasterInputError): String = when (e) {
    RasterInputError.NotALength -> "Type one length such as 5 3/8\" or 12'-6\""
    RasterInputError.PointsTooClose -> "Place two points further apart"
    RasterInputError.NotCalibrated -> "Set the scale first"
    is RasterInputError.NotALengthKey -> "${e.key} is not a length dimension"
}
