package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.graphics.ImageBitmap
import arrow.core.Either
import dev.stapler.stelekit.calibration.CalibrationService
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.platform.ml.MonocularDepthEstimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Manages monocular depth estimation state and inference lifecycle.
 *
 * Extracted from [AnnotationEditorViewModel] to keep the ViewModel focused on annotation
 * editing concerns. This coordinator owns:
 * - [DepthModelUiState] — download/readiness of the model file
 * - Inference running flag, depth map result, and error message
 *
 * CRITICAL: owns its own [CoroutineScope] — never accepts an externally supplied scope.
 *
 * @param depthEstimator          ML depth estimator (injected — platform implementation on Android,
 *                                [dev.stapler.stelekit.platform.ml.NoOpMonocularDepthEstimator] elsewhere).
 * @param imageWidthPx            Native image width needed for calibration computation.
 * @param imageHeightPx           Native image height needed for calibration computation.
 * @param onCalibrationReady      Callback invoked with a new [Calibration] when depth estimation
 *                                produces a usable calibration at [tapPoint].
 */
class DepthEstimationCoordinator(
    private val depthEstimator: MonocularDepthEstimator,
    private val imageWidthPx: Double,
    private val imageHeightPx: Double,
    private val onCalibrationReady: (Calibration) -> Unit,
) {
    // CRITICAL: internal scope — never injected from outside composition.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(DepthCoordinatorState())
    val state: StateFlow<DepthCoordinatorState> = _state.asStateFlow()

    /**
     * Push the current [DepthModelUiState] from the platform layer into this coordinator's state.
     *
     * Called from the Android entry point (or a `LaunchedEffect`) when
     * `DepthModelDownloader.modelState` emits a new value.
     */
    fun updateDepthModelUiState(uiState: DepthModelUiState) {
        _state.update { it.copy(depthModelUiState = uiState) }
    }

    /**
     * Run monocular depth estimation on [imageBitmap] via the injected [depthEstimator].
     *
     * Flow:
     * 1. Calls [MonocularDepthEstimator.initialize] (idempotent after first success).
     * 2. Runs [MonocularDepthEstimator.estimateDepth] on the internal [Dispatchers.Default] scope.
     * 3. On success: stores the depth map in [DepthCoordinatorState.depthMap] and invokes
     *    [onCalibrationReady] with a [dev.stapler.stelekit.model.CalibrationMethod.MONOCULAR_ML]
     *    calibration if [tapPoint] is provided.
     * 4. On failure: stores the error message in [DepthCoordinatorState.depthEstimationError].
     *
     * ADR-005: confidence is 15%. The UI must show the low-confidence warning.
     *
     * @param imageBitmap   the image to estimate depth for
     * @param tapPoint      optional normalized tap point used to sample depth for calibration
     */
    fun runDepthEstimation(imageBitmap: ImageBitmap, tapPoint: NormalizedPoint? = null) {
        if (_state.value.isDepthInferenceRunning) return
        _state.update { it.copy(isDepthInferenceRunning = true, depthEstimationError = null) }

        scope.launch {
            // Initialize estimator (no-op if already done).
            val initResult = depthEstimator.initialize()
            if (initResult.isLeft()) {
                val err = (initResult as Either.Left).value.message
                _state.update { it.copy(isDepthInferenceRunning = false, depthEstimationError = err) }
                return@launch
            }

            val depthResult = depthEstimator.estimateDepth(imageBitmap)
            depthResult.fold(
                ifLeft = { err ->
                    _state.update {
                        it.copy(isDepthInferenceRunning = false, depthEstimationError = err.message)
                    }
                },
                ifRight = { depthMap ->
                    _state.update {
                        it.copy(isDepthInferenceRunning = false, depthMap = depthMap, depthEstimationError = null)
                    }
                    // Auto-apply calibration if a tap point is provided.
                    if (tapPoint != null && imageWidthPx > 0.0 && imageHeightPx > 0.0) {
                        val cal = CalibrationService.computeFromMLDepth(
                            depthMap = depthMap,
                            tapPointNormalized = tapPoint,
                            imageWidthPx = imageWidthPx,
                            imageHeightPx = imageHeightPx,
                        )
                        if (cal != null) onCalibrationReady(cal)
                    }
                },
            )
        }
    }

    /**
     * Cancel the internal [CoroutineScope]. Call when the editor is permanently dismissed.
     */
    fun close() {
        scope.cancel()
    }
}

/**
 * Immutable state snapshot for [DepthEstimationCoordinator].
 */
data class DepthCoordinatorState(
    /** True while ONNX inference is running (shows spinner). */
    val isDepthInferenceRunning: Boolean = false,
    /** Non-null after successful depth estimation. Relative depth map, normalized [0,1]. */
    val depthMap: FloatArray? = null,
    /** Error message from the most recent depth estimation attempt. Null when none. */
    val depthEstimationError: String? = null,
    /** Current download/readiness state of the depth model file. */
    val depthModelUiState: DepthModelUiState = DepthModelUiState.Absent,
) {
    // FloatArray does not implement structural equality, so we override here.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DepthCoordinatorState) return false
        return isDepthInferenceRunning == other.isDepthInferenceRunning &&
            depthMap.contentEquals(other.depthMap) &&
            depthEstimationError == other.depthEstimationError &&
            depthModelUiState == other.depthModelUiState
    }

    override fun hashCode(): Int {
        var result = isDepthInferenceRunning.hashCode()
        result = 31 * result + (depthMap?.contentHashCode() ?: 0)
        result = 31 * result + (depthEstimationError?.hashCode() ?: 0)
        result = 31 * result + depthModelUiState.hashCode()
        return result
    }
}

private fun FloatArray?.contentEquals(other: FloatArray?): Boolean {
    if (this === other) return true
    if (this == null || other == null) return false
    return this.contentEquals(other)
}
