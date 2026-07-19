package dev.stapler.stelekit.platform.sensor

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over a continuous camera luminance-frame stream, e.g. for QR code decode.
 *
 * Deliberately separate from [CameraProvider] (ISP, see ADR-002): [CameraProvider] is
 * single-shot photo capture with a bind-capture-unbind lifecycle, while this interface is a
 * bind-once-keep-running stream. Coupling them would force every [CameraProvider] implementor
 * (including photo-only platforms) to depend on a streaming method they never use.
 *
 * Each platform provides its own implementation:
 * - Android: [dev.stapler.stelekit.platform.sensor.AndroidCameraFrameSource] (CameraX `ImageAnalysis`)
 * - JVM desktop / WASM / iOS (until implemented): [NoOpCameraFrameSource]
 *
 * Use [SensorModule] to obtain the platform-appropriate instance.
 */
interface CameraFrameSource {

    /**
     * Whether the platform has a usable streaming camera.
     *
     * Check this before entering a scanning UI state.
     */
    val isAvailable: Boolean

    /**
     * A cold [Flow] of decoded camera frames. Collection starts capture; cancellation stops it.
     *
     * Returns [DomainError.SensorError.PermissionDenied] if camera permission is missing, or
     * [DomainError.SensorError.HardwareUnavailable] if no streaming camera is present.
     */
    fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>>
}
