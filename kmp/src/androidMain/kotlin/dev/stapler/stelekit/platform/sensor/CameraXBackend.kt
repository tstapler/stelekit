// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:OptIn(ExperimentalCamera2Interop::class)

package dev.stapler.stelekit.platform.sensor

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The one place the default-back-camera literal lives (ADR-004); every other binder calls this. */
internal fun defaultBackSelector(): CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

/** Camera2 id of a CameraX info, or null for an info with no Camera2 backing (extension or virtual cameras). */
internal fun safeCameraId(info: CameraInfo): String? = try {
    Camera2CameraInfo.from(info).cameraId
} catch (e: IllegalArgumentException) {
    null
}

/** CAMERA_IN_USE and the other recoverable codes are retried by CameraX itself, so only these end a bind. */
internal fun isFatalCameraError(code: Int): Boolean = when (code) {
    CameraState.ERROR_STREAM_CONFIG,
    CameraState.ERROR_CAMERA_DISABLED,
    CameraState.ERROR_CAMERA_FATAL_ERROR,
    CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED,
    -> true
    else -> false
}

/** Deletes files in [dir] not modified for [maxAgeMs], returning how many; leftovers of cancelled or crashed captures. */
internal fun sweepStaleCaptures(dir: java.io.File, nowMs: Long, maxAgeMs: Long): Int =
    dir.listFiles().orEmpty().count { it.isFile && nowMs - it.lastModified() > maxAgeMs && it.delete() }

internal enum class CameraOpenResult { OPEN, ERROR, TIMEOUT }

/** Waits on the Main thread for the camera to reach OPEN, an error, or [timeoutMs]; bindToLifecycle alone proves nothing. */
internal suspend fun awaitCameraOpen(info: CameraInfo, timeoutMs: Long): CameraOpenResult =
    withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<CameraOpenResult> { cont ->
            val observer = object : Observer<CameraState> {
                override fun onChanged(value: CameraState) {
                    val result = when {
                        value.error?.code?.let(::isFatalCameraError) == true -> CameraOpenResult.ERROR
                        value.type == CameraState.Type.OPEN -> CameraOpenResult.OPEN
                        else -> return
                    }
                    info.cameraState.removeObserver(this)
                    if (cont.isActive) cont.resume(result)
                }
            }
            info.cameraState.observeForever(observer)
            // removeObserver asserts the main thread, and cancellation can arrive on any thread.
            cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { info.cameraState.removeObserver(observer) } }
        }
    } ?: CameraOpenResult.TIMEOUT

/** Selector for one lens: a specific back camera by id, optionally pinned to one physical id behind it. */
internal fun selectorFor(lens: CameraLens): CameraSelector {
    if (lens.cameraId.isEmpty()) return defaultBackSelector()
    val builder = CameraSelector.Builder()
        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
        .addCameraFilter { infos -> infos.filter { safeCameraId(it) == lens.cameraId } }
    lens.physicalId?.let { builder.setPhysicalCameraId(it) }
    return builder.build()
}

/**
 * CameraX implementation of [CameraBackend]. It owns one [Preview] and one [ImageCapture] and unbinds only those
 * two, never `unbindAll()`, so a Preview bound by the QR-scan binder and an ImageAnalysis bound by the frame source
 * stay up. A failing [bind] rebinds the previous lens before throwing so the preview is never left black.
 */
