package dev.stapler.stelekit.ui.annotate

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import dev.stapler.stelekit.calibration.CalibrationService
import dev.stapler.stelekit.calibration.CaptureFrameInfo
import dev.stapler.stelekit.calibration.ConstraintCalibrator
import dev.stapler.stelekit.calibration.ConstraintFitApplier
import dev.stapler.stelekit.calibration.ConstraintTuning
import dev.stapler.stelekit.calibration.FitOptions
import dev.stapler.stelekit.calibration.FocalSourceResolver
import dev.stapler.stelekit.calibration.LensRecordFocal
import dev.stapler.stelekit.calibration.TierRequest
import dev.stapler.stelekit.calibration.describe
import dev.stapler.stelekit.calibration.CameraProfile
import dev.stapler.stelekit.calibration.CameraProfileStore
import dev.stapler.stelekit.calibration.CameraProfiles
import dev.stapler.stelekit.calibration.ProfileRejection
import dev.stapler.stelekit.calibration.HeightOffset
import dev.stapler.stelekit.calibration.LensMeasurement
import dev.stapler.stelekit.calibration.LensMode
import dev.stapler.stelekit.calibration.MeasurementAdvisor
import dev.stapler.stelekit.calibration.MeasurementReliability
import dev.stapler.stelekit.calibration.PlanePreview
import dev.stapler.stelekit.calibration.PlaneReference
import dev.stapler.stelekit.calibration.PlaneSession
import dev.stapler.stelekit.calibration.ReferenceRectangle
import dev.stapler.stelekit.calibration.MultiPointCalibration
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.CalibrationConstraint
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ConstraintSource
import dev.stapler.stelekit.model.FitReport
import dev.stapler.stelekit.model.MeasurementValue
import dev.stapler.stelekit.model.ValueSource
import dev.stapler.stelekit.model.CalibrationTick
import dev.stapler.stelekit.model.DEFAULT_MEASUREMENT_UNIT
import dev.stapler.stelekit.model.GeometryCalibration
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.model.angleBetweenThreePoints
import dev.stapler.stelekit.model.metersToDisplayString
import dev.stapler.stelekit.model.polygonAreaMeters
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.annotatorAutoReturnToSelect
import dev.stapler.stelekit.platform.annotatorStickyTools
import dev.stapler.stelekit.platform.setDefaultMeasurementUnit
import dev.stapler.stelekit.platform.diagnostics.EditorDiagnosticsSnapshot
import dev.stapler.stelekit.platform.diagnostics.EditorDiagnosticsSource
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
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import arrow.atomic.Atomic
import arrow.atomic.AtomicBoolean
import arrow.atomic.getAndUpdate
import arrow.atomic.value
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
    val currentTool: AnnotationTool = AnnotationTool.SELECT,
    /** Whether a placement tool disarms after a commit (ONE_SHOT) or stays armed ("Keep tool"). */
    val stickiness: ToolStickiness = ToolStickiness.ONE_SHOT,
    /** Story 3.6: the next committed distance is a known-distance reference. */
    val pendingKnownDistance: Boolean = false,
    /**
     * Persisted rows deleted this session. Lives in the state (not beside it) so a delete changes the state's
     * equality: a late load merge computed before the delete then fails its compare-and-set and recomputes.
     */
    val sessionDeleted: Set<String> = emptySet(),
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
    /** Unit used to label every measurement; starts as the image's stored unit, changed via [AnnotationEditorViewModel.setDisplayUnit]. */
    val selectedUnit: MeasurementUnit = DEFAULT_MEASUREMENT_UNIT,
    // ── Monocular depth estimation state ──────────────────────────────────
    /** True while ONNX inference is running (shows spinner). */
    val isDepthInferenceRunning: Boolean = false,
    /** Non-null after successful depth estimation. Relative depth map, normalized [0,1]. */
    val depthMap: FloatArray? = null,
    /** Error message from the most recent depth estimation attempt. Null when none. */
    val depthEstimationError: String? = null,
    /** Current download/readiness state of the depth model file. */
    val depthModelUiState: DepthModelUiState = DepthModelUiState.Absent,
    /** Set when a Throwable escaped an editor coroutine; see [EditorError]. Cleared by Retry. */
    val error: EditorError? = null,
    /** Opt-in non-drag crosshair placement (C.8); idle unless the user turns the mode on. */
    val placement: AnnotationPlacementSession = AnnotationPlacementSession(),
    /** Non-null while the user is marking ruler ticks for a multi-point calibration. */
    val rulerSession: RulerTickSession? = null,
    /** One-line result of the last "save camera profile" attempt; null when none or dismissed. */
    val cameraProfileNotice: String? = null,
    /** Non-null while the user is tapping the corners of a known rectangle (paper or card) to calibrate a plane. */
    val planeSession: PlaneSession? = null,
    /** One-line strip message from the last placement gesture ("Cancelled", identical points); null when none. */
    val placementMessage: String? = null,
)

/**
 * A Throwable caught by the editor's scope handler. [stopsInput] is true for an [Error] (for example
 * `OutOfMemoryError`): the editor refuses edits until Retry or Close editor, rather than carrying on
 * in a possibly broken state. Committed measurements are never removed by an error.
 */
data class EditorError(val message: String, val stopsInput: Boolean)

/**
 * Platform-independent mirror of [DepthModelDownloader.ModelState] for common UI.
 *
 * Populated via [AnnotationEditorViewModel.updateDepthModelUiState] from the Android
 * platform layer which observes [DepthModelDownloader.modelState].
 */
sealed interface DepthModelUiState {
    /** Model not downloaded. Show "Download depth model (~100MB)" button. */
    data object Absent : DepthModelUiState

    /** Download in progress. [progress] is 0–100 or -1 if indeterminate. [notice] is an optional one-line caveat. */
    data class Downloading(val progress: Int, val notice: String? = null) : DepthModelUiState

    /**
     * Download queued but not running (waiting for an unmetered network, offline, or backing off). [canDownloadAnyway]
     * offers "Download now anyway" for the metered case.
     */
    data class Waiting(val message: String, val canDownloadAnyway: Boolean, val notice: String? = null) : DepthModelUiState

    /** Model ready — show "Estimate depth (AI)" button. */
    data object Ready : DepthModelUiState

    /** Download cancelled — show "Download cancelled." with a "Download again" action. */
    data object Cancelled : DepthModelUiState

    /**
     * Download failed. Show "Download failed — tap to retry", or [reason] followed by
     * "Tap to retry." when set (e.g. a stall-timeout transition surfaces plain-language copy
     * instead of the generic message).
     */
    data class Failed(val reason: String? = null) : DepthModelUiState
}

/** Open question "clear your edited value and use the measured length?" raised by dragging an override (Story 3.4). */
data class OverrideDragConfirm(val uuid: String, val pointsBefore: List<NormalizedPoint>)

