package dev.stapler.stelekit.annotate

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.platform.ml.MonocularDepthEstimator
import dev.stapler.stelekit.ui.annotate.DepthEstimationCoordinator
import dev.stapler.stelekit.ui.annotate.DepthModelUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Platform ImageBitmap factories (Bitmap.createBitmap on Android) are not available to plain
// unit tests; the estimator fake never reads pixels, so a metadata-only stub is enough.
private class StubImageBitmap : ImageBitmap {
    override val width = 2
    override val height = 2
    override val colorSpace: ColorSpace = ColorSpaces.Srgb
    override val hasAlpha = false
    override val config = ImageBitmapConfig.Argb8888
    override fun readPixels(
        buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int,
    ) = Unit
    override fun prepareToDraw() = Unit
}

private class FakeDepthEstimator(
    var initResult: Either<DomainError, Unit> = Unit.right(),
    var depthResult: Either<DomainError, FloatArray> = FloatArray(4) { 2f }.right(),
    val gate: CompletableDeferred<Unit>? = null,
) : MonocularDepthEstimator {
    override val isAvailable = true
    var initCalls = 0
    var estimateCalls = 0

    override suspend fun initialize(): Either<DomainError, Unit> {
        initCalls++
        return initResult
    }

    override suspend fun estimateDepth(imageBitmap: ImageBitmap): Either<DomainError, FloatArray> {
        estimateCalls++
        gate?.await()
        return depthResult
    }
}

class DepthEstimationCoordinatorTest {
    private val bitmap: ImageBitmap = StubImageBitmap()
    private val error = DomainError.SensorError.HardwareUnavailable("depth")

    private fun coordinator(
        estimator: FakeDepthEstimator,
        w: Double = 2.0,
        h: Double = 2.0,
        onCal: (Calibration) -> Unit = {},
    ) = DepthEstimationCoordinator(estimator, w, h, onCal)

    private suspend fun DepthEstimationCoordinator.awaitIdleAfterRun() {
        state.first { !it.isDepthInferenceRunning && (it.depthMap != null || it.depthEstimationError != null) }
    }

    @Test
    fun initFailure_setsError() = runTest {
        val est = FakeDepthEstimator(initResult = error.left())
        val c = coordinator(est)
        c.runDepthEstimation(bitmap)
        c.awaitIdleAfterRun()
        assertEquals(error.message, c.state.value.depthEstimationError)
        assertEquals(0, est.estimateCalls)
        c.close()
    }

    @Test
    fun estimateFailure_setsError() = runTest {
        val est = FakeDepthEstimator(depthResult = error.left())
        val c = coordinator(est)
        c.runDepthEstimation(bitmap)
        c.awaitIdleAfterRun()
        assertEquals(error.message, c.state.value.depthEstimationError)
        assertNull(c.state.value.depthMap)
        c.close()
    }

    @Test
    fun successWithoutTap_storesDepthMapAndSkipsCalibration() = runTest {
        var called = false
        val c = coordinator(FakeDepthEstimator()) { called = true }
        c.runDepthEstimation(bitmap)
        c.awaitIdleAfterRun()
        assertNotNull(c.state.value.depthMap)
        assertFalse(called)
        c.close()
    }

    @Test
    fun successWithTap_invokesCalibrationReady() = runTest {
        val got = CompletableDeferred<Calibration>()
        val c = coordinator(FakeDepthEstimator()) { got.complete(it) }
        c.runDepthEstimation(bitmap, NormalizedPoint(0.5, 0.5))
        val cal = got.await()
        assertEquals(CalibrationMethod.MONOCULAR_ML, cal.method)
        c.close()
    }

    @Test
    fun successWithTap_zeroImageDims_skipsCalibration() = runTest {
        var called = false
        val c = coordinator(FakeDepthEstimator(), w = 0.0, h = 0.0) { called = true }
        c.runDepthEstimation(bitmap, NormalizedPoint(0.5, 0.5))
        c.awaitIdleAfterRun()
        assertFalse(called)
        c.close()
    }

    @Test
    fun successWithTap_nonPositiveDepth_skipsCalibration() = runTest {
        var called = false
        val est = FakeDepthEstimator(depthResult = FloatArray(4) { 0f }.right())
        val c = coordinator(est) { called = true }
        c.runDepthEstimation(bitmap, NormalizedPoint(0.5, 0.5))
        c.awaitIdleAfterRun()
        assertFalse(called)
        c.close()
    }

    @Test
    fun runWhileRunning_isIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        val est = FakeDepthEstimator(gate = gate)
        val c = coordinator(est)
        c.runDepthEstimation(bitmap)
        assertTrue(c.state.value.isDepthInferenceRunning)
        c.runDepthEstimation(bitmap)
        gate.complete(Unit)
        c.awaitIdleAfterRun()
        assertEquals(1, est.initCalls)
        c.close()
    }

    @Test
    fun updateDepthModelUiState_propagates() {
        val c = coordinator(FakeDepthEstimator())
        c.updateDepthModelUiState(DepthModelUiState.Ready)
        assertEquals(DepthModelUiState.Ready, c.state.value.depthModelUiState)
        c.close()
    }

    @Test
    fun runAfterClose_doesNotInvokeEstimator() = runTest {
        val est = FakeDepthEstimator()
        val c = coordinator(est)
        c.close()
        c.runDepthEstimation(bitmap)
        assertEquals(0, est.initCalls)
    }
}
