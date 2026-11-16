package dev.stapler.stelekit.ui.components

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.CaptureLensRecord
import dev.stapler.stelekit.model.ImageSensorData
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.platform.sensor.CameraCapabilityProbe
import dev.stapler.stelekit.platform.sensor.CameraLens
import dev.stapler.stelekit.platform.sensor.CameraSession
import dev.stapler.stelekit.platform.sensor.CameraXBackend
import dev.stapler.stelekit.platform.sensor.CaptureLensRecordBuilder
import dev.stapler.stelekit.platform.sensor.ExifOrientationFixer
import dev.stapler.stelekit.platform.sensor.FlashMode
import dev.stapler.stelekit.platform.sensor.LensKind
import dev.stapler.stelekit.platform.sensor.PinchChipTracker
import dev.stapler.stelekit.platform.sensor.PlatformImageFile
import dev.stapler.stelekit.platform.sensor.SensorModule
import dev.stapler.stelekit.platform.sensor.defaultBackLens
import dev.stapler.stelekit.platform.sensor.snapshotSensorData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val logger = Logger("CameraCapture")

private const val SETTING_LAST_LENS = "camera_last_lens_id"
private const val SETTING_HINT_DISMISSED = "camera_ultrawide_hint_dismissed"
private const val CATALOG_TIMEOUT_MS = 4_000L
private const val FIRST_FRAME_TIMEOUT_MS = 5_000L
private const val INLINE_MESSAGE_MS = 3_000L
private const val SIDE_RAIL_MIN_WIDTH_DP = 600

