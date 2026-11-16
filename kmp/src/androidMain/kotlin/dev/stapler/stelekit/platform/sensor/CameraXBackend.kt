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
import android.os.SystemClock
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The one place the default-back-camera literal lives (ADR-004); every other binder calls this. */
internal fun defaultBackSelector(): CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

/** Selector for one lens: a specific back camera by id, optionally pinned to one physical id behind it. */
internal fun selectorFor(lens: CameraLens): CameraSelector {
    if (lens.cameraId.isEmpty()) return defaultBackSelector()
    val builder = CameraSelector.Builder()
        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
        .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == lens.cameraId } }
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

    private var provider: ProcessCameraProvider? = null
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
        val cameraProvider = obtainProvider()
        withContext(Dispatchers.Main) {
            val previous = current
            try {
                cameraProvider.unbind(preview, imageCapture)
                camera = cameraProvider.bindToLifecycle(lifecycleOwner, selectorFor(lens), preview, imageCapture)
                current = lens
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                restore(cameraProvider, previous)
                throw e
            }
        }
    }

    override suspend fun setZoom(ratio: Double) {
        val control = camera?.cameraControl ?: return
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

    private fun restore(cameraProvider: ProcessCameraProvider, previous: CameraLens?) {
        if (previous == null) return
        try {
            cameraProvider.unbind(preview, imageCapture)
            camera = cameraProvider.bindToLifecycle(lifecycleOwner, selectorFor(previous), preview, imageCapture)
        } catch (e: Throwable) {
            camera = null
            current = null
        }
    }

    private suspend fun obtainProvider(): ProcessCameraProvider {
        provider?.let { return it }
        val future = ProcessCameraProvider.getInstance(context)
        return future.await(context).also { provider = it }
    }

    private fun snapshotOf(result: TotalCaptureResult) = CaptureResultSnapshot(
        timestampNs = SystemClock.elapsedRealtimeNanos(),
        focalLengthMm = result.get(CaptureResult.LENS_FOCAL_LENGTH)?.toDouble(),
        activePhysicalId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        } else {
            null
        },
        zoomRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) result.get(CaptureResult.CONTROL_ZOOM_RATIO)?.toDouble() else null,
    )
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
