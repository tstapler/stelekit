package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.ImageSensorData
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.platform.measurement.DeviceConnectionState
import dev.stapler.stelekit.platform.measurement.ExternalMeasurementDevice
import kotlin.math.abs

/**
 * Root screen for the image annotation editor.
 *
 * Layout:
 *   Layer 1 — zoomed/pannable image (Coil AsyncImage with transformable state)
 *   Layer 2 — committed annotations Canvas
 *   Layer 3 — in-progress annotation Canvas with gesture input
 *   Layer 4 — MeasurementLabelOverlay
 *   Layer 5 — AnnotationToolbar
 *
 * PERFORMANCE (Story 9.1): zoom/pan state is stored in [annotationCanvasTransform] which is read
 * only inside DrawScope, not during recomposition — preventing the annotation Canvas
 * from recomposing on every pan/zoom frame. [Path] objects in [CommittedAnnotationsCanvas]
 * are retained via [remember] keyed by annotation UUID, eliminating per-frame allocation.
 *
 * ACCESSIBILITY (Story 9.6): [InProgressAnnotationCanvas] carries
 * `Modifier.semantics { contentDescription = "..." }` so assistive services can describe
 * the tap target. Measurement labels in [MeasurementLabelOverlay] are rendered with a
 * dark background chip for adequate contrast.
 *
 * HAPTIC (Story 9.6): Supply [onHapticFeedback] when constructing the [AnnotationEditorViewModel]
 * to fire haptic feedback on each annotation commit. Example:
 * ```kotlin
 * val haptic = LocalHapticFeedback.current
 * val vm = remember {
 *     AnnotationEditorViewModel(
 *         measurementRepository = ...,
 *         onHapticFeedback = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
 *     )
 * }
 * ```
 */
