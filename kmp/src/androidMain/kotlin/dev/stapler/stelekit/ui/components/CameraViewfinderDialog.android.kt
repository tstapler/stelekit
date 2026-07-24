package dev.stapler.stelekit.ui.components

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.ImageSensorData
import dev.stapler.stelekit.platform.sensor.ExifOrientationFixer
import dev.stapler.stelekit.platform.sensor.PlatformImageFile
import dev.stapler.stelekit.platform.sensor.SensorModule
import dev.stapler.stelekit.platform.sensor.snapshotSensorData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val logger = Logger("CameraCapture")

@Composable
actual fun CameraViewfinderDialog(
    onCapture: (PlatformImageFile) -> Unit,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var isCapturing by remember { mutableStateOf(false) }
    var captureJob by remember { mutableStateOf<Job?>(null) }

    val previewView = remember(context) { PreviewView(context) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }

    val cancelCapture = {
        captureJob?.cancel()
        captureJob = null
        isCapturing = false
        onDismiss()
    }

    // DisposableEffect is keyed on lifecycleOwner only (rebinding the camera on every
    // recomposition would be wrong) — rememberUpdatedState keeps onError current without
    // restarting the effect.
    val currentOnError by rememberUpdatedState(onError)

    DisposableEffect(lifecycleOwner) {
        try {
            SensorModule.motionSensorProvider.startSensing()
        } catch (e: Throwable) {
            // Raw exception text (CameraX/hardware internals, or an OutOfMemoryError's
            // diagnostic message) must not reach the user-facing snackbar — log it and
            // surface a generic message instead, matching the sanitization already applied
            // to every other camera/import error path via DomainError.toUiMessage().
            logger.warn("Failed to start sensors: ${e.message}", e)
            currentOnError("Failed to start sensors")
        }
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val result = runCatching { future.get() }
            val obtained = result.getOrElse {
                logger.warn("Failed to start camera: ${it.message}", it)
                currentOnError("Failed to start camera")
                return@addListener
            }
            provider = obtained
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            try {
                obtained.unbindAll()
                obtained.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                )
            } catch (e: Throwable) {
                logger.warn("Failed to bind camera: ${e.message}", e)
                currentOnError("Failed to bind camera")
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            SensorModule.motionSensorProvider.stopSensing()
        }
    }

    Dialog(
        onDismissRequest = { cancelCapture() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { cancelCapture() }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                }

                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = if (isCapturing) 0.5f else 1f))
                        .border(4.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                        .clickable(enabled = !isCapturing) {
                            isCapturing = true
                            captureJob = scope.launch {
                                try {
                                    val result = takePhotoAndProcess(context, imageCapture)
                                    result.fold(
                                        onSuccess = { file -> onCapture(file) },
                                        onFailure = { err ->
                                            // Raw exception text (which may be an
                                            // OutOfMemoryError's diagnostic message, now that
                                            // takePhotoAndProcess catches Throwable) must not
                                            // reach the user-facing snackbar unsanitized.
                                            logger.warn("Capture failed: ${err.message}", err)
                                            onError("Capture failed")
                                            onDismiss()
                                        },
                                    )
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    // Guards the caller-supplied onCapture/onError/onDismiss
                                    // callbacks: an uncaught Throwable here would otherwise
                                    // propagate on this scope (a plain
                                    // rememberCoroutineScope() with no
                                    // CoroutineExceptionHandler) and can kill the Android
                                    // process.
                                    logger.warn("Capture callback crashed: ${e.message}", e)
                                } finally {
                                    isCapturing = false
                                    captureJob = null
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isCapturing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp,
                        )
                    }
                }

                // Balance the close button on the left
                Spacer(modifier = Modifier.size(48.dp))
            }
        }
    }
}

private suspend fun takePhotoAndProcess(
    context: Context,
    imageCapture: ImageCapture,
): Result<PlatformImageFile> {
    val capturesDir = File(context.cacheDir, "captures").also { it.mkdirs() }
    val outputFile = File(capturesDir, "${UUID.randomUUID()}.jpg")
    val capturedAt = System.currentTimeMillis()
    val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
    val executor = Executors.newSingleThreadExecutor()
    return try {
        // Bounds the whole pipeline (sensor snapshot + shutter + EXIF fix), not just the
        // shutter call — EXIF processing on a large/rotated JPEG used to run unbounded
        // after this timeout had already elapsed.
        // Note: cancellation is cooperative (Kotlin can only interrupt at a suspension
        // point) — the EXIF fix's synchronous BitmapFactory decode/rotate/encode and a
        // truly HAL-wedged takePicture() cannot be preempted mid-call. This timeout gives
        // up and surfaces an error to the caller within ~10s in that case, but the
        // underlying thread/camera binding may remain occupied until the native call
        // eventually returns. Known residual risk, not preemptible from Kotlin.
        withTimeout(10_000L) {
            val sensorSnapshot = SensorModule.motionSensorProvider.snapshotSensorData()
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
            if (!outputFile.exists()) {
                return@withTimeout Result.failure<PlatformImageFile>(
                    Exception("Capture succeeded but file is missing")
                )
            }
            // Off the calling dispatcher — full-res bitmap decode/rotate/encode must not
            // block the coroutine's current thread (Main, when launched from the UI).
            val fixResult = withContext(PlatformDispatcher.IO) {
                ExifOrientationFixer.fixOrientation(outputFile.absolutePath)
            }.fold(
                ifLeft = { return@withTimeout Result.failure<PlatformImageFile>(Exception("Photo processing failed")) },
                ifRight = { it },
            )
            val sensorData: ImageSensorData? = sensorSnapshot?.copy(
                focalLengthMm = fixResult.focalLengthMm ?: sensorSnapshot.focalLengthMm,
                focalLength35mmEq = fixResult.focalLength35mmEq ?: sensorSnapshot.focalLength35mmEq,
                cameraMake = fixResult.cameraMake ?: sensorSnapshot.cameraMake,
                cameraModel = fixResult.cameraModel ?: sensorSnapshot.cameraModel,
            )
            Result.success(PlatformImageFile(
                path = fixResult.outputPath,
                mimeType = "image/jpeg",
                capturedAtMs = capturedAt,
                focalLengthMm = fixResult.focalLengthMm,
                focalLength35mmEq = fixResult.focalLength35mmEq,
                cameraMake = fixResult.cameraMake,
                cameraModel = fixResult.cameraModel,
                sensorData = sensorData,
            ))
        }
    } catch (e: TimeoutCancellationException) {
        Result.failure(Exception("Camera timed out — try again"))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // Throwable, not Exception — an OutOfMemoryError decoding a large frame must
        // surface as a capture failure, not kill the process or hang. Pass e through
        // directly (not re-wrapped) so the original type/stack trace/cause chain survives.
        Result.failure(e)
    } finally {
        executor.shutdown()
    }
}
