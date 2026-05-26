package dev.stapler.stelekit.ui.annotate

import arrow.core.Either
import androidx.compose.ui.graphics.ImageBitmap
import dev.stapler.stelekit.calibration.CalibrationService
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.model.angleBetweenThreePoints
import dev.stapler.stelekit.model.metersToDisplayString
import dev.stapler.stelekit.model.pixelDistanceToMeters
import dev.stapler.stelekit.model.polygonAreaMeters
import dev.stapler.stelekit.platform.measurement.DeviceConnectionState
import dev.stapler.stelekit.platform.measurement.ExternalMeasurementDevice
import dev.stapler.stelekit.platform.ml.MonocularDepthEstimator
import dev.stapler.stelekit.platform.sensor.CameraProvider
import dev.stapler.stelekit.platform.sensor.DepthSensorProvider
import dev.stapler.stelekit.platform.sensor.MotionSensorProvider
import dev.stapler.stelekit.platform.sensor.SensorModule
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.ImageAnnotationRepository
import dev.stapler.stelekit.repository.MeasurementAnnotationRepository
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * The active annotation tool in the editor.
 */
enum class AnnotationTool {
    SELECT,
    DISTANCE,
    AREA,
    ANGLE,
    LABEL,
    GRID_REF,
}

/**
 * Number of points required to complete each tool's annotation.
 */
fun AnnotationTool.requiredPointCount(): Int = when (this) {
    AnnotationTool.SELECT -> 1
    AnnotationTool.DISTANCE -> 2
    AnnotationTool.AREA -> 3 // minimum; user taps to close
    AnnotationTool.ANGLE -> 3
    AnnotationTool.LABEL -> 1
    AnnotationTool.GRID_REF -> 2
}

/**
 * Returns true if the tool accepts more than the minimum point count.
 * AREA is the only open-ended tool (polygon with N ≥ 3 vertices, closed on re-tap of first point).
 */
fun AnnotationTool.isOpenEnded(): Boolean = this == AnnotationTool.AREA

/**
 * Immutable state snapshot for [AnnotationEditorViewModel].
 *
 * All coordinates in [inProgressPoints] and [committedAnnotations] are stored in
 * normalized [0,1] image space — never screen pixels.
 */
data class AnnotationEditorState(
    val currentTool: AnnotationTool = AnnotationTool.DISTANCE,
    val inProgressPoints: List<NormalizedPoint> = emptyList(),
    val committedAnnotations: List<MeasurementAnnotation> = emptyList(),
    val calibration: Calibration? = null,
    val imageAnnotation: ImageAnnotation? = null,
    val selectedAnnotationUuid: String? = null,
    val labelInputText: String = "",
    val isLabelInputVisible: Boolean = false,
    val pendingLabelPoint: NormalizedPoint? = null,
    // ── GRID_REF calibration dialog state ─────────────────────────────────
    /** True when the GRID_REF reference-line dialog is awaiting user input. */
    val isGridRefDialogVisible: Boolean = false,
    /** The two normalized points of the completed GRID_REF line. */
    val pendingGridRefPoints: List<NormalizedPoint> = emptyList(),
    /** Text field content for the real-world length the user is typing. */
    val gridRefLengthText: String = "",
    /** Selected unit for the GRID_REF dialog (defaults to the image's display unit). */
    val gridRefUnit: MeasurementUnit = MeasurementUnit.METERS,
    // ── Monocular depth estimation state ──────────────────────────────────
    /** True while ONNX inference is running (shows spinner). */
    val isDepthInferenceRunning: Boolean = false,
    /** Non-null after successful depth estimation. Relative depth map, normalized [0,1]. */
    val depthMap: FloatArray? = null,
    /** Error message from the most recent depth estimation attempt. Null when none. */
    val depthEstimationError: String? = null,
    /** Current download/readiness state of the depth model file. */
    val depthModelUiState: DepthModelUiState = DepthModelUiState.Absent,
)

/**
 * Platform-independent mirror of [DepthModelDownloader.ModelState] for common UI.
 *
 * Populated via [AnnotationEditorViewModel.updateDepthModelUiState] from the Android
 * platform layer which observes [DepthModelDownloader.modelState].
 */