@Composable
actual fun CameraViewfinderDialog(
    onCapture: (PlatformImageFile) -> Unit,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Transient UI work only (the shutter press); the session owns its own scope.
    val scope = rememberCoroutineScope()
    val settings = remember { PlatformSettings() }
    var isCapturing by remember { mutableStateOf(false) }
    var captureJob by remember { mutableStateOf<Job?>(null) }
    var lenses by remember { mutableStateOf(emptyList<CameraLens>()) }
    var savedLensId by rememberSaveable { mutableStateOf(settings.getString(SETTING_LAST_LENS, "")) }
    var flash by rememberSaveable { mutableStateOf(FlashMode.OFF) }
    var inlineMessage by remember { mutableStateOf<String?>(null) }
    var hintDismissed by remember { mutableStateOf(settings.getBoolean(SETTING_HINT_DISMISSED, false)) }
    var timedOut by remember { mutableStateOf(false) }
    var highlightedId by remember { mutableStateOf<String?>(null) }

    val previewView = remember(context) { PreviewView(context) }
    val backend = remember(context, lifecycleOwner) { CameraXBackend(context, lifecycleOwner, previewView.surfaceProvider) }
    val session = remember(backend) { CameraSession(backend, Dispatchers.Main.immediate, SystemClock::elapsedRealtime) }
    val sessionState by session.state.collectAsState()
    val tracker = remember(lenses) { PinchChipTracker(lenses) }
    val selected = sessionState.selected
    val hasFlash = remember(selected?.id) { backend.hasFlash() }

    val cancelCapture = {
        captureJob?.cancel()
        captureJob = null
        isCapturing = false
        onDismiss()
    }

    // rememberUpdatedState-style: onError must stay current without restarting the effect below.
    val currentOnError by androidx.compose.runtime.rememberUpdatedState(onError)

    DisposableEffect(session) {
        try {
            SensorModule.motionSensorProvider.startSensing()
        } catch (e: Throwable) {
            // Raw exception text must not reach the user-facing snackbar; log it, show a generic message.
            logger.warn("Failed to start sensors: ${e.message}", e)
            currentOnError("Failed to start sensors")
        }
        onDispose {
            session.close()
            SensorModule.motionSensorProvider.stopSensing()
        }
    }

    LaunchedEffect(session) {
        // A plain LaunchedEffect: an uncaught Throwable here kills the Android process.
        val found = try {
            withTimeoutOrNull(CATALOG_TIMEOUT_MS) { SensorModule.cameraLensCatalog.lenses() }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("Lens catalog failed: ${e::class.simpleName}")
            emptyList()
        }
        lenses = found
        session.requestLens(initialLens(found, savedLensId))
    }

    LaunchedEffect(selected?.id) {
        if (selected == null) return@LaunchedEffect
        savedLensId = selected.id
        settings.putString(SETTING_LAST_LENS, selected.id)
        highlightedId = null
        backend.setFlashMode(flash)
    }

    LaunchedEffect(sessionState.disabled.size) {
        if (sessionState.disabled.isEmpty()) return@LaunchedEffect
        inlineMessage = LENS_UNAVAILABLE
        delay(INLINE_MESSAGE_MS)
        inlineMessage = null
    }

    LaunchedEffect(session) {
        delay(FIRST_FRAME_TIMEOUT_MS)
        if (session.state.value.selected == null) timedOut = true
    }

    val showUnavailable = sessionState.allFailed || (timedOut && selected == null)

    Dialog(
        onDismissRequest = { cancelCapture() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            val sideRail = maxWidth >= SIDE_RAIL_MIN_WIDTH_DP.dp
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize().pointerInput(session) {
                    detectTransformGestures { _, _, zoomChange, _ ->
                        if (zoomChange == 1f || !session.state.value.shutterEnabled) return@detectTransformGestures
                        val range = backend.zoomRange()
                        val target = (backend.currentZoomRatio() * zoomChange).coerceIn(range.start, range.endInclusive)
                        highlightedId = tracker.onZoom(target)?.id
                        scope.launch { runCatchingNonCancellation { backend.setZoom(target) } }
                    }
                },
            )

            if (selected == null && !showUnavailable) CameraStartingOverlay()
            if (showUnavailable) {
                CameraUnavailableScreen(
                    onRetry = {
                        timedOut = false
                        session.requestLens(initialLens(lenses, savedLensId), forceRebind = true)
                    },
                    onClose = { cancelCapture() },
                )
            }

            TopBar(
                flash = flash,
                hasFlash = hasFlash,
                debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                onClose = { cancelCapture() },
                onFlash = {
                    flash = flash.next(hasFlash)
                    backend.setFlashMode(flash)
                },
                onProbe = {
                    scope.launch {
                        backend.unbind()
                        runCatchingNonCancellation { CameraCapabilityProbe(context).run() }
                        session.requestLens(selected ?: initialLens(lenses, savedLensId), forceRebind = true)
                    }
                },
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
            )

            CameraControls(
                    modifier = if (sideRail) {
                        Modifier.align(Alignment.CenterEnd).navigationBarsPadding().padding(end = 24.dp)
                    } else {
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp)
                    },
                    lenses = lenses,
                    selectedId = highlightedId ?: selected?.id,
                    disabled = sessionState.disabled,
                    bindingId = null,
                    enabled = !sessionState.binding && selected != null,
                    statusLine = inlineMessage ?: noSeparateLensLine(lenses),
                    showHint = selected?.kind == LensKind.ULTRA_WIDE && !hintDismissed,
                    onDismissHint = {
                        hintDismissed = true
                        settings.putBoolean(SETTING_HINT_DISMISSED, true)
                    },
                    onSelect = { session.requestLens(it) },
                    shutterEnabled = sessionState.shutterEnabled && !isCapturing,
                    isCapturing = isCapturing,
                    onShutter = {
                        val token = session.beginCapture() ?: return@CameraControls
                        val lens = selected ?: return@CameraControls
                        val shutterNs = SystemClock.elapsedRealtimeNanos()
                        val snapshot = backend.captureResults.latest()
                        val zoom = backend.currentZoomRatio()
                        isCapturing = true
                        captureJob = scope.launch {
                            try {
                                val result = takePhotoAndProcess(context, backend.imageCapture) { exifFocal ->
                                    CaptureLensRecordBuilder.build(lens, zoom, snapshot, shutterNs, exifFocal).also {
                                        logger.info(
                                            "capture lens camera=${it.cameraId} physical=${it.physicalId} focal=${it.focalLengthMm} " +
                                                "zoom=${it.zoomRatio} source=${it.source} keys=focal:${snapshot?.focalLengthMm != null}," +
                                                "physical:${snapshot?.activePhysicalId != null},zoom:${snapshot?.zoomRatio != null}",
                                        )
                                    }
                                }
                                result.fold(
                                    onSuccess = { file ->
                                        if (session.acceptCapture(token)) {
                                            onCapture(file)
                                        } else {
                                            File(file.path).delete()
                                            inlineMessage = CAPTURE_FAILED
                                        }
                                    },
                                    onFailure = { err ->
                                        // Raw exception text (possibly an OutOfMemoryError message) never reaches the UI.
                                        logger.warn("Capture failed: ${err.message}", err)
                                        inlineMessage = CAPTURE_FAILED
                                    },
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                // An uncaught Throwable on this plain scope can kill the Android process.
                                logger.warn("Capture callback crashed: ${e.message}", e)
                            } finally {
                                isCapturing = false
                                captureJob = null
                            }
                        }
                    },
                    onCancel = { cancelCapture() },
                )
        }
    }
}

private fun initialLens(lenses: List<CameraLens>, savedId: String): CameraLens =
    lenses.firstOrNull { it.id == savedId }
        ?: lenses.firstOrNull { it.kind == LensKind.WIDE && it.labelRatio == 1.0 }
        ?: lenses.firstOrNull { it.kind == LensKind.ZOOM_STOP && it.zoomRatio == 1.0 }
        ?: defaultBackLens()