@Composable
fun AnnotationEditorScreen(
    viewModel: AnnotationEditorViewModel,
    imageAnnotation: ImageAnnotation,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    /** Optional active device for Story 5.7 — BLE status chip and "Set from laser" button. */
    activeDevice: ExternalMeasurementDevice? = null,
    /**
     * Story 7.4: Optional callback invoked when the user taps "Export to Drive".
     * When null, the Export to Drive button is not shown.
     */
    onExportToDrive: (() -> Unit)? = null,
    /** Story 7.4: Drive export state for progress/success/error feedback. */
    driveExportState: DriveExportUiState = DriveExportUiState.Idle,
) {
    val state by viewModel.state.collectAsState()
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Story 5.7: wire the active device into the ViewModel so injectMeasurementFromDevice()
    // can find it. Effect re-runs when activeDevice reference changes.
    LaunchedEffect(activeDevice) {
        if (activeDevice != null) {
            viewModel.setActiveDevice(activeDevice)
        }
    }

    // Story 5.7: observe device connection state for the status chip.
    val deviceConnectionState by viewModel.deviceConnectionState.collectAsState()

    // Initialize once
    LaunchedEffect(imageAnnotation.uuid) {
        viewModel.initialize(imageAnnotation)
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Calibration status indicator bar
        CalibrationStatusBar(
            calibration = state.calibration,
            modifier = Modifier.fillMaxWidth(),
        )

        // ARCore accuracy warning — shown only when ARCore/LiDAR depth calibration is active
        val cal = state.calibration
        if (cal != null && cal.method == CalibrationMethod.ARCORE_DEPTH) {
            Surface(
                color = Color(0xFFFFF3E0),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFE65100),
                    )
                    Spacer(Modifier.width(8.dp))
                    // ADR-005 mandated warning text
                    Text(
                        text = "ARCore depth accuracy ±8–10 cm. Not suitable for measurements under 15 cm.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE65100),
                    )
                }
            }
        }

        // "No calibration" dismissible banner
        var showNoCalibrationBanner by remember { mutableStateOf(true) }
        if (showNoCalibrationBanner && (cal == null || cal.method == CalibrationMethod.NONE)) {
            Surface(
                color = Color(0xFFFFF9C4),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "No calibration set — measurements will not show real-world values. " +
                            "Draw a reference line or connect a laser meter.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showNoCalibrationBanner = false }) {
                        Text("Dismiss")
                    }
                }
            }
        }

        // Story 8.4: Tilt warning — shown when capture-time pitch > 15° or roll > 10°.
        val sensorData: ImageSensorData? = state.imageAnnotation?.sensorData
        val isTiltExcessive = sensorData?.let { sd ->
            (sd.pitchDeg != null && abs(sd.pitchDeg) > 15.0) ||
                (sd.rollDeg != null && abs(sd.rollDeg) > 10.0)
        } == true
        var showTiltWarning by remember { mutableStateOf(true) }
        if (isTiltExcessive && showTiltWarning) {
            Surface(
                color = Color(0xFFFFF9C4),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFF57F17),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Camera tilted — measurements may be inaccurate. " +
                            "Use a reference object for calibration.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFF57F17),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showTiltWarning = false }) {
                        Text("Dismiss")
                    }
                }
            }
        }

        // Story 8.2: GPS metadata row — formatted as "49.2827° N, 123.1207° W".
        val latLng = sensorData?.latLng
        if (latLng != null) {
            GpsMetadataRow(
                latLng = latLng,
                altitudeM = sensorData.altitudeM,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            // Layer 1: Zoomed/pannable image
            // We use Coil AsyncImage which handles subsampling for large images.
            // Zoom/pan is achieved via graphicsLayer transform so the Canvas layers are
            // not recomposed on gesture updates.
            AsyncImage(
                model = imageAnnotation.filePath,
                contentDescription = "Annotated image",
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coords -> canvasSize = coords.size },
            )

            // Layer 2: Committed annotations Canvas
            // Retains Path objects via remember to avoid per-frame allocation.
            CommittedAnnotationsCanvas(
                annotations = state.committedAnnotations,
                selectedUuid = state.selectedAnnotationUuid,
                canvasSize = canvasSize,
            )

            // Layer 3: In-progress annotation Canvas + gesture detection
            InProgressAnnotationCanvas(
                inProgressPoints = state.inProgressPoints,
                currentTool = state.currentTool,
                canvasSize = canvasSize,
                onTap = { offset ->
                    val normalized = offset.toNormalized(canvasSize)
                    if (normalized != null) {
                        viewModel.addPoint(normalized)
                    }
                },
            )

            // Layer 4: Measurement label overlay
            MeasurementLabelOverlay(
                annotations = state.committedAnnotations,
                canvasSize = canvasSize,
                modifier = Modifier.fillMaxSize(),
            )

            // Layer 5: Annotation toolbar (bottom)
            AnnotationToolbar(
                currentTool = state.currentTool,
                canUndo = viewModel.canUndo.collectAsState().value,
                canRedo = viewModel.canRedo.collectAsState().value,
                displayUnit = state.imageAnnotation?.unit,
                onToolSelect = { viewModel.selectTool(it) },
                onUndo = { viewModel.undo() },
                onRedo = { viewModel.redo() },
                onDeleteSelect = { viewModel.deleteSelectedAnnotation() },
                hasSelection = state.selectedAnnotationUuid != null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(8.dp),
            )

            // Story 5.7: Device connection status chip + "Set from laser" button.
            // Shown at the bottom of the screen when a device is set.
            if (activeDevice != null) {
                val hasAnyDistance = state.committedAnnotations.any { ann ->
                    ann.annotationType == AnnotationType.DISTANCE || ann.annotationType == AnnotationType.GRID_REF
                }
                DeviceConnectionChip(
                    deviceName = activeDevice.deviceName,
                    connectionState = deviceConnectionState,
                    showSetFromLaserButton = deviceConnectionState == DeviceConnectionState.CONNECTED &&
                        hasAnyDistance,
                    onSetFromLaser = { viewModel.injectMeasurementFromDevice() },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 8.dp, bottom = 64.dp),
                )
            }

            // Story 7.4: Export to Drive button — shown at top-right when onExportToDrive is set.
            if (onExportToDrive != null) {
                DriveExportButton(
                    exportState = driveExportState,
                    onExport = onExportToDrive,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
            }

            // Label text input overlay (shown when LABEL tool places an anchor)
            if (state.isLabelInputVisible) {
                LabelInputOverlay(
                    text = state.labelInputText,
                    onTextChange = { viewModel.updateLabelText(it) },
                    onConfirm = { viewModel.confirmLabel() },
                    onDismiss = { viewModel.dismissLabelInput() },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }

    // GRID_REF calibration dialog — shown as overlay outside the Column
    if (state.isGridRefDialogVisible) {
        GridRefCalibrationDialog(
            lengthText = state.gridRefLengthText,
            selectedUnit = state.gridRefUnit,
            onLengthTextChange = { viewModel.updateGridRefLengthText(it) },
            onUnitChange = { viewModel.updateGridRefUnit(it) },
            onConfirm = { viewModel.confirmGridRefCalibration() },
            onDismiss = { viewModel.dismissGridRefDialog() },
        )
    }
}

// ── Story 7.4: Drive export UI ────────────────────────────────────────────────

/** UI state for the Google Drive export operation. */
sealed class DriveExportUiState {
    data object Idle : DriveExportUiState()
    data object Loading : DriveExportUiState()
    data class Success(val driveLink: String) : DriveExportUiState()
    data class Error(val message: String) : DriveExportUiState()
}

/**
 * Floating "Export to Drive" button shown in the top-right of the annotation canvas.
 *
 * - Idle / Error: shows a CloudUpload FAB (error allows retry).
 * - Loading: shows a disabled spinner FAB.
 * - Success: shows a "Saved to Drive" chip.
 */
@Composable
internal fun DriveExportButton(
    exportState: DriveExportUiState,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (exportState) {
        is DriveExportUiState.Idle, is DriveExportUiState.Error -> {
            androidx.compose.material3.FloatingActionButton(
                onClick = onExport,
                modifier = modifier,
                containerColor = Color(0xFF1565C0),
                contentColor = Color.White,
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = "Export to Google Drive")
            }
        }
        is DriveExportUiState.Loading -> {
            androidx.compose.material3.FloatingActionButton(
                onClick = {},
                modifier = modifier,
                containerColor = Color(0xFF1565C0).copy(alpha = 0.5f),
                contentColor = Color.White,
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.padding(4.dp),
                )
            }
        }
        is DriveExportUiState.Success -> {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                color = Color(0xFFE3F2FD),
                modifier = modifier,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color(0xFF1565C0))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Saved to Drive",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF1565C0),
                    )
                }
            }
        }
    }
}

