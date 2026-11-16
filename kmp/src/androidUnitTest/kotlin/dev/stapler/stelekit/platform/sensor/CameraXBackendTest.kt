// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.sensor

import android.content.Context
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The CameraX backend's logic that needs no camera HAL; the bind itself is covered by the owner device checklist. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@OptIn(ExperimentalCoroutinesApi::class)
class CameraXBackendTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun backend(): CameraXBackend {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        return CameraXBackend(context, owner, Preview.SurfaceProvider { })
    }

    private fun infoWith(state: MutableLiveData<CameraState>): CameraInfo =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CameraInfo::class.java)) { _, method, _ ->
            if (method.name == "getCameraState") state else error("unexpected ${method.name}")
        } as CameraInfo

    @Test
    fun backendWithNothingBound_should_ReportDefaults_AndRefuseToZoom() = runTest {
        val backend = backend()
        assertFalse(backend.hasFlash())
        assertEquals(1.0..1.0, backend.zoomRange())
        assertEquals(1.0, backend.currentZoomRatio())
        assertFailsWith<IllegalStateException> { backend.setZoom(2.0) }
        backend.setFlashMode(FlashMode.ON)
        assertEquals(ImageCapture.FLASH_MODE_ON, backend.imageCapture.flashMode)
        backend.setFlashMode(FlashMode.AUTO)
        assertEquals(ImageCapture.FLASH_MODE_AUTO, backend.imageCapture.flashMode)
        backend.setFlashMode(FlashMode.OFF)
        assertEquals(ImageCapture.FLASH_MODE_OFF, backend.imageCapture.flashMode)
        backend.unbind()
        assertNull(backend.captureResults.latest())
    }

    @Test
    fun selectors_should_UseTheDefaultBackCamera_UnlessALensNamesACameraId() {
        assertSame(CameraSelector.DEFAULT_BACK_CAMERA, defaultBackSelector())
        assertSame(CameraSelector.DEFAULT_BACK_CAMERA, selectorFor(defaultBackLens()))
        val lens = defaultBackLens().copy(cameraId = "0", physicalId = "2")
        assertEquals(CameraSelector.LENS_FACING_BACK, selectorFor(lens).lensFacing)
    }

    @Test
    fun awaitCameraOpen_should_ReportOpen_Error_RecoverableThenOpen_AndTimeout() = runTest {
        val open = MutableLiveData(CameraState.create(CameraState.Type.OPEN))
        assertEquals(CameraOpenResult.OPEN, awaitCameraOpen(infoWith(open), 1_000))

        val fatal = MutableLiveData(
            CameraState.create(CameraState.Type.CLOSED, CameraState.StateError.create(CameraState.ERROR_CAMERA_FATAL_ERROR)),
        )
        assertEquals(CameraOpenResult.ERROR, awaitCameraOpen(infoWith(fatal), 1_000))

        val busy = MutableLiveData(
            CameraState.create(CameraState.Type.OPENING, CameraState.StateError.create(CameraState.ERROR_CAMERA_IN_USE)),
        )
        val waiting = async { awaitCameraOpen(infoWith(busy), 1_000) }
        runCurrent()
        busy.value = CameraState.create(CameraState.Type.OPEN)
        assertEquals(CameraOpenResult.OPEN, waiting.await())

        val never = MutableLiveData(CameraState.create(CameraState.Type.PENDING_OPEN))
        assertEquals(CameraOpenResult.TIMEOUT, awaitCameraOpen(infoWith(never), 500))
    }
}