private fun noSeparateLensLine(lenses: List<CameraLens>): String? =
    NO_SEPARATE_ULTRAWIDE.takeIf { lenses.isNotEmpty() && lenses.all { it.kind == LensKind.ZOOM_STOP } }

private inline fun runCatchingNonCancellation(block: () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.warn("Camera control failed: ${e::class.simpleName}")
    }
}

@Composable
private fun TopBar(
    flash: FlashMode,
    hasFlash: Boolean,
    debuggable: Boolean,
    onClose: () -> Unit,
    onFlash: () -> Unit,
    onProbe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White, modifier = Modifier.size(32.dp))
        }
        if (debuggable) TextButton(onClick = onProbe, modifier = Modifier.heightIn(min = 48.dp)) { Text("Probe lenses") }
        TextButton(onClick = onFlash, enabled = hasFlash, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(
                if (hasFlash) "Flash: ${flash.name.lowercase().replaceFirstChar { it.uppercase() }}" else "Flash not available on this lens",
                color = Color.White,
            )
        }
    }
}

@Composable
private fun CameraControls(
    lenses: List<CameraLens>,
    selectedId: String?,
    disabled: Map<String, String>,
    bindingId: String?,
    enabled: Boolean,
    statusLine: String?,
    showHint: Boolean,
    onDismissHint: () -> Unit,
    onSelect: (CameraLens) -> Unit,
    shutterEnabled: Boolean,
    isCapturing: Boolean,
    onShutter: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    if (showHint) WideAngleHint(onDismiss = onDismissHint)
    statusLine?.let { CameraStatusLine(it) }
    CameraLensChips(lenses, selectedId, disabled, bindingId, enabled, onSelect, Modifier.padding(vertical = 8.dp))
    Row(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel", color = Color.White) }
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (shutterEnabled) 1f else 0.5f))
                .border(4.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                .clickable(enabled = shutterEnabled, onClick = onShutter),
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
    }
    }
}

private suspend fun takePhotoAndProcess(
    context: Context,
    imageCapture: ImageCapture,
    lensRecordFor: (exifFocalMm: Double?) -> CaptureLensRecord,
): Result<PlatformImageFile> {
    val capturesDir = File(context.cacheDir, "captures").also { it.mkdirs() }
    val outputFile = File(capturesDir, "${UUID.randomUUID()}.jpg")
    var delivered = false
    val capturedAt = System.currentTimeMillis()
    val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
    val executor = Executors.newSingleThreadExecutor()
    return try {
        // Bounds the whole pipeline (sensor snapshot + shutter + EXIF fix), not just the shutter call.
        // Cancellation is cooperative: a HAL-wedged takePicture() or the synchronous EXIF re-encode cannot be
        // preempted, so this gives up within ~10 s while the native call may still hold the thread.
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
            // Off the calling dispatcher: the full-res decode/rotate/encode must not block Main.
            val fixResult = withContext(PlatformDispatcher.IO) {
                ExifOrientationFixer.fixOrientation(outputFile.absolutePath)
            }.fold(
                ifLeft = { return@withTimeout Result.failure<PlatformImageFile>(Exception("Photo processing failed")) },
                ifRight = { it },
            )
            val record = lensRecordFor(fixResult.focalLengthMm)
            val sensorData: ImageSensorData? = sensorSnapshot?.copy(
                focalLengthMm = fixResult.focalLengthMm ?: sensorSnapshot.focalLengthMm,
                focalLength35mmEq = fixResult.focalLength35mmEq ?: sensorSnapshot.focalLength35mmEq,
                cameraMake = fixResult.cameraMake ?: sensorSnapshot.cameraMake,
                cameraModel = fixResult.cameraModel ?: sensorSnapshot.cameraModel,
            )
            delivered = true
            Result.success(PlatformImageFile(
                path = fixResult.outputPath,
                mimeType = "image/jpeg",
                capturedAtMs = capturedAt,
                focalLengthMm = fixResult.focalLengthMm,
                focalLength35mmEq = fixResult.focalLength35mmEq,
                cameraMake = fixResult.cameraMake,
                cameraModel = fixResult.cameraModel,
                sensorData = sensorData,
                lensRecord = record,
            ))
        }
    } catch (e: TimeoutCancellationException) {
        Result.failure(Exception("Camera timed out — try again"))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // Throwable, not Exception: an OutOfMemoryError decoding a large frame must surface as a capture failure.
        Result.failure(e)
    } finally {
        executor.shutdown()
        // Unprocessed photos must not linger in cacheDir after a cancel, timeout or failure.
        if (!delivered) outputFile.delete()
    }
}
