package dev.stapler.stelekit.platform.sensor

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError

/**
 * Desktop JVM camera provider stub.
 *
 * Live webcam capture (via JavaCV or similar) is out of scope for v1.
 * This stub falls back to [DesktopFilePicker] for all capture requests,
 * allowing desktop users to import local image files via the same UI entry-point
 * that would normally trigger the camera.
 *
 * To enable real webcam capture in a future story, inject a JavaCV-backed
 * implementation and replace the [capturePhoto] body.
 */
class WebcamCameraProvider : CameraProvider {

    /**
     * `false` — desktop v1 uses file picker instead of live webcam capture.
     *
     * A future version may set this to `true` when JavaCV is available and a webcam
     * device is detected at startup.
     */
    override val isAvailable: Boolean = false

    /**
     * Falls back to [DesktopFilePicker] for desktop platforms.
     *
     * The plan calls for file-picker fallback when real webcam capture is not available.
     * This delegates to [DesktopFilePicker.pickImageFile] which shows a JFileChooser.
     */
    override suspend fun capturePhoto(): Either<DomainError.SensorError, PlatformImageFile> =
        DesktopFilePicker.pickImageFile()
}
