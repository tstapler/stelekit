// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.sensor

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.SizeF
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCameraCharacteristics
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Camera2 characteristic reading and the debug probe against Robolectric's fake CameraManager. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CameraLensSourceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun addCamera(id: String, facing: Int, focal: Float, size: SizeF) {
        val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        shadowOf(characteristics).apply {
            set(CameraCharacteristics.LENS_FACING, facing)
            set(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS, floatArrayOf(focal))
            set(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE, size)
            set(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM, 8f)
            // The logical-multi physical-id key is hidden API, so only the plain capability is faked.
            set(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES, intArrayOf(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE))
        }
        shadowOf(context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).addCamera(id, characteristics)
    }

    @Test
    fun source_should_ReadFactsOfEachCamera_AndKeepOnlyIdsCameraXCanBind() = runBlocking {
        addCamera("0", CameraCharacteristics.LENS_FACING_BACK, 5.6f, SizeF(6.4f, 4.8f))
        addCamera("1", CameraCharacteristics.LENS_FACING_FRONT, 2.0f, SizeF(3.2f, 2.4f))
        addCamera("5", CameraCharacteristics.LENS_FACING_EXTERNAL, 3.0f, SizeF(3.2f, 2.4f))

        val all = Camera2LensInfoSource(context, bindableIds = { null }).readInfos()
        assertEquals(listOf("0", "1", "5"), all.map { it.cameraId }.sorted())
        val back = all.first { it.cameraId == "0" }
        assertEquals(LensFacing.BACK, back.facing)
        assertEquals(5.6, back.focalLengthMm!!, 1e-4)
        assertEquals(1.0, back.minZoomRatio)
        assertEquals(8.0, back.maxZoomRatio)
        assertTrue(back.physicalIds.isEmpty())
        assertEquals(LensFacing.FRONT, all.first { it.cameraId == "1" }.facing)
        assertEquals(LensFacing.EXTERNAL, all.first { it.cameraId == "5" }.facing)

        val bindable = Camera2LensInfoSource(context, bindableIds = { setOf("0") }).readInfos()
        assertEquals(listOf("0"), bindable.map { it.cameraId })
    }

    @Test
    @Config(sdk = [30])
    fun source_should_ReadTheZoomRatioRange_OnApi30() = runBlocking {
        addCamera("0", CameraCharacteristics.LENS_FACING_BACK, 5.6f, SizeF(6.4f, 4.8f))
        val characteristics = (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).getCameraCharacteristics("0")
        shadowOf(characteristics).set(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE, android.util.Range(0.6f, 8f))
        val info = Camera2LensInfoSource(context, bindableIds = { null }).readInfos().single()
        assertEquals(0.6, info.minZoomRatio!!, 1e-4)
        assertEquals(8.0, info.maxZoomRatio!!, 1e-4)
    }

    @Test
    fun probe_should_ReturnNull_When_TheCameraServiceCannotBeRead() = runBlocking {
        val broken = object : android.content.ContextWrapper(context) {
            override fun getSystemService(name: String): Any = Any()
        }
        assertEquals(null, CameraCapabilityProbe(broken, bindTester = { _, _ -> true }).run())
    }

    @Test
    fun defaultCatalogFactory_should_BuildWithItsDefaultSources() {
        assertNotNull(defaultCameraLensCatalog(context))
    }

    @Test
    fun probe_should_WriteAFixtureFile_AndLogPhysicalBindResults() = runBlocking {
        addCamera("0", CameraCharacteristics.LENS_FACING_BACK, 5.6f, SizeF(6.4f, 4.8f))
        val path = CameraCapabilityProbe(context, bindTester = { _, _ -> true }).run()
        assertNotNull(path)
        val file = File(path)
        assertTrue(file.exists() && file.readText().contains("\"0\""))
    }
}
