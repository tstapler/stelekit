package dev.stapler.stelekit.platform.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ImageSensorData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android camera provider using CameraX.
 *
 * Requires the CAMERA runtime permission — checked in [capturePhoto] before binding.
 * Uses [ProcessLifecycleOwner] so no Activity reference is needed.
 *
 * ## OOM prevention (Story 9.2) — inSampleSize for preview loading
 *
 * Full-resolution JPEG is written to `cacheDir/captures/<uuid>.jpg`. Display is handled
 * by Coil's [coil3.compose.AsyncImage], which subsamples to the viewport resolution
 * automatically via [android.graphics.BitmapFactory.Options.inSampleSize].
 * This class never decodes the full bitmap into memory.
 *
 * ## EXIF correction
 *
 * After capture, [ExifOrientationFixer.fixOrientation] rotates pixel data to bake in the
 * EXIF orientation and resets the orientation tag to NORMAL. Camera metadata
 * (focal length, make, model) is extracted from EXIF at that point.
 *
 * ## Sensor data (Story 8.1.5)
 *
 * A snapshot of [SensorModule.motionSensorProvider.sensorDataFlow] is captured at shutter
 * time and attached to the returned [PlatformImageFile.sensorData].
 */
class AndroidCameraProvider(private val context: Context) : CameraProvider {

    override val isAvailable: Boolean = true

    /**
     * Captures a single JPEG photo using CameraX [ImageCapture].
     *
     * Returns [DomainError.SensorError.PermissionDenied] if CAMERA permission is missing.
     * Returns [DomainError.SensorError.CaptureFailed] for any capture or I/O error.
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun capturePhoto(): Either<DomainError.SensorError, PlatformImageFile> {
        // 1. Permission gate
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return DomainError.SensorError.PermissionDenied("camera").left()
        }

        return try {
            // 2. Obtain ProcessCameraProvider — bridge ListenableFuture to suspend
            val cameraProvider: ProcessCameraProvider = suspendCancellableCoroutine { cont ->
                val future = ProcessCameraProvider.getInstance(context)
                val executor = ContextCompat.getMainExecutor(context)
                future.addListener(
                    {
                        if (cont.isActive) {
                            runCatching { future.get() }
                                .onSuccess { cont.resume(it) }
                                .onFailure { cont.resumeWithException(it) }
                        }
                    },
                    executor,
                )
                cont.invokeOnCancellation { future.cancel(true) }
            }

            // 3. Build ImageCapture use case
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            // 4. Bind to ProcessLifecycleOwner — no Activity reference required.
            // Use fully-qualified class to avoid the Glance bindToLifecycle extension clash.
            val lifecycleOwner = androidx.lifecycle.ProcessLifecycleOwner.get()
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                imageCapture,
            )

            // 5. Prepare output file: cacheDir/captures/<uuid>.jpg
            val capturesDir = File(context.cacheDir, "captures").also { it.mkdirs() }
            val outputFile = File(capturesDir, "${UUID.randomUUID()}.jpg")

            // 6. Snapshot sensor data at shutter time (Story 8.1.5)
            val sensorSnapshot = SensorModule.motionSensorProvider.sensorDataFlow.firstOrNull()
            val capturedAt = System.currentTimeMillis()

            // 7. Take the photo — bridge ImageCapture callback to a suspend function
            val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
            val executor = Executors.newSingleThreadExecutor()

            suspendCancellableCoroutine { cont ->
                imageCapture.takePicture(
                    outputOptions,
                    executor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            if (cont.isActive) cont.resumeWithException(exception)
                        }
                    },
                )
                cont.invokeOnCancellation { executor.shutdown() }
            }
            executor.shutdown()

            if (!outputFile.exists()) {
                return DomainError.SensorError.CaptureFailed(
                    "CameraX onImageSaved fired but file missing: ${outputFile.absolutePath}"
                ).left()
            }

            // 8. Fix EXIF orientation in-place and extract camera metadata
            val fixResult = ExifOrientationFixer.fixOrientation(outputFile.absolutePath)
                .fold(
                    ifLeft = { return it.left() },
                    ifRight = { it },
                )

            // 9. Merge EXIF camera metadata into motion sensor snapshot
            val sensorData: ImageSensorData? = if (sensorSnapshot != null) {
                sensorSnapshot.copy(
                    focalLengthMm = fixResult.focalLengthMm ?: sensorSnapshot.focalLengthMm,
                    focalLength35mmEq = fixResult.focalLength35mmEq
                        ?: sensorSnapshot.focalLength35mmEq,
                    cameraMake = fixResult.cameraMake ?: sensorSnapshot.cameraMake,
                    cameraModel = fixResult.cameraModel ?: sensorSnapshot.cameraModel,
                )
            } else if (fixResult.focalLengthMm != null || fixResult.cameraMake != null) {
                // No live sensor data — build from EXIF metadata alone
                ImageSensorData(
                    focalLengthMm = fixResult.focalLengthMm,
                    focalLength35mmEq = fixResult.focalLength35mmEq,
                    cameraMake = fixResult.cameraMake,
                    cameraModel = fixResult.cameraModel,
                )
            } else {
                null
            }

            PlatformImageFile(
                path = fixResult.outputPath,
                mimeType = "image/jpeg",
                capturedAtMs = capturedAt,
                focalLengthMm = fixResult.focalLengthMm,
                focalLength35mmEq = fixResult.focalLength35mmEq,
                cameraMake = fixResult.cameraMake,
                cameraModel = fixResult.cameraModel,
                sensorData = sensorData,
            ).right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.SensorError.CaptureFailed(
                "CameraX capture failed: ${e.message ?: "unknown"}"
            ).left()
        }
    }
}