sealed interface DepthModelUiState {
    /** Model not downloaded. Show "Download depth model (~100MB)" button. */
    data object Absent : DepthModelUiState

    /** Download in progress. [progress] is 0–100 or -1 if indeterminate. */
    data class Downloading(val progress: Int) : DepthModelUiState

    /** Model ready — show "Estimate depth (AI)" button. */
    data object Ready : DepthModelUiState

    /** Download failed. Show "Download failed — tap to retry". */
    data object Failed : DepthModelUiState
}

/**
 * ViewModel for the image annotation editor screen.
 *
 * CRITICAL: owns its [CoroutineScope] internally — never accepts an externally supplied scope.
 * Compose cancels [rememberCoroutineScope] on composition exit; any object holding that scope
 * will throw [ForgottenCoroutineScopeException] on its next [launch].
 *
 * Call [close] when the editor is permanently dismissed (e.g. on back navigation after
 * the composable leaves composition permanently) to cancel in-flight DB writes cleanly.
 */
class AnnotationEditorViewModel(
    private val measurementRepository: MeasurementAnnotationRepository,
    /** Optional — required for tag persistence. When null, tag changes update state only. */
    private val imageAnnotationRepository: ImageAnnotationRepository? = null,
    // Image dimensions in native pixels — needed for area and angle computation.
    // Pass the actual decoded image size; defaults allow unit tests to omit them.
    private val imageWidthPx: Double = 1.0,
    private val imageHeightPx: Double = 1.0,
    /**
     * Story 9.6: Haptic feedback callback invoked on every successful annotation commit.
     *
     * The composable caller should pass a lambda that calls
     * [androidx.compose.ui.hapticfeedback.HapticFeedback.performHapticFeedback] with
     * [androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress].
     *
     * Example wiring in [AnnotationEditorScreen]:
     * ```kotlin
     * val haptic = LocalHapticFeedback.current
     * val viewModel = remember {
     *     AnnotationEditorViewModel(
     *         measurementRepository = repo,
     *         onHapticFeedback = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
     *     )
     * }
     * ```
     *
     * Defaults to no-op to preserve backward compatibility in tests and JVM desktop.
     */
    private val onHapticFeedback: () -> Unit = {},
    /**
     * Camera provider for image capture. Defaults to [SensorModule.cameraProvider].
     * Pass an explicit implementation in tests to avoid global mutable state.
     */
    private val cameraProvider: CameraProvider = SensorModule.cameraProvider,
    /**
     * Motion sensor provider for GPS/compass/IMU data. Defaults to [SensorModule.motionSensorProvider].
     * Pass an explicit implementation in tests to avoid global mutable state.
     */
    private val motionSensorProvider: MotionSensorProvider = SensorModule.motionSensorProvider,
    /**
     * Depth sensor provider for ARCore/LiDAR depth frames. Defaults to [SensorModule.depthSensorProvider].
     * Pass an explicit implementation in tests to avoid global mutable state.
     */
    private val depthSensorProvider: DepthSensorProvider = SensorModule.depthSensorProvider,
    /**
     * Monocular depth estimator for AI depth calibration. Defaults to [SensorModule.monocularDepthEstimator].
     * Pass an explicit implementation in tests to avoid global mutable state.
     */
    private val monocularDepthEstimator: MonocularDepthEstimator = SensorModule.monocularDepthEstimator,
) {
    // CRITICAL: internal scope — never injected from outside composition.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logger = Logger("AnnotationEditorViewModel")

    // ── Depth estimation coordinator ──────────────────────────────────────────
    private val depthCoordinator = DepthEstimationCoordinator(
        depthEstimator = monocularDepthEstimator,
        imageWidthPx = imageWidthPx,
        imageHeightPx = imageHeightPx,
        onCalibrationReady = { cal -> updateCalibration(cal) },
    )

    private val _state = MutableStateFlow(AnnotationEditorState())
    val state: StateFlow<AnnotationEditorState> = _state.asStateFlow()

    init {
        // Merge depth coordinator state changes into the main AnnotationEditorState so that
        // the screen continues to read a single unified state object.
        scope.launch {
            depthCoordinator.state.collect { depthState ->
                _state.update { editorState ->
                    editorState.copy(
                        isDepthInferenceRunning = depthState.isDepthInferenceRunning,
                        depthMap = depthState.depthMap,
                        depthEstimationError = depthState.depthEstimationError,
                        depthModelUiState = depthState.depthModelUiState,
                    )
                }
            }
        }
    }

    // ── Undo / redo ───────────────────────────────────────────────────────────

    private val maxHistory = 50

    // undoStack holds previous states (most recent at the end).
    private val undoStack = ArrayDeque<AnnotationEditorState>(maxHistory)

    // redoStack holds states available for redo after an undo.
    private val redoStack = ArrayDeque<AnnotationEditorState>(maxHistory)

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private fun pushUndo(before: AnnotationEditorState) {
        if (undoStack.size >= maxHistory) undoStack.removeFirst()
        undoStack.addLast(before)
        redoStack.clear()
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = false
    }

    /** Undo the most recent committed annotation. */
    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_state.value)
        _state.value = prev
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    /** Redo the most recently undone annotation. */
    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_state.value)
        _state.value = next
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    // ── Tool selection ────────────────────────────────────────────────────────

    /** Switch the active annotation tool and clear any in-progress points. */
    fun selectTool(tool: AnnotationTool) {
        _state.update { it.copy(currentTool = tool, inProgressPoints = emptyList()) }
    }

    // ── Initialization ────────────────────────────────────────────────────────

    /**
     * Load the [imageAnnotation] and fetch its persisted measurements from the repository.
     * Call once after construction with the target image.
     */
    fun initialize(imageAnnotation: ImageAnnotation) {
        _state.update { it.copy(imageAnnotation = imageAnnotation, calibration = imageAnnotation.calibration) }
        // Load persisted annotations once at startup. After that, committedAnnotations is managed
        // exclusively by optimistic local updates (commitAnnotation, undo, redo, deleteAnnotation).
        // A continuous collect would race with optimistic updates and corrupt undo/redo history.
        scope.launch {
            val result = measurementRepository.getMeasurementsForImage(imageAnnotation.uuid).first()
            result.onRight { list ->
                _state.update { currentSt ->
                    // Only apply the initial load if no annotations have been added yet
                    // (guards against calling initialize() multiple times).
                    if (currentSt.committedAnnotations.isEmpty()) {
                        currentSt.copy(committedAnnotations = list)
                    } else {
                        currentSt
                    }
                }
            }
        }
    }

    // ── Gesture handling ──────────────────────────────────────────────────────

    /**
     * Add a tap point to the in-progress annotation.
     *
     * When enough points have been collected for the active tool, [commitAnnotation] is
     * called automatically. For AREA, the polygon is committed when the user re-taps the
     * first point (within [closeTolerance] in normalized units) or explicitly taps a
     * "close polygon" UI button.
     */
    fun addPoint(point: NormalizedPoint) {
        val st = _state.value
        val tool = st.currentTool

        // LABEL tool: immediately show text input; commit on text confirm.
        if (tool == AnnotationTool.LABEL) {
            _state.update {
                it.copy(
                    pendingLabelPoint = point,
                    isLabelInputVisible = true,
                    labelInputText = "",
                )
            }
            return
        }

        val newPoints = st.inProgressPoints + point

        // AREA: check if user closed the polygon by tapping near the first point.
        if (tool == AnnotationTool.AREA && newPoints.size >= 4) {
            val first = newPoints.first()
            val closeTolerance = 0.03
            val dx = point.x - first.x
            val dy = point.y - first.y
            if (sqrt(dx * dx + dy * dy) < closeTolerance) {
                // Close the polygon — use all but the closing tap.
                commitAnnotation(newPoints.dropLast(1), tool)
                return
            }
        }

        _state.update { it.copy(inProgressPoints = newPoints) }

        // Auto-commit for non-open-ended tools once required count is reached.
        if (!tool.isOpenEnded() && newPoints.size >= tool.requiredPointCount()) {
            commitAnnotation(newPoints, tool)
        }
    }

    /** Explicitly close the in-progress AREA polygon. No-op for other tools. */
    fun closePolygon() {
        val st = _state.value
        if (st.currentTool != AnnotationTool.AREA || st.inProgressPoints.size < 3) return
        commitAnnotation(st.inProgressPoints, AnnotationTool.AREA)
    }

    /** Clear in-progress points without committing. */
    fun cancelInProgress() {
        _state.update { it.copy(inProgressPoints = emptyList()) }
    }

    // ── Label input ───────────────────────────────────────────────────────────

    /** Update the typed label text while the floating input is visible. */
    fun updateLabelText(text: String) {
        _state.update { it.copy(labelInputText = text) }
    }

    /** Confirm the label text and commit the LABEL annotation. */
    fun confirmLabel() {
        val st = _state.value
        val anchorPoint = st.pendingLabelPoint ?: return
        val label = st.labelInputText
        _state.update { it.copy(isLabelInputVisible = false, pendingLabelPoint = null, labelInputText = "") }
        commitAnnotation(listOf(anchorPoint), AnnotationTool.LABEL, explicitLabel = label)
    }

    /** Dismiss the label input without committing. */
    fun dismissLabelInput() {
        _state.update { it.copy(isLabelInputVisible = false, pendingLabelPoint = null, labelInputText = "") }
    }

    // ── GRID_REF calibration dialog ───────────────────────────────────────────

    /** Update the real-world length text while the GRID_REF dialog is visible. */
    fun updateGridRefLengthText(text: String) {
        _state.update { it.copy(gridRefLengthText = text) }
    }

    /** Update the unit selection in the GRID_REF dialog. */
    fun updateGridRefUnit(unit: MeasurementUnit) {
        _state.update { it.copy(gridRefUnit = unit) }
    }

    /**
     * Confirm the GRID_REF dialog: compute calibration from the drawn line and
     * the user-supplied real-world length, then re-derive all committed measurements.
     *
     * No-op if the length text is blank or non-positive.
     */
    fun confirmGridRefCalibration() {
        val st = _state.value
        val points = st.pendingGridRefPoints
        if (points.size < 2) return

        val lengthMeters = parseGridRefLengthToMeters(st.gridRefLengthText, st.gridRefUnit) ?: return
        if (lengthMeters <= 0.0) return

        val calibration = CalibrationService.computeFromReference(
            pixelStart = points[0],
            pixelEnd = points[1],
            imageWidthPx = imageWidthPx,
            imageHeightPx = imageHeightPx,
            knownLengthMeters = lengthMeters,
        ) ?: return

        _state.update {
            it.copy(
                isGridRefDialogVisible = false,
                pendingGridRefPoints = emptyList(),
                gridRefLengthText = "",
                inProgressPoints = emptyList(),
            )
        }
        updateCalibration(calibration)
        logger.info("GRID_REF calibration applied: ${calibration.pixelsPerMeter} px/m from ${lengthMeters}m reference")
    }

    /** Dismiss the GRID_REF dialog without applying calibration. */
    fun dismissGridRefDialog() {
        _state.update {
            it.copy(
                isGridRefDialogVisible = false,
                pendingGridRefPoints = emptyList(),
                gridRefLengthText = "",
                inProgressPoints = emptyList(),
            )
        }
    }

    // ── Commit ────────────────────────────────────────────────────────────────

    /**
     * Compute the measurement value from [points] and [tool], persist to the repository,
     * and update committed annotations in [state].
     *
     * For [AnnotationTool.GRID_REF], shows the reference-length dialog instead of
     * persisting an annotation — calibration is applied via [confirmGridRefCalibration].
     *
     * The [calibration] in the current state is used for unit conversion. If calibration
     * is absent (NONE), [valueMeters] is stored as null and the display shows pixel counts.
     */
    @OptIn(DirectRepositoryWrite::class)
    private fun commitAnnotation(
        points: List<NormalizedPoint>,
        tool: AnnotationTool,
        explicitLabel: String? = null,
    ) {
        // GRID_REF: show the calibration dialog instead of committing a measurement annotation.
        if (tool == AnnotationTool.GRID_REF) {
            val displayUnit = _state.value.imageAnnotation?.unit ?: MeasurementUnit.METERS
            _state.update {
                it.copy(
                    isGridRefDialogVisible = true,
                    pendingGridRefPoints = points,
                    gridRefLengthText = "",
                    gridRefUnit = displayUnit,
                    inProgressPoints = emptyList(),
                )
            }
            return
        }

        val st = _state.value
        val calibration = st.calibration
        val imageAnnotationUuid = st.imageAnnotation?.uuid ?: return
        val displayUnit = st.imageAnnotation?.unit ?: MeasurementUnit.METERS

        val (valueMeters, valueDisplay) = computeMeasurement(points, tool, calibration, displayUnit)

        val annotationType = tool.toAnnotationType()
        val measurement = MeasurementAnnotation(
            uuid = UuidGenerator.generateV7(),
            imageUuid = imageAnnotationUuid,
            annotationType = annotationType,
            normalizedPoints = points,
            valueMeters = valueMeters,
            valueDisplay = valueDisplay,
            label = explicitLabel,
        )

        val before = st
        pushUndo(before)

        // Story 9.6: Fire haptic feedback on annotation commit. The callback is injected
        // at construction time; on Android the composable caller wires LocalHapticFeedback
        // (HapticFeedbackType.LongPress). On desktop / in tests this is a no-op.
        onHapticFeedback()

        // Optimistic update: add to committed list immediately
        _state.update { currentSt ->
            currentSt.copy(
                inProgressPoints = emptyList(),
                committedAnnotations = currentSt.committedAnnotations + measurement,
            )
        }

        scope.launch {
            val result = measurementRepository.saveMeasurementAnnotation(measurement)
            result.onLeft { err ->
                logger.error("Failed to save annotation: ${err.message}")
                // Roll back optimistic update on failure
                _state.update { currentSt ->
                    currentSt.copy(
                        committedAnnotations = currentSt.committedAnnotations
                            .filter { it.uuid != measurement.uuid },
                    )
                }
            }
        }
    }

    // ── Calibration ───────────────────────────────────────────────────────────

    /**
     * Update the calibration and re-derive ALL committed measurements' [valueMeters].
     *
     * This is critical: if calibration changes (e.g. user marks a reference object),
     * all existing annotations must be recalculated or they display stale values.
     */
    @OptIn(DirectRepositoryWrite::class)
    fun updateCalibration(newCalibration: Calibration) {
        val st = _state.value
        val displayUnit = st.imageAnnotation?.unit ?: MeasurementUnit.METERS

        val rederived = st.committedAnnotations.map { annotation ->
            val (newValue, newDisplay) = computeMeasurement(
                annotation.normalizedPoints,
                annotation.annotationType.toTool(),
                newCalibration,
                displayUnit,
            )
            annotation.copy(valueMeters = newValue, valueDisplay = newDisplay)
        }

        _state.update { it.copy(calibration = newCalibration, committedAnnotations = rederived) }

        // Persist re-derived measurements to the repository
        scope.launch {
            val imageUuid = st.imageAnnotation?.uuid ?: return@launch
            measurementRepository.saveMeasurements(imageUuid, rederived).onLeft { err ->
                logger.error("Failed to persist re-derived measurements: ${err.message}")
            }
        }
    }

    // ── Selection and deletion ────────────────────────────────────────────────

    /** Select an annotation by UUID (SELECT tool). */
    fun selectAnnotation(uuid: String) {
        _state.update { it.copy(selectedAnnotationUuid = uuid) }
    }

    /** Clear the current selection. */
    fun clearSelection() {
        _state.update { it.copy(selectedAnnotationUuid = null) }
    }

    /** Delete the currently selected annotation. */
    fun deleteSelectedAnnotation() {
        val selectedUuid = _state.value.selectedAnnotationUuid ?: return
        deleteAnnotation(selectedUuid)
    }

    /** Delete an annotation by UUID. */
    @OptIn(DirectRepositoryWrite::class)
    fun deleteAnnotation(uuid: String) {
        val before = _state.value
        pushUndo(before)

        _state.update { currentSt ->
            currentSt.copy(
                committedAnnotations = currentSt.committedAnnotations.filter { it.uuid != uuid },
                selectedAnnotationUuid = if (currentSt.selectedAnnotationUuid == uuid) null else currentSt.selectedAnnotationUuid,
            )
        }

        scope.launch {
            measurementRepository.deleteMeasurementAnnotation(uuid).onLeft { err ->
                logger.error("Failed to delete annotation $uuid: ${err.message}")
            }
        }
    }

    // ── Measurement computation ───────────────────────────────────────────────

    /**
     * Compute the [valueMeters] and [valueDisplay] for a set of [points] and [tool].
     *
     * Uses the Shoelace formula for area, dot-product for angle, and Euclidean distance
     * for linear measurements. All input coordinates are in normalized [0,1] image space.
     */
    private fun computeMeasurement(
        points: List<NormalizedPoint>,
        tool: AnnotationTool,
        calibration: Calibration?,
        displayUnit: MeasurementUnit,
    ): Pair<Double?, String?> {
        val ppm = calibration?.pixelsPerMeter ?: 0.0
        return when (tool) {
            AnnotationTool.DISTANCE, AnnotationTool.GRID_REF -> {
                if (points.size < 2) return null to null
                val p1 = points[0]
                val p2 = points[1]
                val dx = (p2.x - p1.x) * imageWidthPx
                val dy = (p2.y - p1.y) * imageHeightPx
                val pixelDist = sqrt(dx * dx + dy * dy)
                if (ppm == 0.0) {
                    null to "${pixelDist.toInt()} px"
                } else {
                    val meters = pixelDist / ppm
                    meters to metersToDisplayString(meters, displayUnit)
                }
            }

            AnnotationTool.AREA -> {
                if (points.size < 3) return null to null
                if (ppm == 0.0) {
                    null to "uncalibrated"
                } else {
                    val area = polygonAreaMeters(points, ppm, imageWidthPx, imageHeightPx)
                    area to "${metersToDisplayString(area, displayUnit)}²"
                }
            }

            AnnotationTool.ANGLE -> {
                if (points.size < 3) return null to null
                val result = angleBetweenThreePoints(points[0], points[1], points[2])
                when (result) {
                    is Either.Right -> {
                        val deg = result.value
                        deg to "${deg.toStringWithDecimals(1)}°"
                    }
                    is Either.Left -> null to null
                }
            }

            AnnotationTool.LABEL -> null to null

            AnnotationTool.SELECT -> null to null
        }
    }

    // ── Tag management ────────────────────────────────────────────────────────

    /**
     * Add [tag] to the current [ImageAnnotation.tags] list and persist the updated annotation.
     *
     * Tag format is compatible with Logseq block properties: lower-case, no spaces, no `#`.
     * The `::tags::` property (for Logseq query compatibility) is managed by
     * [MeasurementPropertySyncer] separately; this method only persists the raw tag list.
     *
     * No-op if [tag] is already present or blank.
     */
    fun addTag(tag: String) {
        val sanitized = tag.trim().lowercase().replace(Regex("[^a-z0-9-_]"), "-")
        if (sanitized.isBlank()) return
        val current = _state.value.imageAnnotation ?: return
        if (sanitized in current.tags) return
        val updated = current.copy(tags = current.tags + sanitized)
        _state.update { it.copy(imageAnnotation = updated) }
        persistTagsUpdate(updated)
    }

    /**
     * Remove [tag] from the current [ImageAnnotation.tags] list and persist.
     */
    fun removeTag(tag: String) {
        val current = _state.value.imageAnnotation ?: return
        if (tag !in current.tags) return
        val updated = current.copy(tags = current.tags - tag)
        _state.update { it.copy(imageAnnotation = updated) }
        persistTagsUpdate(updated)
    }

    @OptIn(DirectRepositoryWrite::class)
    private fun persistTagsUpdate(updated: ImageAnnotation) {
        val repo = imageAnnotationRepository ?: return
        scope.launch {
            repo.saveImageAnnotation(updated).onLeft { err ->
                logger.error("Failed to persist tag update: ${err.message}")
            }
        }
    }

    // ── External measurement device (Story 5.7) ──────────────────────────────

    /** Currently active external measurement device (BLE laser, keyboard, USB). */
    private var activeDevice: ExternalMeasurementDevice? = null

    /**
     * Connection state of the active device, or DISCONNECTED when no device is set.
     * Sourced directly from the device's [ExternalMeasurementDevice.connectionState].
     */
    val deviceConnectionState: StateFlow<DeviceConnectionState>
        get() = activeDevice?.connectionState ?: _noDeviceConnectionState

    private val _noDeviceConnectionState =
        MutableStateFlow(DeviceConnectionState.DISCONNECTED)

    /**
     * Set the active measurement device.
     *
     * The ViewModel does NOT connect the device automatically — call
     * [ExternalMeasurementDevice.connect] before setting it here, or let the
     * UI layer manage the connection lifecycle.
     *
     * Setting a new device replaces the previous one without disconnecting it
     * (disconnection is the caller's responsibility).
     */
    fun setActiveDevice(device: ExternalMeasurementDevice) {
        activeDevice = device
    }

    /**
     * Inject the next reading from the active device into the most recently committed
     * DISTANCE or GRID_REF annotation.
     *
     * Flow:
     * 1. Takes the first [MeasurementReading] from [ExternalMeasurementDevice.measurementFlow].
     * 2. Updates the most recently committed distance annotation's [MeasurementAnnotation.valueMeters].
     * 3. Updates [Calibration] to [CalibrationMethod.BLE_LASER] with 100% confidence.
     * 4. Re-derives all other annotations using [updateCalibration].
     *
     * No-op when:
     * - No active device is set.
     * - No distance annotation has been committed yet.
     * - The device emits no readings within the collection window (caller cancels the scope).
     */
    fun injectMeasurementFromDevice() {
        val device = activeDevice ?: return
        val st = _state.value

        // Find the most recently committed DISTANCE annotation.
        val targetAnnotation = st.committedAnnotations
            .lastOrNull { it.annotationType == AnnotationType.DISTANCE || it.annotationType == AnnotationType.GRID_REF }
            ?: return

        scope.launch {
            val reading = device.measurementFlow().first()

            // Compute new pixelsPerMeter from this annotation's pixel length and the laser reading.
            if (reading.valueMeters <= 0.0) return@launch

            val points = targetAnnotation.normalizedPoints
            if (points.size < 2) return@launch

            val p1 = points[0]
            val p2 = points[1]
            val dx = (p2.x - p1.x) * imageWidthPx
            val dy = (p2.y - p1.y) * imageHeightPx
            val pixelDist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (pixelDist <= 0.0) return@launch

            val newPixelsPerMeter = pixelDist / reading.valueMeters
            val newCalibration = Calibration(
                method = CalibrationMethod.BLE_LASER,
                pixelsPerMeter = newPixelsPerMeter,
                confidencePercent = 100,
            )

            // Update the target annotation with the BLE device ID.
            val updatedAnnotation = targetAnnotation.copy(
                valueMeters = reading.valueMeters,
                valueDisplay = "${reading.valueMeters} m",
                bleDeviceId = reading.deviceId,
            )
            _state.update { currentSt ->
                currentSt.copy(
                    committedAnnotations = currentSt.committedAnnotations.map { ann ->
                        if (ann.uuid == targetAnnotation.uuid) updatedAnnotation else ann
                    },
                )
            }

            // Re-derive all annotations with the new calibration.
            updateCalibration(newCalibration)

            logger.info("BLE reading injected: ${reading.valueMeters}m from ${reading.deviceId}")
        }
    }

    // ── Monocular depth estimation (delegated to DepthEstimationCoordinator) ──

    /**
     * Push the current [DepthModelUiState] from the platform layer into the coordinator,
     * which propagates the change into [AnnotationEditorState] automatically.
     *
     * Called from the Android entry point (or a `LaunchedEffect`) when
     * [DepthModelDownloader.modelState] emits a new value.
     */
    fun updateDepthModelUiState(uiState: DepthModelUiState) {
        depthCoordinator.updateDepthModelUiState(uiState)
    }

    /**
     * Run monocular depth estimation on [imageBitmap] via [DepthEstimationCoordinator].
     *
     * Flow:
     * 1. Calls [MonocularDepthEstimator.initialize] (idempotent after first success).
     * 2. Runs [MonocularDepthEstimator.estimateDepth] on the coordinator's internal scope.
     * 3. On success: stores the depth map in [AnnotationEditorState.depthMap] and applies
     *    a [CalibrationMethod.MONOCULAR_ML] calibration at the tap point if provided.
     * 4. On failure: stores the error message in [AnnotationEditorState.depthEstimationError].
     *
     * ADR-005: confidence is 15%. The UI must show the low-confidence warning.
     *
     * @param imageBitmap   the image to estimate depth for
     * @param tapPoint      optional normalized tap point used to sample depth for calibration
     */
    fun runDepthEstimation(imageBitmap: ImageBitmap, tapPoint: NormalizedPoint? = null) {
        depthCoordinator.runDepthEstimation(imageBitmap, tapPoint)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Cancel the internal [CoroutineScope] and the [DepthEstimationCoordinator].
     * Call when the editor is permanently dismissed.
     */
    fun close() {
        depthCoordinator.close()
        scope.launch { /* drain */ }.cancel()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}

// ── Extension helpers ─────────────────────────────────────────────────────────

private fun AnnotationTool.toAnnotationType(): AnnotationType = when (this) {
    AnnotationTool.SELECT -> AnnotationType.DISTANCE
    AnnotationTool.DISTANCE -> AnnotationType.DISTANCE
    AnnotationTool.AREA -> AnnotationType.AREA
    AnnotationTool.ANGLE -> AnnotationType.ANGLE
    AnnotationTool.LABEL -> AnnotationType.LABEL
    AnnotationTool.GRID_REF -> AnnotationType.GRID_REF
}

private fun AnnotationType.toTool(): AnnotationTool = when (this) {
    AnnotationType.DISTANCE -> AnnotationTool.DISTANCE
    AnnotationType.AREA -> AnnotationTool.AREA
    AnnotationType.ANGLE -> AnnotationTool.ANGLE
    AnnotationType.LABEL -> AnnotationTool.LABEL
    AnnotationType.GRID_REF -> AnnotationTool.GRID_REF
}

private fun Double.toStringWithDecimals(decimals: Int): String {
    var factor = 1.0
    repeat(decimals) { factor *= 10.0 }
    val rounded = kotlin.math.round(this * factor) / factor
    val intPart = rounded.toLong()
    val fracPart = kotlin.math.abs((rounded - intPart) * factor).toLong()
    return "$intPart.${fracPart.toString().padStart(decimals, '0')}"
}

/**
 * Parse the user-supplied length string from the GRID_REF dialog to meters.
 *
 * Accepts a decimal number. The [unit] governs the interpretation:
 * - METERS → value as-is
 * - CENTIMETERS → divide by 100
 * - MILLIMETERS → divide by 1000
 * - FEET → multiply by 0.3048
 * - INCHES → multiply by 0.0254
 *
 * Returns null if the text cannot be parsed or results in a non-positive value.
 */
internal fun parseGridRefLengthToMeters(text: String, unit: MeasurementUnit): Double? {
    val value = text.trim().toDoubleOrNull() ?: return null
    if (value <= 0.0) return null
    return when (unit) {
        MeasurementUnit.METERS -> value
        MeasurementUnit.CENTIMETERS -> value / 100.0
        MeasurementUnit.MILLIMETERS -> value / 1000.0
        MeasurementUnit.FEET -> value * 0.3048
        MeasurementUnit.INCHES -> value * 0.0254
    }
}