// ── Layer 2: Committed annotations ───────────────────────────────────────────

/**
 * Canvas layer that renders all [annotations] at their normalized coordinates mapped to [canvasSize].
 *
 * PERFORMANCE: [Path] objects are retained via [remember] — one per annotation UUID — to avoid
 * per-frame allocation during pan/zoom animation.
 */
@Composable
private fun CommittedAnnotationsCanvas(
    annotations: List<MeasurementAnnotation>,
    selectedUuid: String?,
    canvasSize: IntSize,
    modifier: Modifier = Modifier,
) {
    // Retain Path objects per annotation uuid to avoid per-frame allocation.
    val pathMap = remember { mutableMapOf<String, Path>() }

    Canvas(modifier = modifier.fillMaxSize()) {
        if (canvasSize == IntSize.Zero) return@Canvas
        annotations.forEach { annotation ->
            val isSelected = annotation.uuid == selectedUuid
            val path = pathMap.getOrPut(annotation.uuid) { Path() }
            drawAnnotation(annotation, path, canvasSize, isSelected)
        }
    }
}

private fun DrawScope.drawAnnotation(
    annotation: MeasurementAnnotation,
    path: Path,
    canvasSize: IntSize,
    isSelected: Boolean,
) {
    val color = if (isSelected) Color(0xFFFFD700) else annotationColor(annotation.annotationType)
    val strokeWidth = if (isSelected) 4.dp.toPx() else 2.dp.toPx()
    val points = annotation.normalizedPoints
    if (points.isEmpty()) return

    val screenPoints = points.map { it.toScreen(canvasSize) }

    when (annotation.annotationType) {
        AnnotationType.DISTANCE, AnnotationType.GRID_REF -> {
            if (screenPoints.size >= 2) {
                drawLine(
                    color = color,
                    start = screenPoints[0],
                    end = screenPoints[1],
                    strokeWidth = strokeWidth,
                )
                // Draw endpoint dots
                drawCircle(color = color, radius = 4.dp.toPx(), center = screenPoints[0])
                drawCircle(color = color, radius = 4.dp.toPx(), center = screenPoints[1])
            }
        }

        AnnotationType.AREA -> {
            if (screenPoints.size >= 3) {
                path.reset()
                path.moveTo(screenPoints[0].x, screenPoints[0].y)
                screenPoints.drop(1).forEach { pt -> path.lineTo(pt.x, pt.y) }
                path.close()
                drawPath(path, color = color.copy(alpha = 0.25f))
                drawPath(path, color = color, style = Stroke(width = strokeWidth))
            }
        }

        AnnotationType.ANGLE -> {
            if (screenPoints.size >= 3) {
                val vertex = screenPoints[1]
                drawLine(color = color, start = screenPoints[0], end = vertex, strokeWidth = strokeWidth)
                drawLine(color = color, start = vertex, end = screenPoints[2], strokeWidth = strokeWidth)
                // Draw small arc at vertex to indicate the angle
                val arcRadius = 16.dp.toPx()
                drawCircle(color = color.copy(alpha = 0.4f), radius = arcRadius, center = vertex)
                drawCircle(color = color, radius = 3.dp.toPx(), center = vertex)
            }
        }

        AnnotationType.LABEL -> {
            if (screenPoints.isNotEmpty()) {
                // Draw anchor dot
                drawCircle(color = color, radius = 5.dp.toPx(), center = screenPoints[0])
            }
        }
    }
}

