package dev.stapler.stelekit.platform.ml

import arrow.core.Either
import arrow.core.left
import androidx.compose.ui.graphics.ImageBitmap
import dev.stapler.stelekit.error.DomainError

/**
 * Abstraction over monocular depth estimation via ML (Depth Anything V2 via ONNX Runtime).
 *
 * Platform implementations:
 * - Android: [OnnxMonocularDepthEstimator] (stub — requires ONNX Runtime and model asset)
 * - iOS: stub returning [DomainError.SensorError.HardwareUnavailable] (Core ML in Epic 8)
 * - JVM / WASM: [NoOpMonocularDepthEstimator] (always unavailable)
 *
 * Register the platform implementation in [SensorModule] at app startup.
 *
 * IMPORTANT: Inference is on-demand only, never real-time. Typical latency is
 * 500–800 ms on Snapdragon 695. Always display a loading indicator.
 */
interface MonocularDepthEstimator {

    /**
     * Whether the depth estimator is available on this device.
     *
     * `false` on JVM, WASM, and Android devices without sufficient memory or
     * supported API level (requires API 26+, ≥3 GB RAM).
     */
    val isAvailable: Boolean

    /**
     * Load and initialize the ML model.
     *
     * Must be called before [estimateDepth]. Safe to call multiple times — subsequent
     * calls are no-ops if already initialized.
     *
     * Returns [DomainError.SensorError.HardwareUnavailable] if the model cannot be loaded
     * (memory gate, unsupported ops, corrupt asset, etc.).
     */
    suspend fun initialize(): Either<DomainError, Unit>

    /**
     * Run depth estimation on the provided [imageBitmap].
     *
     * Returns a flat [FloatArray] of estimated depth values in meters (relative scale),
     * in row-major order with the same dimensions as [imageBitmap].
     *
     * IMPORTANT: Values are RELATIVE, not absolute. They must be scaled using a known
     * reference distance before use in [CalibrationService.computeFromMLDepth].
     */
    suspend fun estimateDepth(imageBitmap: ImageBitmap): Either<DomainError, FloatArray>
}

/**
 * No-op implementation for platforms without ML depth support.
 *
 * Always returns [DomainError.SensorError.HardwareUnavailable].
 */
class NoOpMonocularDepthEstimator : MonocularDepthEstimator {
    override val isAvailable: Boolean = false

    override suspend fun initialize(): Either<DomainError, Unit> =
        DomainError.SensorError.HardwareUnavailable("MonocularDepthEstimator").left()

    override suspend fun estimateDepth(imageBitmap: ImageBitmap): Either<DomainError, FloatArray> =
        DomainError.SensorError.HardwareUnavailable("MonocularDepthEstimator").left()
}
