package dev.stapler.stelekit.platform.sensor

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError

/**
 * Android camera provider using CameraX.
 *
 * This is a stub implementation. The full CameraX live-preview capture flow
 * (Activity-level permission request + ImageCapture use-case + EXIF correction
 * via [ExifOrientationFixer]) will be completed when CameraX is added to
 * androidMain dependencies in kmp/build.gradle.kts.
 *
 * For Android image import, use [AndroidPhotoPickerLauncher] instead — it does not
 * require the CAMERA permission and works on API 21+.
 *
 * Dependency gate: CameraX is NOT yet in kmp/build.gradle.kts.
 * Until added, this class returns [DomainError.SensorError.HardwareUnavailable].
 *
 * ## OOM prevention (Story 9.2) — inSampleSize for preview loading
 *
 * Modern Android cameras produce 12–200 MP images (12–48 MB decoded ARGB_8888).
 * Loading the full bitmap into memory for display would immediately OOM on most devices.
 *
 * The annotation canvas uses Coil's [coil3.compose.AsyncImage], which automatically
 * applies [android.graphics.BitmapFactory.Options.inSampleSize] to decode the image
 * at the viewport's display resolution rather than the sensor's full resolution.
 * The full-resolution file is retained on disk for export and sidecar metadata.
 *
 * When a full CameraX capture implementation is added, the recommended approach is:
 *  1. Save the full-resolution capture to disk via [ImageCapture.takePicture] (file path).
 *  2. Return the file path in [PlatformImageFile.path] — Coil handles display subsampling.
 *  3. Do NOT decode the full bitmap into memory in this class; let Coil subsample it.
 *
 * Example inSampleSize calculation (for any code that needs raw bitmap access,
 * e.g. EXIF correction in [ExifOrientationFixer]):
 * ```kotlin
 * val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
 * BitmapFactory.decodeFile(path, opts)
 * opts.inSampleSize = calculateInSampleSize(opts, targetWidth = 1920, targetHeight = 1080)
 * opts.inJustDecodeBounds = false
 * val bitmap = BitmapFactory.decodeFile(path, opts)
 * // bitmap is now at most 1920×1080 — safe to keep in memory
 * ```
 * where `calculateInSampleSize` rounds down to the nearest power-of-two factor.
 *
 * Story 8.1.5: When a full capture implementation is added, it must snapshot
 * [SensorModule.motionSensorProvider.sensorDataFlow] at the moment of shutter
 * and attach the result to the returned [PlatformImageFile.sensorData]. This
 * ensures GPS, bearing, and tilt data captured at the exact capture instant are
 * preserved through the import pipeline.
 *
 * Example pattern for the full implementation:
 * ```kotlin
 * val sensorSnapshot = SensorModule.motionSensorProvider.sensorDataFlow
 *     .firstOrNull()  // latest emission; null if sensing not started
 * return PlatformImageFile(
 *     path = savedImagePath,
 *     capturedAtMs = System.currentTimeMillis(),
 *     sensorData = sensorSnapshot,
 * ).right()
 * ```
 */
class AndroidCameraProvider : CameraProvider {

    override val isAvailable: Boolean = false

    /**
     * Stub: returns [DomainError.SensorError.HardwareUnavailable] until CameraX
     * is wired in and the CAMERA permission is requested.
     *
     * To enable: add `implementation("androidx.camera:camera-camera2:1.5.0")` and
     * `implementation("androidx.camera:camera-lifecycle:1.5.0")` to androidMain
     * dependencies, then replace this body with a real ImageCapture flow.
     */
    override suspend fun capturePhoto(): Either<DomainError.SensorError, PlatformImageFile> =
        DomainError.SensorError.HardwareUnavailable("camera").left()
}