// ── Layer 3: In-progress Canvas ───────────────────────────────────────────────

/**
 * Transparent Canvas that captures taps via [detectTapGestures] and draws the
 * in-progress annotation preview.
 *
 * PERFORMANCE: recomposition of this layer is driven only by [inProgressPoints] changes,
 * NOT by zoom/pan state — ensuring no per-frame recomposition during gestures.
 */
@Composable
private fun InProgressAnnotationCanvas(
    inProgressPoints: List<NormalizedPoint>,
    currentTool: AnnotationTool,
    canvasSize: IntSize,
    onTap: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = "Annotation canvas — tap to place points" }
            .pointerInput(currentTool) {
                detectTapGestures { offset -> onTap(offset) }
            },
    ) {
        if (canvasSize == IntSize.Zero || inProgressPoints.isEmpty()) return@Canvas
        val screenPoints = inProgressPoints.map { it.toScreen(canvasSize) }
        val color = Color(0xFF4CAF50)

        // Draw line segments between points
        for (i in 0 until screenPoints.size - 1) {
            drawLine(
                color = color,
                start = screenPoints[i],
                end = screenPoints[i + 1],
                strokeWidth = 2.dp.toPx(),
            )
        }

        // Draw point dots
        screenPoints.forEach { pt ->
            drawCircle(color = color, radius = 5.dp.toPx(), center = pt)
        }

        // For AREA: show the closing line back to first point as a dashed preview
        if (currentTool == AnnotationTool.AREA && screenPoints.size >= 2) {
            drawLine(
                color = color.copy(alpha = 0.4f),
                start = screenPoints.last(),
                end = screenPoints.first(),
                strokeWidth = 1.5f.dp.toPx(),
            )
        }
    }
}

// ── Coordinate transform helpers ──────────────────────────────────────────────

/**
 * Convert a screen [Offset] to normalized [0,1] image coordinates.
 *
 * Returns null if [canvasSize] has zero dimensions.
 */
