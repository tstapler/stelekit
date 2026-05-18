package dev.stapler.stelekit.platform.sensor

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError

/**
 * Abstraction over the device camera for image capture.
 *
 * Each platform provides its own implementation:
 * - Android: [AndroidCameraProvider] (CameraX)
 * - iOS: [IOSCameraProvider] (AVFoundation via CameraK)
 * - JVM desktop: [NoOpCameraProvider] (import-only; no live capture)
 * - WASM: [NoOpCameraProvider] (stub)
 *
 * Use [SensorModule] to obtain the platform-appropriate instance.
 */
interface CameraProvider {

    /**
     * Whether the platform has a usable camera.
     *
     * `false` on JVM desktop and WASM. Check this before displaying camera UI.
     */
    val isAvailable: Boolean

    /**
     * Capture a single photo and return a [PlatformImageFile] pointing to the saved bytes.
     *
     * The returned [PlatformImageFile.path] is a stable local file path that can be passed
     * directly to [dev.stapler.stelekit.db.ImageImportService.import].
     *
     * On Android, EXIF orientation is corrected before this method returns.
     * The caller must not assume any particular orientation state for other platforms.
     *
     * Returns [DomainError.SensorError.PermissionDenied] if the camera permission is missing.
     * Returns [DomainError.SensorError.HardwareUnavailable] if no camera is present.
     * Returns [DomainError.SensorError.CaptureFailed] for other capture errors.
     */
    suspend fun capturePhoto(): Either<DomainError.SensorError, PlatformImageFile>
}
