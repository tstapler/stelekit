package dev.stapler.stelekit.platform.ml

import arrow.core.Either
import arrow.core.left
import androidx.compose.ui.graphics.ImageBitmap
import dev.stapler.stelekit.error.DomainError

/**
 * ONNX Runtime implementation of [MonocularDepthEstimator] for Android.
 *
 * ARCHITECTURE STUB — full inference requires the ONNX Runtime dependency and the
 * Depth Anything V2 ViT-S model asset. Neither is added here because:
 *   - ONNX Runtime AAR (`com.microsoft.onnxruntime:onnxruntime-android`) is ~60 MB
 *   - The ViT-S model (~200 MB ONNX) cannot be checked into source control
 *
 * To enable real monocular depth inference:
 * 1. Add `implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")` to
 *    androidMain dependencies in build.gradle.kts.
 * 2. Download `depth_anything_v2_vits.onnx` from `fabio-sim/Depth-Anything-ONNX` and
 *    place it in `androidMain/assets/models/`.
 * 3. Gate model load on API 26+ AND `ActivityManager.MemoryInfo.totalMem >= 3 GB`.
 * 4. Replace the stub bodies below with real `OrtEnvironment` / `OrtSession` calls.
 * 5. All ORT calls must be wrapped in `try { } catch (e: Exception)` — ORT may throw
 *    `OrtException` or `UnsatisfiedLinkError` on unsupported devices.
 *
 * Current behaviour: always returns [DomainError.SensorError.HardwareUnavailable] so the
 * [dev.stapler.stelekit.calibration.CalibrationFallbackChain] skips gracefully.
 *
 * ADR-005 note: ML depth confidence is 15% (±15%). Always show "Low confidence —
 * verify with reference object" warning when this method is active.
 */
class OnnxMonocularDepthEstimator : MonocularDepthEstimator {

    /**
     * Returns false until ONNX Runtime is added and model is loaded.
     *
     * When implementing, replace with:
     * ```kotlin
     * override val isAvailable: Boolean get() = Build.VERSION.SDK_INT >= 26 &&
     *     getSystemService(ActivityManager::class.java)
     *         .let { am -> ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem >= 3L * 1024 * 1024 * 1024 }
     * ```
     */
    override val isAvailable: Boolean = false

    override suspend fun initialize(): Either<DomainError, Unit> =
        DomainError.SensorError.HardwareUnavailable(
            "OnnxMonocularDepthEstimator — ONNX Runtime not linked",
        ).left()

    override suspend fun estimateDepth(imageBitmap: ImageBitmap): Either<DomainError, FloatArray> =
        DomainError.SensorError.HardwareUnavailable(
            "OnnxMonocularDepthEstimator — ONNX Runtime not linked",
        ).left()
}
