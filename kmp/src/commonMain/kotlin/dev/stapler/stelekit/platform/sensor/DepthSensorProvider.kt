package dev.stapler.stelekit.platform.sensor

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.calibration.DepthFrame
import dev.stapler.stelekit.error.DomainError

/**
 * Abstraction over hardware depth sensing.
 *
 * Platform implementations:
 * - Android: [ARCoreDepthProvider] (ARCore Depth API, ±8–10 cm)
 * - iOS: [IOSLidarDepthProvider] (ARKit LiDAR, full impl in Epic 8)
 * - JVM desktop / WASM: [NoOpDepthProvider] (always returns null)
 *
 * Register the platform implementation in [SensorModule] at app startup.
 */
interface DepthSensorProvider {

    /**
     * Whether the platform supports depth sensing hardware.
     *
     * `false` on JVM, WASM, and devices without ARCore or LiDAR.
     * Check this before presenting depth-calibration UI to the user.
     */
    val isAvailable: Boolean

    /**
     * Acquire a single depth frame at the current moment.
     *
     * Returns:
     * - `Right(DepthFrame)` on success
     * - `Right(null)` when hardware is available but no frame is ready (e.g. insufficient
     *   lighting or ARCore is still initializing)
     * - `Left(DomainError.SensorError.HardwareUnavailable)` when the device lacks the
     *   required hardware or the ARCore session is not in depth mode
     * - `Left(DomainError.SensorError.PermissionDenied)` when camera permission is missing
     * - `Left(DomainError.SensorError.CaptureFailed)` for other transient errors
     */
    suspend fun acquireDepthFrame(): Either<DomainError.SensorError, DepthFrame?>
}

/**
 * No-op implementation used on JVM desktop and WASM targets where no depth sensor exists.
 *
 * Always returns `null.right()` — the [CalibrationFallbackChain] will skip to the next
 * available calibration method.
 */
class NoOpDepthProvider : DepthSensorProvider {
    override val isAvailable: Boolean = false

    override suspend fun acquireDepthFrame(): Either<DomainError.SensorError, DepthFrame?> =
        null.right()
}