/** What the last constraint fit produced for the sheet; [fallbackNote] says why a requested tier was not used. */
data class ConstraintFitResult(val report: FitReport?, val fallbackNote: String?)

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
@Suppress("LargeClass") // single editor state machine; splitting it is a separate refactor
class AnnotationEditorViewModel(
    private val measurementRepository: MeasurementAnnotationRepository,
    /** Optional — required for tag persistence. When null, tag changes update state only. */
    private val imageAnnotationRepository: ImageAnnotationRepository? = null,
    // Initial image dimensions in native pixels; the screen replaces them via [setImageSize] once
    // the image decodes. Defaults allow unit tests to omit them.
    imageWidthPx: Double = 1.0,
    imageHeightPx: Double = 1.0,
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
    /** Optional — when set, a unit change also becomes the global default for new annotations. */
    private val settings: Settings? = null,
    /** Wall clock for the undo-after-drag-measure counter; injectable so tests need no real wait. */
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val cameraProfileStore: CameraProfileStore? = settings?.let(::CameraProfileStore)

    // CRITICAL: internal scope — never injected from outside composition.
    // The handler keeps an uncaught Throwable (notably OutOfMemoryError) from reaching the default
    // handler, which kills the process on Android; it surfaces as [AnnotationEditorState.error].
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t -> reportThrowable(t) },
    )
    private val logger = Logger("AnnotationEditorViewModel")

    // Latest drag-measure commit (uuid, time) for the undo-within-5s counter.
    private var lastDragMeasure: Pair<String, Long>? = null

    private fun reportThrowable(t: Throwable) {
        // Logging can itself fail under memory pressure; the state update is what the user needs.
        try {
            logger.error("Annotation editor coroutine failed: ${t::class.simpleName}: ${t.message}", t)
        } catch (_: Throwable) {
            // nothing more to do: the error state below still surfaces the failure
        }
        _state.update {
            it.copy(error = EditorError(message = t.message ?: (t::class.simpleName ?: "unknown"), stopsInput = t is Error))
        }
    }

    private fun inputStopped(): Boolean = _state.value.error?.stopsInput == true

    /** Retry after an error: clears the banner and re-saves the committed set (the likeliest failed step). */
    fun retryAfterError() {
        _state.update { it.copy(error = null, inProgressPoints = emptyList()) }
        persistCommittedSet()
    }

    /** Read-only snapshot for the Android diagnostics sampler (task A.2f). */
    val diagnosticsSource: EditorDiagnosticsSource = EditorDiagnosticsSource {
        EditorDiagnosticsSnapshot(
            tool = _state.value.currentTool.name,
            screen = "AnnotationEditor",
            // `this.`: a bare name would bind to the constructor parameter, not the live property.
            imageWidthPx = this.imageWidthPx.toInt(),
            imageHeightPx = this.imageHeightPx.toInt(),
            undoDepth = history.undoDepth,
        )
    }

    // Native image size used to denormalize points. Written from the UI thread, read on scope threads.
    @kotlin.concurrent.Volatile private var imageWidthPx: Double = imageWidthPx
    @kotlin.concurrent.Volatile private var imageHeightPx: Double = imageHeightPx

    // Serializes persistence so a slow older save can't land after a newer one; set-saves re-read the latest state when they run.
    private val persistence = AnnotationPersistenceQueue(onThrowable = ::reportThrowable)

    // Rows this session added or deleted; a late initial load must not re-add them (e.g. after undo).
    private val sessionTouched = Atomic<Set<String>>(emptySet())

    // True when the initial load failed: a bulk replace would wipe rows that were never shown.
    private val loadFailed = AtomicBoolean(false)

    /** Writes still in flight, for the leave-while-saving wait. */
    val pendingWrites: StateFlow<Int> get() = persistence.pending

    /** Latest persistence failure, for a retry banner. */
    val persistenceFailure: StateFlow<DomainError?> get() = persistence.failure

    /** Waits briefly for in-flight saves, then decides what Back should do (see [backOutcome]). */
    suspend fun resolveBack(waitMillis: Long = LEAVE_SAVE_WAIT_MILLIS): BackOutcome {
        withTimeoutOrNull(waitMillis) { persistence.awaitIdle() }
        return backOutcome(_state.value.inProgressPoints.size, persistence.failure.value != null)
    }

    /** Re-saves the committed set after a failed save, clearing the failure first. */
    fun retryPersistence() {
        persistence.clearFailure()
        persistCommittedSet()
    }

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun saveRow(row: MeasurementAnnotation) = measurementRepository.saveMeasurementAnnotation(row)

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun saveRows(imageUuid: String, rows: List<MeasurementAnnotation>) =
        measurementRepository.saveMeasurements(imageUuid, rows)

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun deleteRow(uuid: String) = measurementRepository.deleteMeasurementAnnotation(uuid)

    private suspend fun saveSet(imageUuid: String, rows: List<MeasurementAnnotation>): Either<DomainError, Unit> {
        if (!loadFailed.value) return saveRows(imageUuid, rows)
        rows.forEach { row -> saveRow(row).onLeft { return it.left() } }
        return Unit.right()
    }

    /** Supplies the decoded image's native size; without it every pixel distance is ~1 px. */
    fun setImageSize(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        imageWidthPx = widthPx.toDouble()
        imageHeightPx = heightPx.toDouble()
        depthCoordinator.setImageSize(imageWidthPx, imageHeightPx)
    }

    /** Image-pixel length of the committed two-point line [uuid]; 0 when it is missing or too short to set a scale. */
    fun segmentPixelLength(uuid: String): Double {
        val points = _state.value.committedAnnotations.firstOrNull { it.uuid == uuid }?.normalizedPoints ?: return 0.0
        if (points.size < 2) return 0.0
        return kotlin.math.hypot((points[1].x - points[0].x) * imageWidthPx, (points[1].y - points[0].y) * imageHeightPx)
    }

    /**
     * Set to true on the first call to [initialize] to prevent a second call from
     * re-issuing the one-shot repository load.
     */
    private val initialized = AtomicBoolean(false)

    // ── Depth estimation coordinator ──────────────────────────────────────────
    private val depthCoordinator = DepthEstimationCoordinator(
        depthEstimator = monocularDepthEstimator,
        imageWidthPx = imageWidthPx,
        imageHeightPx = imageHeightPx,
        onCalibrationReady = { cal -> updateCalibration(cal) },
        onUnhandledThrowable = { t -> reportThrowable(t) },
    )

    private val initialStickiness =
        if (settings.annotatorStickyTools()) ToolStickiness.LOCKED else ToolStickiness.ONE_SHOT
    private val _state = MutableStateFlow(AnnotationEditorState(stickiness = initialStickiness))
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

    private val history = UndoHistory<AnnotationEditorState>(maxHistory = 50)

    /** Committed-but-undoable states (capped at 50); read by the benchmark probe and tests. */
    internal val undoDepth: Int get() = history.undoDepth

    // Single-level calibration undo (constraints and fit ride in the snapshot's geometry). Written from
    // Dispatchers.Default (updateCalibration) and read from the main thread (undoCalibration), so an
    // Atomic reference is used for thread safety without introducing JVM-only types.
    private val calibrationHistory = Atomic(CalibrationHistory.empty())

    private val _canUndoCalibration = MutableStateFlow(false)
    val canUndoCalibration: StateFlow<Boolean> = _canUndoCalibration.asStateFlow()

    private val _calibrationChangeMessage = MutableStateFlow<String?>(null)
    val calibrationChangeMessage: StateFlow<String?> = _calibrationChangeMessage.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private fun pushUndo(before: AnnotationEditorState) {
        history.push(before)
        _canUndo.value = history.canUndo
        _canRedo.value = false
    }

    /** Undo the most recent committed annotation. */
    fun undo() {
        if (inputStopped()) return
        val current = _state.value
        val prev = history.undo(current) ?: return
        restoreSnapshot(prev, current)
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
        persistCommittedSet()
        countUndoAfterDragMeasure()
    }

    /** Calibration is undone only through [undoCalibration]; here computed values are re-derived under the live scale. */
    private fun restoreSnapshot(snapshot: AnnotationEditorState, current: AnnotationEditorState) {
        val restored = snapshot.restoredOver(current)
        _state.value = restored.copy(
            committedAnnotations = rederive(restored.committedAnnotations, restored.calibration, restored.selectedUnit)
                .map { it.relabeled(restored.selectedUnit) },
        )
    }

    private fun countUndoAfterDragMeasure() {
        val (uuid, atMs) = lastDragMeasure ?: return
        if (nowMillis() - atMs > UNDO_AFTER_DRAG_MEASURE_WINDOW_MS) return
        if (_state.value.committedAnnotations.any { it.uuid == uuid }) return
        lastDragMeasure = null
        PlacementCounters.undoAfterDragMeasure()
    }

    /** Redo the most recently undone annotation. */
    fun redo() {
        if (inputStopped()) return
        val current = _state.value
        val next = history.redo(current) ?: return
        restoreSnapshot(next, current)
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
        persistCommittedSet()
    }

    // ── Tool selection ────────────────────────────────────────────────────────

    /** Switch the active annotation tool and clear any in-progress points. */
    fun selectTool(tool: AnnotationTool) {
        if (inputStopped()) return
        _state.update {
            it.copy(
                currentTool = tool,
                pendingKnownDistance = false,
                inProgressPoints = emptyList(),
                rulerSession = if (tool == AnnotationTool.GRID_REF) it.rulerSession else null,
                planeSession = if (tool == AnnotationTool.GRID_REF) it.planeSession else null,
                placement = if (tool == AnnotationTool.SELECT) AnnotationPlacementSession() else it.placement,
                placementMessage = null,
            )
        }
    }

    /** Places both ends of a drag-measure as one commit (ADR-007); false, with no pending point left, when it did not commit. */
    fun placeDrag(start: NormalizedPoint, end: NormalizedPoint): Boolean {
        val before = _state.value.committedAnnotations.size
        addPoint(start)
        addPoint(end)
        val after = _state.value.committedAnnotations
        if (after.size != before + 1) {
            _state.update { it.copy(inProgressPoints = emptyList()) }
            return false
        }
        PlacementCounters.dragMeasure()
        lastDragMeasure = after.last().uuid to nowMillis()
        return true
    }

    /** Drops a pending first point; the tool stays armed. */
    fun discardFirstPoint() {
        _state.update { it.copy(inProgressPoints = emptyList(), placementMessage = null) }
    }

    /** Back to SELECT, discarding any pending point. */
    fun disarm() = selectTool(AnnotationTool.SELECT)

    /** A placement was cancelled (off-canvas or gesture-inset release): the strip says so; the tool stays armed (OC-9). */
    fun cancelPlacementGesture() {
        _state.update { it.copy(placementMessage = CANCELLED_MESSAGE) }
    }

    fun setStickiness(stickiness: ToolStickiness) = _state.update { it.copy(stickiness = stickiness) }

    fun toggleKeepTool() = _state.update {
        it.copy(stickiness = if (it.stickiness == ToolStickiness.LOCKED) ToolStickiness.ONE_SHOT else ToolStickiness.LOCKED)
    }

    /** The length from the pending first DISTANCE point to [to], formatted like a commit; null with no first point. */
    fun liveDistanceDisplay(to: NormalizedPoint): String? {
        val st = _state.value
        if (st.currentTool != AnnotationTool.DISTANCE || st.inProgressPoints.size != 1) return null
        return computeMeasurement(listOf(st.inProgressPoints[0], to), AnnotationTool.DISTANCE, st.calibration, st.selectedUnit).second
    }

    /** The tool to arm once a multi-step calibration session is confirmed (ADR-005). */
    private fun returnToolAfterConfirm() {
        val autoReturn = settings.annotatorAutoReturnToSelect()
        _state.update { it.copy(currentTool = nextToolAfterCommit(it.currentTool, it.stickiness, autoReturn)) }
    }

    /** Applies [transform] to the non-drag placement session; see the extensions in AnnotationPlacement.kt. */
    internal fun updatePlacement(transform: (AnnotationPlacementSession) -> AnnotationPlacementSession) {
        if (inputStopped()) return
        _state.update { it.copy(placement = transform(it.placement)) }
    }

    // ── Initialization ────────────────────────────────────────────────────────

    /**
     * Load the [imageAnnotation] and fetch its persisted measurements from the repository.
     * Call once after construction with the target image.
     */
    fun initialize(imageAnnotation: ImageAnnotation) {
        // Guard: only run the repository load once per ViewModel instance. Calling initialize()
        // a second time updates the calibration/image state but must not overwrite annotations
        // that the user has already added or deleted in this session.
        val firstCall = initialized.compareAndSet(false, true)
        _state.update {
            it.copy(
                // A repeat call may carry a stale copy; the unit the user chose here wins.
                imageAnnotation = if (firstCall) imageAnnotation else imageAnnotation.copy(unit = it.selectedUnit),
                calibration = imageAnnotation.calibration,
                selectedUnit = if (firstCall) imageAnnotation.unit else it.selectedUnit,
            )
        }
        if (!firstCall) return
        // Load persisted annotations once at startup. After that, committedAnnotations is managed
        // exclusively by optimistic local updates (commitAnnotation, undo, redo, deleteAnnotation).
        // A continuous collect would race with optimistic updates and corrupt undo/redo history.
        scope.launch {
            val result = measurementRepository.getMeasurementsForImage(imageAnnotation.uuid).first()
            result.onLeft { err ->
                loadFailed.value = true
                logger.error("Failed to load saved measurements: ${err.message}")
            }
            result.onRight { list ->
                // Merge inside the CAS so a load that finishes after the first placement keeps
                // both the persisted rows and the new ones (a later bulk save would delete them).
                _state.update { currentSt ->
                    currentSt.copy(
                        committedAnnotations = mergeInitialLoad(
                            list.map { it.relabeled(currentSt.selectedUnit) },
                            currentSt.committedAnnotations,
                            sessionTouched.value + currentSt.sessionDeleted,
                        ),
                    )
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
        if (inputStopped()) return
        _state.update { if (it.placementMessage != null) it.copy(placementMessage = null) else it }
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

        val ruler = st.rulerSession
        if (ruler != null && tool == AnnotationTool.GRID_REF) {
            setRulerSession(ruler.withTick(point))
            return
        }
        val plane = st.planeSession
        if (plane != null && tool == AnnotationTool.GRID_REF) {
            setPlaneSession(plane.withCorner(point))
            return
        }

        if (tool == AnnotationTool.DISTANCE && st.inProgressPoints.size == 1 && isIdenticalToFirst(st.inProgressPoints[0], point)) {
            _state.update { it.copy(placementMessage = IDENTICAL_POINTS_MESSAGE) }
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

    private fun isIdenticalToFirst(first: NormalizedPoint, second: NormalizedPoint): Boolean = arePointsIdentical(
        Offset((first.x * imageWidthPx).toFloat(), (first.y * imageHeightPx).toFloat()),
        Offset((second.x * imageWidthPx).toFloat(), (second.y * imageHeightPx).toFloat()),
    )

    /** Explicitly close the in-progress AREA polygon. No-op for other tools. */
    fun closePolygon() {
        if (inputStopped()) return
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
        if (inputStopped()) return
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
        if (inputStopped()) return
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
        updateCalibration(keepingGeometry(calibration))
        returnToolAfterConfirm()
        logger.info("GRID_REF calibration applied: ${calibration.pixelsPerMeter} px/m from ${lengthMeters}m reference")
    }

    /**
     * Applies a multi-point (basis v2) ruler calibration from 2+ marked ticks and re-derives all
     * committed measurements. [mode] picks no correction, a preset k, or automatic estimation.
     * No-op when the ticks cannot define a scale.
     */
    fun confirmMultiPointCalibration(ticks: List<CalibrationTick>, mode: LensMode, profile: CameraProfile? = null) {
        if (inputStopped()) return
        val calibration = MultiPointCalibration.calibrate(ticks, imageWidthPx, imageHeightPx, mode, profile) ?: return
        updateCalibration(keepingGeometry(calibration))
        logger.info("Multi-point calibration applied: ${ticks.size} ticks, k=${calibration.lens?.k}")
    }

    // ── Ruler tick session (multi-point calibration UI) ───────────────────────

    private fun setRulerSession(session: RulerTickSession?) {
        _state.update { it.copy(rulerSession = session, inProgressPoints = session?.entries?.map { e -> e.point }.orEmpty()) }
    }

    /**
     * Starts marking ruler ticks. When the 2-point reference dialog is open, its two points and typed
     * length seed the first two ticks (0 and the length); otherwise the session starts empty.
     */
    fun beginRulerSession() {
        if (inputStopped()) return
        val st = _state.value
        val seeds = st.pendingGridRefPoints.takeIf { it.size >= 2 }
        val unit = if (seeds != null) st.gridRefUnit else st.selectedUnit.takeUnless { it == MeasurementUnit.FEET_INCHES }
            ?: MeasurementUnit.CENTIMETERS
        val entries = seeds?.let {
            listOf(RulerTickEntry(it[0], "0"), RulerTickEntry(it[1], st.gridRefLengthText.trim()))
        }.orEmpty()
        _state.update {
            it.copy(
                currentTool = AnnotationTool.GRID_REF,
                isGridRefDialogVisible = false,
                pendingGridRefPoints = emptyList(),
                gridRefLengthText = "",
                planeSession = null,
            )
        }
        val profile = cameraProfileStore?.all()?.let {
            CameraProfiles.match(it, st.imageAnnotation?.sensorData, imageWidthPx, imageHeightPx)
        }
        setRulerSession(RulerTickSession(unit = unit, entries = entries, lensMode = LensMode.Profile, profile = profile))
    }

    fun updateRulerTickValue(index: Int, text: String) = updateRulerSession { it.withValue(index, text) }

    fun removeRulerTick(index: Int) = updateRulerSession { it.withoutTick(index) }

    fun setRulerUnit(unit: MeasurementUnit) = updateRulerSession { it.copy(unit = unit) }

    fun setRulerLensMode(mode: LensMode) = updateRulerSession { it.copy(lensMode = mode) }

    private fun updateRulerSession(transform: (RulerTickSession) -> RulerTickSession) {
        val session = _state.value.rulerSession ?: return
        setRulerSession(transform(session))
    }

    /** What applying the current ruler session would produce; null when no session is open. */
    fun rulerPreview(): RulerTickPreview? = _state.value.rulerSession?.preview(imageWidthPx, imageHeightPx)

    /** Applies the session through [confirmMultiPointCalibration] and closes it; no-op while the preview cannot apply. */
    fun confirmRulerSession(saveCameraProfile: Boolean = false) {
        if (inputStopped()) return
        val session = _state.value.rulerSession ?: return
        if (session.preview(imageWidthPx, imageHeightPx).canApply.not()) return
        confirmMultiPointCalibration(session.usableTicks(), session.lensMode, session.profile)
        setRulerSession(null)
        returnToolAfterConfirm()
        if (saveCameraProfile) saveCameraProfileFromCalibration()
    }

    /**
     * Learns a camera profile from the calibration just applied and stores it for this camera. Reports the
     * outcome in [AnnotationEditorState.cameraProfileNotice]; nothing is stored when the fit is not good enough.
     */
    fun saveCameraProfileFromCalibration() {
        val st = _state.value
        val calibration = st.calibration
        val store = cameraProfileStore
        val notice = when {
            store == null -> "Camera profiles are unavailable on this device."
            calibration == null -> ProfileRejection.NotLensFitted.message
            else -> CameraProfiles.learn(
                calibration, st.imageAnnotation?.sensorData, imageWidthPx, imageHeightPx,
                id = "cam-" + UuidGenerator.generateV7().takeLast(PROFILE_ID_CHARS), nowMs = Clock.System.now().toEpochMilliseconds(),
            ).fold(
                { it.message },
                { profile ->
                    store.save(profile)
                    "Saved camera profile \"${profile.label}\": it will pre-set lens correction for matching photos."
                },
            )
        }
        _state.update { it.copy(cameraProfileNotice = notice) }
    }

    fun dismissCameraProfileNotice() = _state.update { it.copy(cameraProfileNotice = null) }

    fun cancelRulerSession() = setRulerSession(null)

    /** Warnings and notes for the committed annotation [uuid]; empty when it is unknown, a label, or the image is not calibrated. */
    fun reliabilityOf(uuid: String): MeasurementReliability {
        val st = _state.value
        val annotation = st.committedAnnotations.firstOrNull { it.uuid == uuid }
        val calibration = usableCalibration(st.calibration)
        if (annotation == null || annotation.annotationType == AnnotationType.LABEL || calibration == null) {
            return MeasurementReliability(0.0, emptyList())
        }
        return advise(annotation.normalizedPoints, calibration, st)
    }

    /**
     * What the editor status area reports: the selected measurement, else the latest one, else a
     * calibration-level check. Null when the image is not calibrated.
     */
    fun statusReliability(): MeasurementReliability? {
        val st = _state.value
        val calibration = usableCalibration(st.calibration) ?: return null
        val measured = st.committedAnnotations.filter { it.annotationType != AnnotationType.LABEL }
        val subject = measured.firstOrNull { it.uuid == st.selectedAnnotationUuid } ?: measured.lastOrNull()
        return advise(subject?.normalizedPoints.orEmpty(), calibration, st)
    }

    /** UUIDs of committed measurements whose pill should carry a warning mark. */
    fun warnedAnnotationUuids(): Set<String> =
        _state.value.committedAnnotations.filter { reliabilityOf(it.uuid).hasWarning }.mapTo(mutableSetOf()) { it.uuid }

    private fun usableCalibration(calibration: Calibration?): Calibration? =
        calibration?.takeIf { it.method != CalibrationMethod.NONE && it.pixelsPerMeter > 0.0 }

    private fun advise(points: List<NormalizedPoint>, calibration: Calibration, st: AnnotationEditorState): MeasurementReliability {
        val sensor = st.imageAnnotation?.sensorData
        return MeasurementAdvisor.assess(
            points, calibration, imageWidthPx, imageHeightPx, sensor?.pitchDeg, sensor?.rollDeg, sensor?.focalLength35mmEq,
        )
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
    private fun commitAnnotation(
        points: List<NormalizedPoint>,
        tool: AnnotationTool,
        explicitLabel: String? = null,
    ) {
        // Guard: DISTANCE/GRID_REF lines shorter than 1% of image width are likely accidental taps
        if ((tool == AnnotationTool.DISTANCE || tool == AnnotationTool.GRID_REF) && points.size >= 2) {
            val dx = points[1].x - points[0].x
            val dy = points[1].y - points[0].y
            val normalizedLength = kotlin.math.sqrt(dx * dx + dy * dy)
            if (normalizedLength < 0.005) {
                _state.update { it.copy(inProgressPoints = emptyList()) }
                return
            }
        }

        // GRID_REF: show the calibration dialog instead of committing a measurement annotation.
        if (tool == AnnotationTool.GRID_REF) {
            // The GRID_REF dialog offers no feet-inches entry, so it never inherits that display unit.
            val displayUnit = _state.value.selectedUnit.takeIf { it != MeasurementUnit.FEET_INCHES } ?: MeasurementUnit.METERS
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
        val displayUnit = st.selectedUnit

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
        sessionTouched.getAndUpdate { it + measurement.uuid }

        // Story 9.6: Fire haptic feedback on annotation commit. The callback is injected
        // at construction time; on Android the composable caller wires LocalHapticFeedback
        // (HapticFeedbackType.LongPress). On desktop / in tests this is a no-op.
        onHapticFeedback()

        val toolAfter = nextToolAfterCommit(
            tool, st.stickiness, settings.annotatorAutoReturnToSelect(),
            multiStepSessionOpen = st.rulerSession != null || st.planeSession != null,
        )
        val returned = toolAfter == AnnotationTool.SELECT && tool != AnnotationTool.SELECT
        val knownDistance = st.pendingKnownDistance && tool == AnnotationTool.DISTANCE

        // Optimistic update: add to committed list immediately
        _state.update { currentSt ->
            currentSt.copy(
                pendingKnownDistance = currentSt.pendingKnownDistance && !knownDistance,
                inProgressPoints = emptyList(),
                committedAnnotations = currentSt.committedAnnotations + measurement,
                currentTool = toolAfter,
                selectedAnnotationUuid = if (returned) measurement.uuid else currentSt.selectedAnnotationUuid,
                placement = if (returned) AnnotationPlacementSession() else currentSt.placement,
            )
        }

        persistence.submit(onResult = { result ->
            result.onLeft { err ->
                logger.error("Failed to save annotation: ${err.message}")
                // Roll back optimistic update on failure
                _state.update { currentSt ->
                    currentSt.copy(
                        committedAnnotations = currentSt.committedAnnotations
                            .filter { it.uuid != measurement.uuid },
                        currentTool = nextToolAfterRollback(tool, toolAfter, currentSt.currentTool),
                        selectedAnnotationUuid = currentSt.selectedAnnotationUuid.takeIf { it != measurement.uuid },
                        placementMessage = SAVE_FAILED_MESSAGE,
                    )
                }
            }
        }) { saveRow(measurement) }

        if (knownDistance) {
            knownDistanceUndoDepth = history.undoDepth
            _openValueEditor.tryEmit(measurement.uuid)
        }
    }

    // ── Calibration ───────────────────────────────────────────────────────────

    /**
     * Update the calibration and re-derive ALL committed measurements' [valueMeters].
     *
     * This is critical: if calibration changes (e.g. user marks a reference object),
     * all existing annotations must be recalculated or they display stale values.
     */
    fun updateCalibration(newCalibration: Calibration) {
        if (inputStopped()) return
        if (!newCalibration.pixelsPerMeter.isFinite() || newCalibration.pixelsPerMeter <= 0.0) {
            logger.warn("updateCalibration rejected: invalid pixelsPerMeter=${newCalibration.pixelsPerMeter}")
            return
        }
        commitCalibration(newCalibration)
    }

    /** Applies [newCalibration] without the scale guard, so restoring an uncalibrated photo is possible. */
    private fun commitCalibration(newCalibration: Calibration) {
        val st = _state.value

        // Single-level undo: the snapshot carries constraints and fit inside its geometry.
        if (st.calibration != null && st.calibration.method != CalibrationMethod.NONE) {
            calibrationHistory.getAndUpdate { it.push(CalibrationHistory.Entry(st.calibration, st.committedAnnotations)) }
            _canUndoCalibration.value = true
        }

        val displayUnit = st.selectedUnit

        val rederived = rederive(st.committedAnnotations, newCalibration, displayUnit)

        _state.update {
            it.copy(
                calibration = newCalibration,
                committedAnnotations = rederived,
                imageAnnotation = it.imageAnnotation?.copy(calibration = newCalibration),
            )
        }
        persistImageCalibration()

        // Persist re-derived measurements to the repository
        st.imageAnnotation?.uuid?.let { imageUuid ->
            persistence.submit(onResult = { it.onLeft { err -> logger.error("Failed to persist re-derived measurements: ${err.message}") } }) {
                saveSet(imageUuid, rederived)
            }
        }

        val kept = rederived.count { it.valueSource == ValueSource.USER_OVERRIDE }
        val recalculated = rederived.size - kept
        if (rederived.isNotEmpty()) {
            _calibrationChangeMessage.value = "Scale updated — $recalculated measurement${if (recalculated == 1) "" else "s"} recalculated" +
                if (kept > 0) ", $kept measurement${if (kept == 1) " keeps" else "s keep"} your edited value" else ""
        }
    }

    /** An edited value is the user's word: only computed measurements follow [calibration]. */
    private fun rederive(annotations: List<MeasurementAnnotation>, calibration: Calibration?, unit: MeasurementUnit): List<MeasurementAnnotation> =
        annotations.map { annotation ->
            if (annotation.valueSource == ValueSource.USER_OVERRIDE) {
                annotation
            } else {
                val (newValue, newDisplay) = computeMeasurement(annotation.normalizedPoints, annotation.annotationType.toTool(), calibration, unit)
                annotation.copy(valueMeters = newValue, valueDisplay = newDisplay)
            }
        }

    /** Reverts the calibration and re-derives only computed values, so annotations edited after the change survive. */
    fun undoCalibration() {
        var popped: CalibrationHistory.Entry? = null
        calibrationHistory.getAndUpdate { history ->
            popped = null
            history.pop()?.let { (entry, rest) -> popped = entry; rest } ?: history
        }
        val snapshot = popped ?: return
        _canUndoCalibration.value = calibrationHistory.value.canUndo
        _constraintFitResult.value = null
        _state.update {
            it.copy(
                calibration = snapshot.calibration,
                committedAnnotations = rederive(it.committedAnnotations, snapshot.calibration, it.selectedUnit).map { a -> a.relabeled(it.selectedUnit) },
            )
        }
        _state.update { it.copy(imageAnnotation = it.imageAnnotation?.copy(calibration = snapshot.calibration)) }
        persistImageCalibration()
        _calibrationChangeMessage.value = "Scale restored"
        _state.value.imageAnnotation?.uuid?.let { imageUuid ->
            persistence.submit(onResult = { it.onLeft { err -> logger.error("Failed to restore measurements: ${err.message}") } }) {
                saveSet(imageUuid, _state.value.committedAnnotations)
            }
        }
    }

    /** Saves the image row with the current calibration (scale, lens, geometry) so it survives closing the editor. */
    @OptIn(DirectRepositoryWrite::class)
    private fun persistImageCalibration() {
        val repo = imageAnnotationRepository ?: return
        persistence.submit(onResult = { it.onLeft { err -> logger.error("Failed to persist calibration: ${err.message}") } }) {
            val image = _state.value.imageAnnotation ?: return@submit Unit.right()
            repo.saveImageAnnotation(image)
        }
    }

    fun clearCalibrationMessage() {
        _calibrationChangeMessage.value = null
    }

    fun isCalibrated(): Boolean {
        val cal = _state.value.calibration
        return cal != null && cal.method != CalibrationMethod.NONE && cal.pixelsPerMeter > 0.0
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

    /** Call once when a move gesture starts moving: records one undo step for the whole drag. */
    fun beginAnnotationEdit() {
        if (inputStopped()) return
        pushUndo(_state.value)
        dragUndoDepth = history.undoDepth
    }

    /** Replaces an annotation's points and re-derives its measurement. UI-state only; see [commitAnnotationEdit]. */
    fun updateAnnotationPoints(uuid: String, points: List<NormalizedPoint>) {
        if (inputStopped()) return
        val edited = _state.value.committedAnnotations.firstOrNull { it.uuid == uuid }
        if (edited?.valueSource == ValueSource.USER_OVERRIDE && points.size == edited.normalizedPoints.size) {
            // The edited value stays until the user answers the confirm; only the geometry moves.
            if (_overrideDragConfirm.value == null) _overrideDragConfirm.value = OverrideDragConfirm(uuid, edited.normalizedPoints)
            _state.update { st ->
                st.copy(committedAnnotations = st.committedAnnotations.map { if (it.uuid == uuid) it.copy(normalizedPoints = points) else it })
            }
            return
        }
        _state.update { st ->
            val displayUnit = st.selectedUnit
            st.copy(
                committedAnnotations = st.committedAnnotations.map { a ->
                    if (a.uuid != uuid || points.size != a.normalizedPoints.size) a
                    else {
                        val (meters, display) = computeMeasurement(points, a.annotationType.toTool(), st.calibration, displayUnit)
                        a.copy(normalizedPoints = points, valueMeters = meters, valueDisplay = display)
                    }
                },
            )
        }
    }

    private val _overrideDragConfirm = MutableStateFlow<OverrideDragConfirm?>(null)

    /** Non-null while the user must say whether a dragged edited value should be cleared (Story 3.4). */
    val overrideDragConfirm: StateFlow<OverrideDragConfirm?> = _overrideDragConfirm.asStateFlow()

    /** Answers the drag confirm: clear the override and measure the new length, or put the points back. */
    fun resolveOverrideDrag(clearOverride: Boolean) {
        if (inputStopped()) return
        val confirm = _overrideDragConfirm.value ?: return
        _overrideDragConfirm.value = null
        val st = _state.value
        val tool = st.committedAnnotations.firstOrNull { it.uuid == confirm.uuid }?.annotationType?.toTool() ?: return
        _state.update { cur ->
            val resolved = cur.committedAnnotations.map { a ->
                if (a.uuid != confirm.uuid) a else if (clearOverride) measuredAgain(a, tool, cur) else a.copy(normalizedPoints = confirm.pointsBefore)
            }
            cur.copy(committedAnnotations = resolved)
        }
        if (!clearOverride && history.undoDepth == dragUndoDepth) {
            history.discardLatest()
            _canUndo.value = history.canUndo
        }
        commitAnnotationEdit(confirm.uuid)
    }

    private fun measuredAgain(a: MeasurementAnnotation, tool: AnnotationTool, st: AnnotationEditorState): MeasurementAnnotation {
        val (meters, display) = computeMeasurement(a.normalizedPoints, tool, st.calibration, st.selectedUnit)
        return a.withValue(meters?.let { MeasurementValue.Computed(it) }).copy(valueDisplay = display)
    }

    /** Undo depth right after the drag's undo step was recorded; a declined drag drops that step if still newest. */
    private var dragUndoDepth = -1

    /** Persists the annotation's current state after a move gesture ends. */
    fun commitAnnotationEdit(uuid: String) {
        if (inputStopped()) return
        persistence.submit(onResult = { it.onLeft { err -> logger.error("Failed to save moved annotation $uuid: ${err.message}") } }) {
            val annotation = _state.value.committedAnnotations.firstOrNull { it.uuid == uuid }
            if (annotation == null) Unit.right() else saveRow(annotation)
        }
    }

    /** Re-saves the full committed set so undo/redo of an already-persisted edit reaches the database. */
    private fun persistCommittedSet() {
        persistence.submit(onResult = { it.onLeft { err -> logger.error("Failed to persist undo/redo: ${err.message}") } }) {
            val st = _state.value
            val imageUuid = st.imageAnnotation?.uuid
            if (imageUuid == null) Unit.right() else saveSet(imageUuid, st.committedAnnotations)
        }
    }

    /** Delete the currently selected annotation. */
    fun deleteSelectedAnnotation() {
        if (inputStopped()) return
        val selectedUuid = _state.value.selectedAnnotationUuid ?: return
        deleteAnnotation(selectedUuid)
    }

    /** Delete an annotation by UUID. */
    fun deleteAnnotation(uuid: String) {
        if (inputStopped()) return
        val before = _state.value
        pushUndo(before)

        // Before the state change, so a load landing in between can't re-add the row.
        sessionTouched.getAndUpdate { it + uuid }
        _state.update { currentSt ->
            currentSt.copy(
                committedAnnotations = currentSt.committedAnnotations.filter { it.uuid != uuid },
                sessionDeleted = currentSt.sessionDeleted + uuid,
                selectedAnnotationUuid = if (currentSt.selectedAnnotationUuid == uuid) null else currentSt.selectedAnnotationUuid,
            )
        }

        persistence.submit(onResult = { it.onLeft { err -> logger.error("Failed to delete annotation $uuid: ${err.message}") } }) {
            deleteRow(uuid)
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
        // Reference lines lie in the ruler's plane, so only measurements get the height-offset factor.
        val length = if (tool == AnnotationTool.GRID_REF) 1.0 else heightFactor(calibration)
        return when (tool) {
            AnnotationTool.DISTANCE, AnnotationTool.GRID_REF -> {
                if (points.size < 2) return null to null
                val p1 = points[0]
                val p2 = points[1]
                if (calibration != null && usesLensPath(calibration)) {
                    val lensMeters = LensMeasurement.distanceMeters(p1, p2, calibration, imageWidthPx, imageHeightPx)?.times(length)
                    return lensMeters to lensMeters?.let { metersToDisplayString(it, displayUnit) }
                }
                val dx = (p2.x - p1.x) * imageWidthPx
                val dy = (p2.y - p1.y) * imageHeightPx
                val pixelDist = sqrt(dx * dx + dy * dy)
                if (ppm == 0.0) {
                    null to "${pixelDist.toInt()} px"
                } else {
                    val meters = pixelDist / ppm * length
                    meters to metersToDisplayString(meters, displayUnit)
                }
            }

            AnnotationTool.AREA -> {
                if (points.size < 3) return null to null
                if (ppm == 0.0) {
                    null to "uncalibrated"
                } else {
                    val flatArea = if (calibration != null && usesLensPath(calibration)) {
                        LensMeasurement.areaSquareMeters(points, calibration, imageWidthPx, imageHeightPx) ?: 0.0
                    } else {
                        polygonAreaMeters(points, ppm, imageWidthPx, imageHeightPx)
                    }
                    val area = flatArea * length * length
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

    // ── Plane reference session (paper / card corners) ────────────────────────

    private fun setPlaneSession(session: PlaneSession?) {
        _state.update { it.copy(planeSession = session, inProgressPoints = session?.allPoints().orEmpty()) }
    }

    fun beginPlaneSession() {
        if (inputStopped()) return
        _state.update { it.copy(currentTool = AnnotationTool.GRID_REF, isGridRefDialogVisible = false, pendingGridRefPoints = emptyList(), rulerSession = null) }
        setPlaneSession(PlaneSession())
    }

    fun setPlaneRectangle(rectangle: ReferenceRectangle) = updatePlaneSession { it.copy(rectangle = rectangle) }

    fun setPlaneFirstEdgeLong(long: Boolean) = updatePlaneSession { it.copy(firstEdgeIsLong = long) }

    fun removeLastPlaneCorner() = updatePlaneSession { it.withoutLastCorner() }

    fun cancelPlaneSession() = setPlaneSession(null)

    private fun updatePlaneSession(transform: (PlaneSession) -> PlaneSession) {
        setPlaneSession(transform(_state.value.planeSession ?: return))
    }

    /** What applying the corners tapped so far would do; null when no plane session is open. */
    fun planePreview(): PlanePreview? {
        val session = _state.value.planeSession ?: return null
        return PlaneReference.preview(session, _state.value.calibration?.lens?.k ?: 0.0, imageWidthPx, imageHeightPx)
    }

    /** Applies the plane calibration (keeping any height offset) and closes the session; no-op until it can apply. */
    fun confirmPlaneSession() {
        if (inputStopped()) return
        val calibration = planePreview()?.calibration ?: return
        updateCalibration(keepingGeometry(calibration))
        setPlaneSession(null)
        returnToolAfterConfirm()
    }

    /** Lens-aware and plane-mapped calibrations measure through [LensMeasurement]; the rest use the plain scale. */
    private fun usesLensPath(calibration: Calibration): Boolean =
        calibration.lens != null || calibration.geometry?.plane != null

    private fun heightFactor(calibration: Calibration?): Double = HeightOffset.lengthFactor(
        calibration,
        _state.value.imageAnnotation?.sensorData?.focalLength35mmEq,
        imageWidthPx,
        imageHeightPx,
    )

    /**
     * What a height-offset [draft] would do for the current calibration; null when the image has no
     * usable calibration yet.
     */
    fun heightOffsetPreview(draft: HeightOffsetDraft): HeightOffsetPreview? {
        val st = _state.value
        val calibration = usableScale(st.calibration) ?: return null
        return draft.preview(calibration, st.imageAnnotation?.sensorData?.focalLength35mmEq, imageWidthPx, imageHeightPx)
    }

    /** Saves [geometry] on the calibration (null clears it) and re-derives every measurement. */
    fun setGeometry(geometry: GeometryCalibration?) {
        if (inputStopped()) return
        val calibration = usableScale(_state.value.calibration) ?: return
        updateCalibration(
            ConstraintFitApplier.withHeightOffset(calibration, geometry?.heightOffsetMeters ?: 0.0, geometry?.cameraDistanceMeters),
        )
    }

    private fun usableScale(calibration: Calibration?): Calibration? =
        calibration?.takeIf { it.method != CalibrationMethod.NONE && it.pixelsPerMeter > 0.0 }

    /** A new line or ruler reference keeps the height offset already entered, but replaces plane, constraints and fit. */
    private fun keepingGeometry(calibration: Calibration): Calibration =
        ConstraintFitApplier.replacingBasis(_state.value.calibration, calibration)

    // ── Value edit and known distances (ADR-002, ADR-003) ─────────────────────

    /**
     * Applies a typed distance to [uuid]: [ValueEditMode.LABEL_ONLY] overrides the shown value only, while
     * [ValueEditMode.KNOWN_DISTANCE] promotes the line to a known distance and re-fits the scale.
     * Invalid text returns Left and changes nothing.
     */
    fun submitValueEdit(
        uuid: String,
        text: String,
        mode: ValueEditMode,
        entryUnit: MeasurementUnit,
    ): Either<MeasurementValueEdit.Error, Unit> {
        if (inputStopped()) return Unit.right()
        val target = _state.value.committedAnnotations.firstOrNull { it.uuid == uuid && it.annotationType == AnnotationType.DISTANCE }
        if (target == null) {
            logger.warn("submitValueEdit ignored: no distance $uuid")
            return Unit.right()
        }
        val meters = MeasurementValueEdit.parse(text, entryUnit).fold({ return it.left() }, { it })
        when (mode) {
            ValueEditMode.LABEL_ONLY -> overrideValue(target, meters)
            ValueEditMode.KNOWN_DISTANCE -> promoteToKnownDistance(target, meters)
        }
        return Unit.right()
    }

    private fun overrideValue(target: MeasurementAnnotation, meters: Double) {
        val st = _state.value
        val updated = MeasurementValueEdit.apply(target, meters, st.selectedUnit)
        pushUndo(st)
        sessionTouched.getAndUpdate { it + target.uuid }
        replaceAnnotation(updated)
        persistence.submit(onResult = { result ->
            result.onLeft { err ->
                logger.error("Failed to save edited value: ${err.message}")
                _state.update { cur ->
                    cur.copy(
                        committedAnnotations = cur.committedAnnotations.map { if (it.uuid == target.uuid) target else it },
                        placementMessage = SAVE_FAILED_MESSAGE,
                    )
                }
            }
        }) { saveRow(updated) }
    }

    private fun replaceAnnotation(updated: MeasurementAnnotation) {
        _state.update { cur -> cur.copy(committedAnnotations = cur.committedAnnotations.map { if (it.uuid == updated.uuid) updated else it }) }
    }

    private fun promoteToKnownDistance(target: MeasurementAnnotation, meters: Double) {
        val points = target.normalizedPoints
        if (points.size < 2) return
        val constraint = CalibrationConstraint(
            id = UuidGenerator.generateV7(),
            p1 = points[0],
            p2 = points[1],
            distanceMeters = meters,
            source = ConstraintSource.FROM_MEASUREMENT,
            measurementUuid = target.uuid,
        )
        val others = _state.value.calibration?.geometry?.constraints.orEmpty().filterNot { it.measurementUuid == target.uuid }
        confirmConstraintCalibration(others + constraint)
    }

    private val _constraintFitResult = MutableStateFlow<ConstraintFitResult?>(null)

    /** The last fit's report and, when a requested tier was not used, why; null before any fit. */
    val constraintFitResult: StateFlow<ConstraintFitResult?> = _constraintFitResult.asStateFlow()

    /**
     * Fits [constraints] (the full list after an add, rename or delete) and writes the result through
     * [ConstraintFitApplier]. An empty list restores the basis the first fit replaced. A failed richer
     * tier falls back to SCALE, so measuring is never blocked.
     */
    fun confirmConstraintCalibration(constraints: List<CalibrationConstraint>, request: TierRequest = TierRequest.Auto) {
        if (inputStopped()) return
        val current = _state.value.calibration
        if (constraints.isEmpty()) {
            _constraintFitResult.value = null
            current?.let { commitCalibration(ConstraintFitApplier.restorePriorBasis(it)) }
            return
        }
        val calibrated = current?.takeIf { it.method != CalibrationMethod.NONE }
        val hints = captureHints
        val focal = FocalSourceResolver.resolve(
            userConfirmed35mmEq = hints.userConfirmedFocal35mmEq,
            lensRecord = hints.lensRecord,
            profileMatched = false,
            exifFocal35mmEq = _state.value.imageAnnotation?.sensorData?.focalLength35mmEq,
        )
        val frame = CaptureFrameInfo(
            imageWidthPx, imageHeightPx, focal.focal35mmEq,
            zoomRatio = hints.zoomRatio ?: hints.lensRecord?.zoomRatio,
            exifDigitalZoomRatio = hints.exifDigitalZoomRatio,
        )
        val options = FitOptions(
            request = request,
            focalSource = focal.source,
            focal35mmEq = focal.focal35mmEq,
            gradientEnabled = gradientTierEnabled(),
            k = current?.lens?.k ?: 0.0,
            basisVersion = calibrated?.basisVersion ?: CalibrationBasis.NATIVE_PIXELS_LENS,
        )
        val response = ConstraintCalibrator.fit(constraints, frame, options)
        when (val outcome = ConstraintFitApplier.apply(current, constraints, response, frame)) {
            is ConstraintFitApplier.Outcome.Declined -> {
                _constraintFitResult.value = ConstraintFitResult(null, outcome.failure.describe())
                logger.warn("calibration fit declined: ${outcome.failure}")
            }
            is ConstraintFitApplier.Outcome.Applied -> {
                _constraintFitResult.value = ConstraintFitResult(response.effective?.report, outcome.fallback?.describe())
                releaseOverridesOfKnownDistances(constraints)
                commitCalibration(outcome.calibration)
                logger.info("calibration fit tier=${outcome.record.tier} n=${outcome.record.n}")
            }
        }
    }

    /** A line promoted to a known distance must follow the fit, so a number typed earlier no longer overrides it. */
    private fun releaseOverridesOfKnownDistances(constraints: List<CalibrationConstraint>) {
        val sources = constraints.mapNotNull { it.measurementUuid }.toSet()
        if (sources.isEmpty()) return
        _state.update { cur ->
            cur.copy(
                committedAnnotations = cur.committedAnnotations.map { a ->
                    val meters = a.valueMeters
                    if (a.uuid in sources && a.valueSource == ValueSource.USER_OVERRIDE && meters != null) a.withValue(MeasurementValue.Computed(meters)) else a
                },
            )
        }
    }

    /** What the host knows about how the photo was captured; unset values mean "unknown", never "uncropped". */
    data class CaptureHints(
        val zoomRatio: Double? = null,
        val exifDigitalZoomRatio: Double? = null,
        val lensRecord: LensRecordFocal? = null,
        val userConfirmedFocal35mmEq: Double? = null,
    )

    @kotlin.concurrent.Volatile private var captureHints = CaptureHints()

    /** Feeds the crop/zoom refusal and the focal source ranking of the next known-distance fit. */
    fun setCaptureHints(hints: CaptureHints) {
        captureHints = hints
    }

    internal val frameWidthPx: Double get() = imageWidthPx
    internal val frameHeightPx: Double get() = imageHeightPx

    internal fun gradientTierEnabled(): Boolean {
        val key = ConstraintTuning.SETTINGS_KEY_GRADIENT_TIER
        val stored = settings?.takeIf { it.containsKey(key) }?.getBoolean(key, ConstraintTuning.GRADIENT_DEFAULT_ON)
        return ConstraintTuning.gradientEnabled(stored)
    }

    private val _openValueEditor = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits the uuid of a line drawn for "Draw a known distance" so the host opens its value editor. */
    val openValueEditor: SharedFlow<String> = _openValueEditor.asSharedFlow()

    /** Undo depth right after the known-distance line was committed; cancel drops that step while it is newest. */
    private var knownDistanceUndoDepth = -1

    /** "Draw a known distance": arms the distance tool; the next committed line opens the value editor once. */
    fun beginKnownDistance() {
        if (inputStopped()) return
        selectTool(AnnotationTool.DISTANCE)
        _state.update { it.copy(pendingKnownDistance = true) }
    }

    /** Cancel in the editor: the drawn line goes away and leaves no undo entry. */
    fun cancelKnownDistance(uuid: String) {
        if (inputStopped()) return
        if (history.undoDepth == knownDistanceUndoDepth) {
            history.discardLatest()
            _canUndo.value = history.canUndo
        }
        knownDistanceUndoDepth = -1
        sessionTouched.getAndUpdate { it + uuid }
        _state.update { cur ->
            cur.copy(
                committedAnnotations = cur.committedAnnotations.filter { it.uuid != uuid },
                selectedAnnotationUuid = cur.selectedAnnotationUuid.takeIf { it != uuid },
            )
        }
        persistence.submit(onResult = { it.onLeft { err -> logger.error("Failed to delete cancelled known distance $uuid: ${err.message}") } }) {
            deleteRow(uuid)
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
        if (inputStopped()) return
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
        if (inputStopped()) return
        val current = _state.value.imageAnnotation ?: return
        if (tag !in current.tags) return
        val updated = current.copy(tags = current.tags - tag)
        _state.update { it.copy(imageAnnotation = updated) }
        persistTagsUpdate(updated)
    }

    /**
     * Switch the unit every measurement is labelled in, save it on the image so reopening restores
     * it, and remember it as the default for new annotations. Labels change immediately; saving is
     * best-effort and only logs on failure. Not an undoable step: undo/redo keep the chosen unit.
     */
    fun setDisplayUnit(unit: MeasurementUnit) {
        if (inputStopped()) return
        val before = _state.value
        val image = before.imageAnnotation ?: return
        if (unit == before.selectedUnit && unit == image.unit) return
        val after = _state.updateAndGet { st ->
            st.copy(
                selectedUnit = unit,
                imageAnnotation = st.imageAnnotation?.copy(unit = unit),
                committedAnnotations = st.committedAnnotations.map { it.relabeled(unit) },
            )
        }
        settings?.setDefaultMeasurementUnit(unit)
        val relabeled = after.committedAnnotations.filter { a ->
            before.committedAnnotations.firstOrNull { it.uuid == a.uuid }?.valueDisplay != a.valueDisplay
        }
        persistUnitChange(relabeled)
    }

    /**
     * Saves the latest image row (so a racing tag edit is not clobbered) and each re-labelled
     * measurement individually. Upserts, never a whole-set replace: the initial load may not have
     * finished, and replacing the set would delete measurements that are stored but not yet loaded.
     */
    @OptIn(DirectRepositoryWrite::class)
    private fun persistUnitChange(relabeled: List<MeasurementAnnotation>) {
        persistence.submit {
            _state.value.imageAnnotation?.let { image ->
                imageAnnotationRepository?.saveImageAnnotation(image)?.onLeft { err ->
                    logger.error("Failed to persist unit change: ${err.message}")
                }
            }
            relabeled.forEach { m ->
                saveRow(m).onLeft { err ->
                    logger.error("Failed to persist re-labelled measurement ${m.uuid}: ${err.message}")
                }
            }
            Unit.right()
        }
    }

    @OptIn(DirectRepositoryWrite::class)
    private fun persistTagsUpdate(updated: ImageAnnotation) {
        val repo = imageAnnotationRepository ?: return
        persistence.submit(onResult = { it.onLeft { err -> logger.error("Failed to persist tag update: ${err.message}") } }) {
            repo.saveImageAnnotation(updated)
        }
    }

    // ── External measurement device (Story 5.7) ──────────────────────────────

    /** Currently active external measurement device (BLE laser, keyboard, USB). */
    private val _activeDevice = Atomic<ExternalMeasurementDevice?>(null)

    /**
     * Connection state of the active device, or DISCONNECTED when no device is set.
     * Sourced directly from the device's [ExternalMeasurementDevice.connectionState].
     */
    val deviceConnectionState: StateFlow<DeviceConnectionState>
        get() = _activeDevice.value?.connectionState ?: _noDeviceConnectionState

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
        _activeDevice.value = device
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
        if (inputStopped()) return
        val device = _activeDevice.value ?: return
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
        _state.update { it.copy(stickiness = initialStickiness) }
        depthCoordinator.close()
        persistence.close()
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
        MeasurementUnit.FEET_INCHES -> value * 0.3048
    }
}

private const val PROFILE_ID_CHARS = 8
private const val CANCELLED_MESSAGE = "Cancelled"
private const val SAVE_FAILED_MESSAGE = "Couldn't save. Try again"
private const val IDENTICAL_POINTS_MESSAGE = "Choose a second point away from the first"
