// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.sensor

import android.content.Context
import android.hardware.camera2.CaptureResult
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.UseCase
import androidx.camera.core.impl.CameraInfoInternal
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
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

    private class FakeBinder(private val camera: () -> Camera) : UseCaseBinder {
        var calls = 0
        val outcomes = ArrayDeque<Boolean>()

        override fun unbind(vararg useCases: UseCase) = Unit

        override fun bind(owner: LifecycleOwner, selector: CameraSelector, vararg useCases: UseCase): Camera {
            calls++
            if (!(outcomes.removeFirstOrNull() ?: true)) error("bind refused")
            return camera()
        }
    }

    private fun fakeCamera(state: CameraState): Camera {
        val info = infoWith(MutableLiveData(state))
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Camera::class.java)) { _, method, _ ->
            if (method.name == "getCameraInfo") info else error("unexpected ${method.name}")
        } as Camera
    }

    private fun lens(id: String) = defaultBackLens().copy(id = id, cameraId = "")

    private fun bindWith(binder: FakeBinder): CameraXBackend {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        return CameraXBackend(context, owner, Preview.SurfaceProvider { }, binderSource = { binder })
    }

    @Test
    fun bind_should_RestoreThePreviousLensOnFailure_AndReportALostPreviousLens() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val binder = FakeBinder { fakeCamera(CameraState.create(CameraState.Type.OPEN)) }
            val backend = bindWith(binder)
            backend.bind(lens("a"))

            binder.outcomes.addAll(listOf(false, true))
            val restored = assertFailsWith<IllegalStateException> { backend.bind(lens("b")) }
            assertFalse(restored is PreviousLensLostException)

            binder.outcomes.addAll(listOf(false, false))
            assertFailsWith<PreviousLensLostException> { backend.bind(lens("c")) }
            assertFailsWith<IllegalStateException> { backend.setZoom(2.0) }
            backend.unbind()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun bind_should_FailAndRestore_When_TheCameraReportsAFatalError() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val fatal = CameraState.create(CameraState.Type.CLOSED, CameraState.StateError.create(CameraState.ERROR_CAMERA_FATAL_ERROR))
            val binder = FakeBinder { fakeCamera(fatal) }
            val backend = bindWith(binder)
            assertFailsWith<IllegalStateException> { backend.bind(lens("a")) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    @Config(sdk = [30])
    fun snapshotFrom_should_MapTheResultKeys() {
        val reader = object : CaptureResultReader {
            @Suppress("UNCHECKED_CAST")
            override fun <T> get(key: CaptureResult.Key<T>): T? = when (key) {
                CaptureResult.LENS_FOCAL_LENGTH -> 5.6f
                CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID -> "2"
                CaptureResult.CONTROL_ZOOM_RATIO -> 2f
                else -> null
            } as T?
        }
        val snapshot = snapshotFrom(reader)
        assertEquals(5.6, snapshot.focalLengthMm!!, 1e-4)
        assertEquals("2", snapshot.activePhysicalId)
        assertEquals(2.0, snapshot.zoomRatio!!, 1e-4)
    }

    @Test
    fun safeCameraId_should_ReturnNull_ForAnInfoWithoutCamera2Backing() {
        val info = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CameraInfoInternal::class.java)) { proxy, method, _ ->
            if (method.name == "getImplementation") proxy else error("unexpected ${method.name}")
        } as CameraInfo
        assertNull(safeCameraId(info))
    }
}