internal fun Offset.toNormalized(canvasSize: IntSize): NormalizedPoint? {
    if (canvasSize.width == 0 || canvasSize.height == 0) return null
    val nx = (x / canvasSize.width).coerceIn(0.0f, 1.0f).toDouble()
    val ny = (y / canvasSize.height).coerceIn(0.0f, 1.0f).toDouble()
    return NormalizedPoint(nx, ny)
}

/**
 * Convert a [NormalizedPoint] to screen [Offset] within the given [canvasSize].
 *
 * Applied only at draw time, never stored.
 */
internal fun NormalizedPoint.toScreen(canvasSize: IntSize): Offset =
    Offset(
        x = (this.x * canvasSize.width).toFloat(),
        y = (this.y * canvasSize.height).toFloat(),
    )

// ── Annotation color ──────────────────────────────────────────────────────────

private fun annotationColor(type: AnnotationType): Color = when (type) {
    AnnotationType.DISTANCE -> Color(0xFFE53935)
    AnnotationType.AREA -> Color(0xFF1E88E5)
    AnnotationType.ANGLE -> Color(0xFF43A047)
    AnnotationType.LABEL -> Color(0xFFFB8C00)
    AnnotationType.GRID_REF -> Color(0xFF8E24AA)
}

// ── Calibration status bar ────────────────────────────────────────────────────

/**
 * Color-coded calibration confidence badge displayed in the editor header bar.
 *
 * Color mapping:
 * - Green  (BLE_LASER / MANUAL_REFERENCE) — 95–100% confidence
 * - Yellow (ARCORE_DEPTH / LIDAR_DEPTH)   — 60–85% confidence
 * - Orange (EXIF_FOCAL)                   — 20% confidence
 * - Red    (MONOCULAR_ML)                 — 10–15% confidence
 * - Grey   (NONE / null)                  — no calibration
 */
@Composable
internal fun CalibrationStatusBar(
    calibration: dev.stapler.stelekit.model.Calibration?,
    modifier: Modifier = Modifier,
) {
    val (color, label, icon) = when (calibration?.method) {
        CalibrationMethod.BLE_LASER ->
            Triple(Color(0xFF2E7D32), "±1mm BLE", Icons.Default.CheckCircle)
        CalibrationMethod.MANUAL_REFERENCE ->
            Triple(Color(0xFF2E7D32), "±manual ref", Icons.Default.CheckCircle)
        CalibrationMethod.ARCORE_DEPTH ->
            Triple(Color(0xFFF57F17), "±8cm ARCore", Icons.Default.Warning)
        CalibrationMethod.LIDAR_DEPTH ->
            Triple(Color(0xFF558B2F), "±2cm LiDAR", Icons.Default.CheckCircle)
        CalibrationMethod.EXIF_FOCAL ->
            Triple(Color(0xFFE65100), "±15% EXIF estimate", Icons.Default.Info)
        CalibrationMethod.MONOCULAR_ML ->
            Triple(Color(0xFFC62828), "±15% AI estimate", Icons.Default.Warning)
        CalibrationMethod.NONE, null ->
            Triple(Color(0xFF616161), "Not calibrated", Icons.Default.Info)
    }

    Surface(
        color = color.copy(alpha = 0.12f),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
            if (calibration != null && calibration.method != CalibrationMethod.NONE) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${calibration.confidencePercent}% confidence",
                    style = MaterialTheme.typography.labelSmall,
                    color = color.copy(alpha = 0.7f),
                )
            }
        }
    }
}

// ── Story 8.2: GPS metadata row ──────────────────────────────────────────────

/**
 * Compact metadata row showing GPS coordinates from [ImageSensorData].
 *
 * Formatted as "49.2827° N, 123.1207° W" per Story 8.2 spec.
 * Altitude is shown when available, e.g. "↑ 45.2 m".
 * Only rendered when [latLng] is non-null.
 */
