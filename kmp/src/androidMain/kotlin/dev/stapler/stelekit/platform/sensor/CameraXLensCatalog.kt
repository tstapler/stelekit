// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:OptIn(ExperimentalCamera2Interop::class)

package dev.stapler.stelekit.platform.sensor

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.ProcessLifecycleOwner
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Android composition-root factory for the lens catalog (Camera2 characteristics plus a CameraX physical-id bind
 * test). The sources are injectable so an entry-point test can drive the real catalog with fakes.
 */
fun defaultCameraLensCatalog(
    context: Context,
    source: LensInfoSource = Camera2LensInfoSource(context.applicationContext),
    bindTester: PhysicalBindTester = CameraXPhysicalBindTester(context.applicationContext),
): CameraLensCatalog = ProbingLensCatalog(source, bindTester)

/** Reads rear-camera facts from Camera2, keeping only ids CameraX can also bind. Every characteristic may be absent. */
class Camera2LensInfoSource(private val context: Context) : LensInfoSource {
    override suspend fun readInfos(): List<CameraLensInfo> {
        val infos = withContext(PlatformDispatcher.IO) {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            manager.cameraIdList.mapNotNull { id -> infoOf(manager, id) }
        }
        val bindable = cameraXIds() ?: return infos
        return infos.filter { it.cameraId in bindable }
    }

    private fun infoOf(manager: CameraManager, id: String): CameraLensInfo? = try {
        val c = manager.getCameraCharacteristics(id)
        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> LensFacing.BACK
            CameraCharacteristics.LENS_FACING_FRONT -> LensFacing.FRONT
            else -> LensFacing.EXTERNAL
        }
        val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val physical = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) c.physicalCameraIds.sorted() else emptyList()
        val zoom = zoomRange(c)
        CameraLensInfo(
            cameraId = id,
            facing = facing,
            focalLengthMm = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()?.toDouble(),
            sensorWidthMm = size?.width?.toDouble(),
            sensorHeightMm = size?.height?.toDouble(),
            physicalIds = physical,
            physicalFocalsMm = physical.mapNotNull { pid -> physicalFocal(manager, pid)?.let { pid to it } }.toMap(),
            minZoomRatio = zoom?.first,
            maxZoomRatio = zoom?.second,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

    private fun physicalFocal(manager: CameraManager, id: String): Double? = try {
        manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()?.toDouble()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

    private fun zoomRange(c: CameraCharacteristics): Pair<Double, Double>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { return it.lower.toDouble() to it.upper.toDouble() }
        }
        val digital = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: return null
        return 1.0 to digital.toDouble()
    }

    private suspend fun cameraXIds(): Set<String>? = try {
        val provider = ProcessCameraProvider.getInstance(context).await(context)
        withContext(Dispatchers.Main) { provider.availableCameraInfos.mapNotNull { safeCameraId(it) }.toSet() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }
}

/**
 * Binds a throwaway [ImageCapture] to one physical id and unbinds it again, so only that use case is touched. Run it
 * before the dialog's own bind, never while a preview is bound to the same camera.
 */
class CameraXPhysicalBindTester(private val context: Context) : PhysicalBindTester {
    override suspend fun canBind(cameraId: String, physicalId: String): Boolean {
        val provider = ProcessCameraProvider.getInstance(context).await(context)
        return withContext(Dispatchers.Main) {
            val probe = ImageCapture.Builder().build()
            try {
                val selector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .addCameraFilter { infos -> infos.filter { safeCameraId(it) == cameraId } }
                    .setPhysicalCameraId(physicalId)
                    .build()
                val camera = provider.bindToLifecycle(ProcessLifecycleOwner.get(), selector, probe)
                // Binding is asynchronous: a bad physical id only shows up as a camera-state error after bind returns.
                awaitCameraOpen(camera.cameraInfo, BIND_OPEN_TIMEOUT_MS) == CameraOpenResult.OPEN
            } finally {
                provider.unbind(probe)
            }
        }
    }

    private companion object {
        const val BIND_OPEN_TIMEOUT_MS = 1_500L
    }
}