internal class CameraXBackend(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    surfaceProvider: Preview.SurfaceProvider,
    val captureResults: CaptureResultHolder = CaptureResultHolder(),
    private val binderSource: suspend () -> UseCaseBinder = {
        ProviderBinder(ProcessCameraProvider.getInstance(context).await(context))
    },
) : CameraBackend {
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            try {
                captureResults.update(snapshotOf(result))
            } catch (e: Throwable) {
                // A capture callback must never throw into the camera thread; the record falls back to the selected lens.
            }
        }
    }

    val preview: Preview = Preview.Builder().also {
        Camera2Interop.Extender(it).setSessionCaptureCallback(captureCallback)
    }.build().also { it.setSurfaceProvider(surfaceProvider) }

    val imageCapture: ImageCapture = buildCappedImageCapture {
        Camera2Interop.Extender(it).setSessionCaptureCallback(captureCallback)
    }

    private companion object {
        const val OPEN_TIMEOUT_MS = 3_000L
    }

    private var provider: UseCaseBinder? = null
    private var camera: Camera? = null
    private var current: CameraLens? = null

    /** True when the bound camera has a flash unit; false before the first bind. */
    fun hasFlash(): Boolean = camera?.cameraInfo?.hasFlashUnit() == true

    fun zoomRange(): ClosedFloatingPointRange<Double> {
        val state = camera?.cameraInfo?.zoomState?.value ?: return 1.0..1.0
        return state.minZoomRatio.toDouble()..state.maxZoomRatio.toDouble()
    }

    fun currentZoomRatio(): Double = camera?.cameraInfo?.zoomState?.value?.zoomRatio?.toDouble() ?: 1.0

    override suspend fun bind(lens: CameraLens) {
        val cameraProvider = obtainBinder()
        withContext(Dispatchers.Main) {
            val previous = current
            try {
                cameraProvider.unbind(preview, imageCapture)
                val bound = cameraProvider.bind(lifecycleOwner, selectorFor(lens), preview, imageCapture)
                camera = bound
                // A bad physical id surfaces as a camera-state error after bind returns, not as a throw.
                check(awaitCameraOpen(bound.cameraInfo, OPEN_TIMEOUT_MS) != CameraOpenResult.ERROR) {
                    "camera reported an error opening lens ${lens.id}"
                }
                current = lens
                captureResults.clear()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!restore(cameraProvider, previous) && previous != null) throw PreviousLensLostException(e)
                throw e
            }
        }
    }

    override suspend fun setZoom(ratio: Double) {
        val control = checkNotNull(camera?.cameraControl) { "no camera is bound" }
        withContext(Dispatchers.Main) { control.setZoomRatio(ratio.toFloat()).await(context) }
    }

    fun setFlashMode(mode: FlashMode) {
        imageCapture.flashMode = when (mode) {
            FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
        }
    }

    override fun unbind() {
        try {
            provider?.unbind(preview, imageCapture)
        } catch (e: Throwable) {
            // Best effort on teardown; the lifecycle owner releases the camera regardless.
        }
        camera = null
        current = null
    }

    /** True when the previous lens is running again (or there was none to restore). */
    private fun restore(cameraProvider: UseCaseBinder, previous: CameraLens?): Boolean {
        if (previous == null) {
            runCatching { cameraProvider.unbind(preview, imageCapture) }
            camera = null
            return true
        }
        return try {
            cameraProvider.unbind(preview, imageCapture)
            camera = cameraProvider.bind(lifecycleOwner, selectorFor(previous), preview, imageCapture)
            true
        } catch (e: Throwable) {
            camera = null
            current = null
            false
        }
    }

    private suspend fun obtainBinder(): UseCaseBinder = provider ?: binderSource().also { provider = it }

    private fun snapshotOf(result: TotalCaptureResult) = snapshotFrom(
        object : CaptureResultReader {
            override fun <T> get(key: CaptureResult.Key<T>): T? = result.get(key)
        },
    )
}

/** Reads one key of a capture result; a seam so the snapshot mapping needs no real [TotalCaptureResult]. */
internal interface CaptureResultReader {
    operator fun <T> get(key: CaptureResult.Key<T>): T?
}

internal fun snapshotFrom(result: CaptureResultReader) = CaptureResultSnapshot(
    timestampNs = SystemClock.elapsedRealtimeNanos(),
    focalLengthMm = result[CaptureResult.LENS_FOCAL_LENGTH]?.toDouble(),
    activePhysicalId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        result[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID]
    } else {
        null
    },
    zoomRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) result[CaptureResult.CONTROL_ZOOM_RATIO]?.toDouble() else null,
)

/** The two [ProcessCameraProvider] calls the backend needs, so bind and restore can run against a fake. */
internal interface UseCaseBinder {
    fun unbind(vararg useCases: UseCase)

    fun bind(owner: LifecycleOwner, selector: CameraSelector, vararg useCases: UseCase): Camera
}

private class ProviderBinder(private val provider: ProcessCameraProvider) : UseCaseBinder {
    override fun unbind(vararg useCases: UseCase) = provider.unbind(*useCases)

    override fun bind(owner: LifecycleOwner, selector: CameraSelector, vararg useCases: UseCase): Camera =
        provider.bindToLifecycle(owner, selector, *useCases)
}

internal suspend fun <T> ListenableFuture<T>.await(context: Context): T = suspendCancellableCoroutine { cont ->
    addListener(
        {
            if (cont.isActive) {
                runCatching { get() }
                    .onSuccess { cont.resume(it) }
                    .onFailure { cont.resumeWithException(it) }
            }
        },
        ContextCompat.getMainExecutor(context),
    )
    cont.invokeOnCancellation { cancel(true) }
}