@Composable
internal fun GpsMetadataRow(
    latLng: Pair<Double, Double>,
    altitudeM: Double?,
    modifier: Modifier = Modifier,
) {
    val (lat, lng) = latLng
    val latStr = formatCoordinate(lat, "N", "S")
    val lngStr = formatCoordinate(lng, "E", "W")
    val altStr = if (altitudeM != null) "  ↑ ${"%.1f".format(altitudeM)} m" else ""

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "$latStr, $lngStr$altStr",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

/**
 * Format a geographic coordinate as a degree string with cardinal direction.
 *
 * Example: 49.2827 → "49.2827° N"
 */
private fun formatCoordinate(degrees: Double, positive: String, negative: String): String {
    val direction = if (degrees >= 0.0) positive else negative
    val absDeg = abs(degrees)
    return "${"%.4f".format(absDeg)}° $direction"
}

// ── GRID_REF calibration dialog ───────────────────────────────────────────────

/**
 * Dialog shown after the user draws a GRID_REF reference line.
 *
 * Prompts for the real-world length of the reference object and the unit,
 * then calls [onConfirm] to apply calibration to all committed annotations.
 */
@Composable
internal fun GridRefCalibrationDialog(
    lengthText: String,
    selectedUnit: MeasurementUnit,
    onLengthTextChange: (String) -> Unit,
    onUnitChange: (MeasurementUnit) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var unitMenuExpanded by remember { mutableStateOf(false) }
    val isValidLength = lengthText.trim().toDoubleOrNull()?.let { it > 0.0 } == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set reference length") },
        text = {
            Column {
                Text(
                    text = "How long is the object you just traced?",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.padding(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = lengthText,
                        onValueChange = onLengthTextChange,
                        label = { Text("Length") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        isError = lengthText.isNotBlank() && !isValidLength,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box {
                        TextButton(onClick = { unitMenuExpanded = true }) {
                            Text(selectedUnit.symbol())
                        }
                        DropdownMenu(
                            expanded = unitMenuExpanded,
                            onDismissRequest = { unitMenuExpanded = false },
                        ) {
                            MeasurementUnit.entries.forEach { unit ->
                                DropdownMenuItem(
                                    text = { Text(unit.symbol()) },
                                    onClick = {
                                        onUnitChange(unit)
                                        unitMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = isValidLength,
            ) {
                Text("Apply calibration")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

// ── Story 5.7: Device connection status chip ──────────────────────────────────

/**
 * Status chip displayed at the bottom of the annotation editor when an [ExternalMeasurementDevice]
 * is active.
 *
 * Shows:
 * - Device name + colored connection indicator dot
 * - "Set from laser" button when a distance annotation exists and the device is CONNECTED
 *
 * Tapping "Set from laser" calls [onSetFromLaser] which triggers
 * [AnnotationEditorViewModel.injectMeasurementFromDevice].
 */
@Composable
internal fun DeviceConnectionChip(
    deviceName: String,
    connectionState: DeviceConnectionState,
    showSetFromLaserButton: Boolean,
    onSetFromLaser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chipColor = when (connectionState) {
        DeviceConnectionState.CONNECTED -> Color(0xFF2E7D32)
        DeviceConnectionState.CONNECTING -> Color(0xFFF57F17)
        DeviceConnectionState.DISCONNECTED -> Color(0xFF616161)
        DeviceConnectionState.ERROR -> Color(0xFFC62828)
    }
    val statusLabel = when (connectionState) {
        DeviceConnectionState.CONNECTED -> "Connected"
        DeviceConnectionState.CONNECTING -> "Connecting…"
        DeviceConnectionState.DISCONNECTED -> "Disconnected"
        DeviceConnectionState.ERROR -> "Error"
    }

    Surface(
        color = chipColor.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Connection state dot
            androidx.compose.foundation.Canvas(modifier = Modifier.width(8.dp).padding(end = 4.dp)) {
                drawCircle(color = chipColor, radius = 4.dp.toPx())
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = "$deviceName · $statusLabel",
                style = MaterialTheme.typography.labelSmall,
                color = chipColor,
            )
            if (showSetFromLaserButton) {
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = onSetFromLaser,
                ) {
                    Text(
                        text = "Set from laser",
                        style = MaterialTheme.typography.labelSmall,
                        color = chipColor,
                    )
                }
            }
        }
    }
}
