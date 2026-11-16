// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform.sensor

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Debug-only probe (Story 4.1): logs every camera's characteristics and the result of a `setPhysicalCameraId` bind
 * per physical id, then writes a make-and-model-only fixture the classifier tests can load. Every read is guarded so
 * a missing characteristic never aborts the run. Capture-metadata delivery (`LENS_FOCAL_LENGTH`, active physical id,
 * zoom) is not exercised here; it is logged by the dialog's `capture lens ...` line at each shutter press.
 */
class CameraCapabilityProbe(
    private val context: Context,
    private val bindTester: PhysicalBindTester = CameraXPhysicalBindTester(context),
    private val logger: Logger = Logger("CameraProbe"),
) {
    /** Runs the probe and returns the fixture file path, or null when the camera service could not be read. */
    suspend fun run(): String? {
        val cameras = withContext(PlatformDispatcher.IO) { readCameras() } ?: return null
        val withBinds = cameras.map { withBindResults(it) }
        withBinds.forEach { logger.info(CameraProbeFormat.line(it)) }
        val fixture = ProbeFixture(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty(), withBinds)
        val file = withContext(PlatformDispatcher.IO) { write(fixture) }
        logger.info("camera probe fixture saved: ${file.absolutePath}")
        return file.absolutePath
    }

    private fun readCameras(): List<ProbeCamera>? = try {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        manager.cameraIdList.map { id -> probeCamera(manager, id) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.warn("camera probe could not list cameras: ${e::class.simpleName}")
        null
    }

    private fun probeCamera(manager: CameraManager, id: String): ProbeCamera {
        val c = try {
            manager.getCameraCharacteristics(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return ProbeCamera(id = id)
        }
        val size = safe { c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) }
        val capabilities = safe { c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) }
        val keys = safe { c.keys.map { it.name }.toSet() }.orEmpty()
        val zoom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) safe { c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) } else null
        return ProbeCamera(
            id = id,
            facing = safe { c.get(CameraCharacteristics.LENS_FACING) }?.let { if (it == CameraCharacteristics.LENS_FACING_BACK) "BACK" else if (it == CameraCharacteristics.LENS_FACING_FRONT) "FRONT" else "EXTERNAL" },
            focalsMm = safe { c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) }?.map { it.toDouble() },
            sensorWidthMm = size?.width?.toDouble(),
            sensorHeightMm = size?.height?.toDouble(),
            logicalMulti = capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA),
            physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) safe { c.physicalCameraIds.sorted() } else null,
            hasIntrinsicCalibration = "android.lens.intrinsicCalibration" in keys,
            hasLensDistortion = "android.lens.distortion" in keys,
            distortionModes = safe { c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES) }?.toList(),
            minZoom = zoom?.lower?.toDouble(),
            maxZoom = zoom?.upper?.toDouble(),
        )
    }

    private suspend fun withBindResults(camera: ProbeCamera): ProbeCamera {
        val ids = camera.physicalIds.orEmpty()
        if (ids.isEmpty()) return camera
        val results = ids.associateWith { pid ->
            try {
                if (bindTester.canBind(camera.id, pid)) "ok" else "fail:false"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                "fail:${e::class.simpleName}"
            }
        }
        return camera.copy(physicalBind = results)
    }

    private fun write(fixture: ProbeFixture): File {
        val dir = File(context.filesDir, "camera-probe").also { it.mkdirs() }
        val name = (fixture.make + "-" + fixture.model).replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, "lens-fixture-$name.json").also { it.writeText(CameraProbeFormat.exportJson(fixture)) }
    }

    private inline fun <T> safe(block: () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }
}
