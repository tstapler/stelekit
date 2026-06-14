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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import dev.stapler.stelekit.ui.PlatformBackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
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
import dev.stapler.stelekit.util.roundTo
import kotlin.math.round

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
    /** Optional settings store for persisting first-calibration-use flag across sessions. */
    platformSettings: dev.stapler.stelekit.platform.Settings? = null,
    /** Optional active device for Story 5.7 — BLE status chip and "Set from laser" button. */
    activeDevice: ExternalMeasurementDevice? = null,
    /**
     * Story 7.4: Optional callback invoked when the user taps "Export to Drive".
     * When null, the Export to Drive button is not shown.
     */
    onExportToDrive: (() -> Unit)? = null,
    /** Story 7.4: Drive export state for progress/success/error feedback. */
    driveExportState: DriveExportUiState = DriveExportUiState.Idle,
    /**
     * Optional callback invoked when the user taps "Download depth model".
     *
     * When null, the depth estimation panel is not shown. On Android, pass a lambda that
     * calls [DepthModelDownloader.downloadModel] and pushes state into the ViewModel via
     * [AnnotationEditorViewModel.updateDepthModelUiState].
     */
    onDownloadDepthModel: (() -> Unit)? = null,
    /**
     * When non-null, the "Estimate depth (AI)" button is shown and tapping it calls this
     * lambda. The caller (Android activity/fragment) should trigger inference and update the
     * ViewModel's [AnnotationEditorState.depthMap] via [AnnotationEditorViewModel.runDepthEstimation].
     */
    onEstimateDepth: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var showCalibrationSheet by remember { mutableStateOf(false) }
    var showCalibrationChangeWarning by remember { mutableStateOf(false) }
    var isFirstCalibrationUse by remember {
        mutableStateOf(platformSettings?.getBoolean("image_meter_calibrated_before", false)?.not() ?: true)
    }
    val canUndoCalibration by viewModel.canUndoCalibration.collectAsState()
    val calibrationMessage by viewModel.calibrationChangeMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // UnsavedChanges tracking
    var showUnsavedChangesDialog by remember { mutableStateOf(false) }
    var hasUnsavedChanges by remember { mutableStateOf(false) }

    // BleDevicePanel
    var showBleDevicePanel by remember { mutableStateOf(false) }

    // Delete annotation confirmation
    var pendingDeleteUuid by remember { mutableStateOf<String?>(null) }

    // Coach marks
    var showDistanceCoachMark by remember { mutableStateOf(false) }
    var showAreaCoachMark by remember { mutableStateOf(false) }
    var coachMarksShown by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    // Adaptive labels: show for first 3 sessions (tracked in state per session)
    val showToolLabels by remember { mutableStateOf(true) }

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

    LaunchedEffect(calibrationMessage) {
        val msg = calibrationMessage ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = msg,
            actionLabel = if (canUndoCalibration) "Undo" else null,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoCalibration()
        }
        viewModel.clearCalibrationMessage()
    }

    LaunchedEffect(state.committedAnnotations.size) {
        if (state.committedAnnotations.isNotEmpty()) hasUnsavedChanges = true
        if (state.committedAnnotations.size == 1 &&
            state.committedAnnotations.first().annotationType == dev.stapler.stelekit.model.AnnotationType.DISTANCE &&
            coachMarksShown == 1) {
            showAreaCoachMark = true
        }
    }

    LaunchedEffect(viewModel.isCalibrated()) {
        if (viewModel.isCalibrated() && state.committedAnnotations.isEmpty() && coachMarksShown == 0) {
            showDistanceCoachMark = true
        }
    }

    // Show UnsavedChangesDialog when user tries to back-navigate with unsaved changes
    PlatformBackHandler(enabled = hasUnsavedChanges) {
        showUnsavedChangesDialog = true
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
        // Calibration status indicator bar
        CalibrationConfidenceBadge(
            calibration = state.calibration,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
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

        // "No calibration" nudge banner
        val isCalibrated = viewModel.isCalibrated()
        if (!isCalibrated) {
            CalibrationNudgeBanner(
                isFirstUse = isFirstCalibrationUse,
                onCalibrateClick = {
                    if (state.committedAnnotations.isNotEmpty()) {
                        showCalibrationChangeWarning = true
                    } else {
                        showCalibrationSheet = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // Once calibrated, mark first-use as done and persist across sessions
            LaunchedEffect(Unit) {
                isFirstCalibrationUse = false
                platformSettings?.putBoolean("image_meter_calibrated_before", true)
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

            // Accessibility layer: invisible semantic nodes over each annotation for TalkBack
            AnnotationSemanticOverlay(
                annotations = state.committedAnnotations,
                canvasSize = canvasSize,
                onAnnotationSelect = { uuid -> viewModel.selectAnnotation(uuid) },
                modifier = Modifier.fillMaxSize(),
            )

            // Coach mark: after first calibration, show DISTANCE coach mark
            if (showDistanceCoachMark && coachMarksShown < 2) {
                CoachMarkOverlay(
                    message = "Tap Distance to draw a measurement line on the photo.",
                    isVisible = true,
                    onDismiss = {
                        showDistanceCoachMark = false
                        coachMarksShown++
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // After DISTANCE is used once, show AREA coach mark
            if (showAreaCoachMark && coachMarksShown < 2) {
                CoachMarkOverlay(
                    message = "Tap Area to measure the area of a surface.",
                    isVisible = true,
                    onDismiss = {
                        showAreaCoachMark = false
                        coachMarksShown++
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }


            // Layer 5: Annotation toolbar (bottom)
            AnnotationToolbar(
                currentTool = state.currentTool,
                canUndo = viewModel.canUndo.collectAsState().value,
                canRedo = viewModel.canRedo.collectAsState().value,
                displayUnit = state.imageAnnotation?.unit,
                onToolSelect = { viewModel.selectTool(it) },
                onUndo = { viewModel.undo() },
                onRedo = { viewModel.redo() },
                onDeleteSelect = {
                    val selectedUuid = state.selectedAnnotationUuid
                    if (selectedUuid != null) {
                        pendingDeleteUuid = selectedUuid
                    }
                },
                hasSelection = state.selectedAnnotationUuid != null,
                isCalibrated = viewModel.isCalibrated(),
                onCalibrate = {
                    if (state.committedAnnotations.isNotEmpty()) {
                        showCalibrationChangeWarning = true
                    } else {
                        showCalibrationSheet = true
                    }
                },
                // TODO: add viewModel.setDisplayUnit(unit) when the ViewModel supports unit changes.
                // Until then this is intentionally a no-op — the unit dropdown is visible but
                // selecting a unit has no effect. The unit shown reflects imageAnnotation.unit only.
                onUnitSelect = {},
                showLabels = showToolLabels,
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

            // Depth estimation panel — shown when platform provides download/estimate callbacks.
            if (onDownloadDepthModel != null || onEstimateDepth != null) {
                DepthEstimationPanel(
                    modelState = state.depthModelUiState,
                    isInferenceRunning = state.isDepthInferenceRunning,
                    depthEstimationError = state.depthEstimationError,
                    onDownload = onDownloadDepthModel ?: {},
                    onEstimate = onEstimateDepth ?: {},
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 8.dp, top = 8.dp),
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
        } // end Box
        } // end Column

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

        if (showCalibrationSheet) {
            CalibrationSheet(
                onDismiss = { showCalibrationSheet = false },
                onDrawReference = {
                    showCalibrationSheet = false
                    viewModel.selectTool(AnnotationTool.GRID_REF)
                },
                onUseBle = {
                    showCalibrationSheet = false
                    showBleDevicePanel = true
                },
            )
        }

        if (showCalibrationChangeWarning) {
            CalibrationChangeWarningDialog(
                existingAnnotationCount = state.committedAnnotations.size,
                onKeepCurrentScale = { showCalibrationChangeWarning = false },
                onChangeScale = {
                    showCalibrationChangeWarning = false
                    showCalibrationSheet = true
                },
            )
        }

        // BleDevicePanel
        if (showBleDevicePanel) {
            BleDevicePanel(
                onDismiss = { showBleDevicePanel = false },
                onUseDrawMethod = {
                    showBleDevicePanel = false
                    viewModel.selectTool(AnnotationTool.GRID_REF)
                },
                onReadingAccept = { valueMeters ->
                    showBleDevicePanel = false
                    // TODO: BleDevicePanel manages its own device connection internally and does not
                    // call viewModel.setActiveDevice(device) before accepting a reading. As a result,
                    // viewModel.injectMeasurementFromDevice() cannot be called here — it would find
                    // no active device and be a no-op.
                    // Full fix requires BleDevicePanel to expose the connected ExternalMeasurementDevice
                    // in its onReadingAccept callback (change signature to (ExternalMeasurementDevice, Double))
                    // so the caller can do: viewModel.setActiveDevice(device); viewModel.injectMeasurementFromDevice()
                    // For now the reading (valueMeters=$valueMeters) is discarded — a known limitation.
                },
            )
        }

        // UnsavedChangesDialog
        if (showUnsavedChangesDialog) {
            UnsavedChangesDialog(
                onSave = {
                    showUnsavedChangesDialog = false
                    hasUnsavedChanges = false
                    // TODO: trigger explicit save — for now just navigate back
                    onNavigateBack()
                },
                onDiscard = {
                    showUnsavedChangesDialog = false
                    hasUnsavedChanges = false
                    onNavigateBack()
                },
                onKeepEditing = {
                    showUnsavedChangesDialog = false
                },
            )
        }

        // Delete annotation confirmation
        val deleteUuid = pendingDeleteUuid
        if (deleteUuid != null) {
            val annotation = state.committedAnnotations.find { it.uuid == deleteUuid }
            DeleteAnnotationConfirmationDialog(
                annotationLabel = annotation?.label,
                onCancel = { pendingDeleteUuid = null },
                onDelete = {
                    pendingDeleteUuid = null
                    viewModel.deleteAnnotation(deleteUuid)
                },
            )
        }
    } // end Scaffold lambda
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
    val altStr = if (altitudeM != null) "  ↑ ${formatDecimals(altitudeM, 1)} m" else ""

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
    return "${formatDecimals(absDeg, 4)}° $direction"
}

/**
 * KMP-compatible decimal formatting. Rounds [value] to [decimals] decimal places and
 * returns a plain string with exactly [decimals] digits after the point.
 * Avoids [String.format] which is JVM-only.
 */
private fun formatDecimals(value: Double, decimals: Int): String {
    val s = value.roundTo(decimals).toString()
    val dotIdx = s.indexOf('.')
    return if (dotIdx < 0) {
        if (decimals == 0) s else s + "." + "0".repeat(decimals)
    } else {
        val currentDecimals = s.length - dotIdx - 1
        when {
            currentDecimals < decimals -> s + "0".repeat(decimals - currentDecimals)
            currentDecimals > decimals -> s.substring(0, dotIdx + decimals + 1)
            else -> s
        }
    }
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
                            // FEET_INCHES is excluded: the dialog only accepts decimal input,
                            // not the compound "N' M\"" format that FEET_INCHES implies.
                            MeasurementUnit.entries.filter { it != MeasurementUnit.FEET_INCHES }.forEach { unit ->
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

// ── Depth estimation panel ────────────────────────────────────────────────────

/**
 * Compact floating panel that drives the monocular depth model download and inference flow.
 *
 * State transitions (per ADR-005 and Story 9.8 spec):
 * - [DepthModelUiState.Absent]      → "Download depth model (~100MB)" button
 * - [DepthModelUiState.Downloading] → progress indicator with percentage
 * - [DepthModelUiState.Ready]       → "Estimate depth (AI)" button (or spinner during inference)
 * - [DepthModelUiState.Failed]      → "Download failed — tap to retry" button
 *
 * After a successful estimation the low-confidence warning badge is shown inline
 * (ADR-005: ML depth confidence ±15%).
 */
@Composable
internal fun DepthEstimationPanel(
    modelState: DepthModelUiState,
    isInferenceRunning: Boolean,
    depthEstimationError: String?,
    onDownload: () -> Unit,
    onEstimate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color(0xDD1A1A1A),
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                // Inference running — show spinner.
                isInferenceRunning -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Estimating depth…",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                }

                // Model ready — show estimate button.
                modelState is DepthModelUiState.Ready -> {
                    OutlinedButton(onClick = onEstimate) {
                        Text(
                            text = "Estimate depth (AI)",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                    // ADR-005 low-confidence warning.
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFFFA000),
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Low confidence — verify with reference object",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFFA000),
                        )
                    }
                }

                // Download in progress — show indeterminate progress.
                modelState is DepthModelUiState.Downloading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        val pct = modelState.progress
                        Text(
                            text = if (pct >= 0) "Downloading model… $pct%" else "Downloading model…",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                }

                // Download failed — show retry button.
                modelState is DepthModelUiState.Failed -> {
                    TextButton(onClick = onDownload) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFEF5350),
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Download failed — tap to retry",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFEF5350),
                        )
                    }
                }

                // Model absent — show download prompt.
                else -> {
                    OutlinedButton(onClick = onDownload) {
                        Text(
                            text = "Download depth model (~100MB)",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                }
            }

            // Show inference error if present (below the main action button).
            if (depthEstimationError != null && !isInferenceRunning) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = depthEstimationError,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFEF5350),
                )
            }
        }
    }
}
