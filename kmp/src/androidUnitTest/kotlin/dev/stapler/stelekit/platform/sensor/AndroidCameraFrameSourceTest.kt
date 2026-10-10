package dev.stapler.stelekit.platform.sensor

import android.Manifest
import android.app.Application
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import androidx.test.core.app.ApplicationProvider
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [CameraXBinder] is injected via the `internal constructor` (see its KDoc on
 * [AndroidCameraFrameSource]) precisely so these tests can exercise the
 * permission-gate / emit / cancel-unbind wiring in [AndroidCameraFrameSource] itself without
 * needing a real camera HAL — `ProcessCameraProvider.bindToLifecycle` requires actual camera
 * hardware that does not exist under Robolectric or a plain JVM unit test.
 *
 * [FakeCameraXBinder.bind] builds and returns a *real* [ImageAnalysis] instance (exercising
 * CameraX's own [ImageAnalysis.Builder]/[ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST] config path),
 * but skips the hardware-touching `ProcessCameraProvider.bindToLifecycle` step. So: real
 * `ImageAnalysis` object lifecycle (build/setAnalyzer/clearAnalyzer) — not exercised, and not
 * exercisable in this sandbox: the actual `ProcessCameraProvider` bind/unbind against a live
 * camera device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidCameraFrameSourceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun grantCameraPermission() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.CAMERA)
    }

    @Test
    fun frameStream_should_EmitCameraFrameWithRotationDegrees_When_PermissionGrantedAndQrOnScreen() = runTest {
        grantCameraPermission()
        val fakeBinder = FakeCameraXBinder()
        val source = AndroidCameraFrameSource(context, requestPermission = null, binder = fakeBinder)

        val results = mutableListOf<Either<DomainError.SensorError, CameraFrame>>()
        val collectJob = launch { source.frameStream().collect { results += it } }

        val (analyzer, _) = withTimeout(5_000) { fakeBinder.boundDeferred.await() }
        val fakeProxy = FakeImageProxy(
            widthPx = 640,
            heightPx = 480,
            rotationDegreesValue = 90,
            yPlaneBytes = byteArrayOf(1, 2, 3, 4),
        )
        analyzer.analyze(fakeProxy)

        withTimeout(5_000) {
            while (results.isEmpty()) kotlinx.coroutines.yield()
        }
        collectJob.cancel()
        collectJob.join()

        val emitted = results.single()
        assertTrue(emitted is Either.Right, "expected a decoded CameraFrame, got $emitted")
        val frame = (emitted as Either.Right).value
        assertEquals(90, frame.rotationDegrees)
        assertEquals(640, frame.width)
        assertEquals(480, frame.height)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), frame.luminanceBytes)
        assertTrue(fakeProxy.closed, "ImageProxy must be closed after each frame (leak otherwise)")
    }

    @Test
    fun frameStream_should_EmitPermissionDeniedSensorError_When_CameraPermissionDenied() = runTest {
        // Robolectric denies all permissions by default — no grantCameraPermission() call.
        val fakeBinder = FakeCameraXBinder()
        val source = AndroidCameraFrameSource(context, requestPermission = { false }, binder = fakeBinder)

        val results = mutableListOf<Either<DomainError.SensorError, CameraFrame>>()
        withTimeout(5_000) {
            source.frameStream().collect { results += it }
        }

        assertEquals(1, results.size, "flow must complete after emitting exactly one error")
        val error = results.single()
        assertTrue(error is Either.Left, "expected PermissionDenied, got $error")
        assertEquals(
            DomainError.SensorError.PermissionDenied("camera"),
            (error as Either.Left).value,
        )
        assertEquals(0, fakeBinder.bindCount, "must not attempt to bind the camera without permission")
    }

    @Test
    fun frameStream_should_UnbindImageAnalysisUseCase_When_CollectorCancels() = runTest {
        grantCameraPermission()
        val fakeBinder = FakeCameraXBinder()
        val source = AndroidCameraFrameSource(context, requestPermission = null, binder = fakeBinder)

        val collectJob = launch { source.frameStream().collect { } }

        val bound = withTimeout(5_000) { fakeBinder.boundDeferred.await() }
        assertEquals(0, fakeBinder.unbindCount, "must not unbind while still collecting")

        collectJob.cancel()
        collectJob.join()

        assertEquals(1, fakeBinder.unbindCount, "must unbind exactly once on collector cancellation")
        assertEquals(fakeBinder.boundAnalysis, fakeBinder.lastUnbound, "must unbind the same use case it bound")
        assertTrue(fakeBinder.executorShutdownRequested, "no leaked camera binding — analyzer executor must be shut down")
        // Silence unused-variable lint while documenting what `bound` proves (the analyzer/
        // executor pair captured at bind time).
        assertTrue(bound.first === fakeBinder.capturedAnalyzer)
    }

    /** Records bind/unbind calls; returns a real [ImageAnalysis] but skips real camera hardware. */
    private class FakeCameraXBinder : CameraXBinder {
        val boundDeferred = CompletableDeferred<Pair<ImageAnalysis.Analyzer, Executor>>()
        var bindCount = 0
            private set
        var unbindCount = 0
            private set
        var boundAnalysis: ImageAnalysis? = null
            private set
        var lastUnbound: ImageAnalysis? = null
            private set
        var capturedAnalyzer: ImageAnalysis.Analyzer? = null
            private set
        var executorShutdownRequested = false

        override suspend fun bind(analyzer: ImageAnalysis.Analyzer, executor: Executor): ImageAnalysis {
            bindCount++
            capturedAnalyzer = analyzer
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            boundAnalysis = imageAnalysis
            boundDeferred.complete(analyzer to executor)
            return imageAnalysis
        }

        override fun unbind(imageAnalysis: ImageAnalysis) {
            unbindCount++
            lastUnbound = imageAnalysis
            executorShutdownRequested = true
        }
    }

    /**
     * Minimal fake [ImageProxy] carrying a single Y-plane, per this class's KDoc.
     *
     * Overrides are plain `fun getX()` (not Kotlin `val`/`var` property syntax) — [ImageProxy]
     * is a plain Java interface with no Kotlin metadata, so Kotlin does not synthesize
     * property overrides for it; only call-site property access (`imageProxy.width`) works.
     */
    private class FakeImageProxy(
        private val widthPx: Int,
        private val heightPx: Int,
        rotationDegreesValue: Int,
        yPlaneBytes: ByteArray,
    ) : ImageProxy {
        var closed = false
            private set

        private var crop: Rect = Rect(0, 0, widthPx, heightPx)
        private val plane = FakePlaneProxy(yPlaneBytes)
        private val info = FakeImageInfo(rotationDegreesValue)

        override fun getWidth(): Int = widthPx
        override fun getHeight(): Int = heightPx
        override fun getFormat(): Int = ImageFormat.YUV_420_888
        override fun getCropRect(): Rect = crop
        override fun setCropRect(rect: Rect?) { rect?.let { crop = it } }
        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = arrayOf(plane)
        override fun getImageInfo(): ImageInfo = info
        override fun getImage(): Image? = null

        override fun close() {
            closed = true
        }
    }

    private class FakePlaneProxy(private val bytes: ByteArray) : ImageProxy.PlaneProxy {
        override fun getRowStride(): Int = bytes.size
        override fun getPixelStride(): Int = 1
        override fun getBuffer(): ByteBuffer = ByteBuffer.wrap(bytes)
    }

    private class FakeImageInfo(private val rotation: Int) : ImageInfo {
        override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()
        override fun getTimestamp(): Long = 0L
        override fun getRotationDegrees(): Int = rotation
        override fun populateExifData(builder: ExifData.Builder) {
            // No-op — not exercised by this test double.
        }
    }
}
