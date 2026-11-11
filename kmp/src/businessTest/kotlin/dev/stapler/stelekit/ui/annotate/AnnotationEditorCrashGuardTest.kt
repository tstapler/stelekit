package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.platform.ml.MonocularDepthEstimator
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import dev.stapler.stelekit.repository.MeasurementAnnotationRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mirrors `StelekitViewModelCrashReproductionTest` for the annotation editor: an `Error` thrown inside
 * an editor `scope.launch` must surface as [AnnotationEditorState.error], not reach the JVM default
 * uncaught-exception handler (which kills the process on Android).
 */
class AnnotationEditorCrashGuardTest {

    private class UncaughtRecorder : AutoCloseable {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        private val previous = Thread.getDefaultUncaughtExceptionHandler()

        init {
            Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.add(e) }
        }

        override fun close() = Thread.setDefaultUncaughtExceptionHandler(previous)
    }

    @OptIn(DirectRepositoryWrite::class)
    private class ThrowingRepository(
        private val toThrow: Throwable,
        private val delegate: MeasurementAnnotationRepository = InMemoryMeasurementAnnotationRepository(),
    ) : MeasurementAnnotationRepository by delegate {
        @kotlin.concurrent.Volatile var failSaves = true

        override suspend fun saveMeasurementAnnotation(measurement: MeasurementAnnotation): Either<DomainError, Unit> {
            if (failSaves) throw toThrow
            return delegate.saveMeasurementAnnotation(measurement)
        }

        override suspend fun saveMeasurements(imageUuid: String, measurements: List<MeasurementAnnotation>): Either<DomainError, Unit> {
            if (failSaves) throw toThrow
            return delegate.saveMeasurements(imageUuid, measurements)
        }
    }

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

    private class ThrowingDepthEstimator(private val toThrow: Throwable) : MonocularDepthEstimator {
        override val isAvailable = true
        override suspend fun initialize(): Either<DomainError, Unit> = Either.Right(Unit)
        override suspend fun estimateDepth(imageBitmap: ImageBitmap): Either<DomainError, FloatArray> = throw toThrow
    }

    private val image = ImageAnnotation(
        uuid = "img-001",
        blockUuid = "blk-001",
        pageUuid = "page-001",
        graphPath = "/test",
        filePath = "/test/image.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
        unit = MeasurementUnit.METERS,
    )

    private fun makeViewModel(repo: MeasurementAnnotationRepository, depth: MonocularDepthEstimator? = null) =
        AnnotationEditorViewModel(
            measurementRepository = repo,
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
            monocularDepthEstimator = depth ?: ThrowingDepthEstimator(IllegalStateException("unused")),
        ).also { it.initialize(image) }

    private fun commitDistance(vm: AnnotationEditorViewModel) {
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.addPoint(NormalizedPoint(0.0, 0.5))
        vm.addPoint(NormalizedPoint(0.1, 0.5))
    }

    private fun awaitState(vm: AnnotationEditorViewModel, condition: (AnnotationEditorState) -> Boolean) = runBlocking {
        withTimeout(5_000) { vm.state.first(condition) }
    }

    @Test
    fun editorScope_should_RouteOomToStateErrorNotDefaultHandler_When_RepositoryThrowsOutOfMemoryError() {
        UncaughtRecorder().use { recorder ->
            val vm = makeViewModel(ThrowingRepository(OutOfMemoryError("simulated")))

            commitDistance(vm)
            awaitState(vm) { it.error != null }

            assertTrue(recorder.uncaught.isEmpty(), "default handler saw ${recorder.uncaught}")
            assertNotNull(vm.state.value.error)
            assertTrue(vm.state.value.error!!.stopsInput)
            vm.close()
        }
    }

    @Test
    fun committedMeasurements_should_Remain_When_ThrowableCaught() {
        val vm = makeViewModel(ThrowingRepository(OutOfMemoryError("simulated")))

        commitDistance(vm)
        awaitState(vm) { it.error != null }

        assertEquals(1, vm.state.value.committedAnnotations.size)
        vm.close()
    }

    @Test
    fun input_should_StopAfterHandledError_UntilRetry() {
        val repo = ThrowingRepository(OutOfMemoryError("simulated"))
        val vm = makeViewModel(repo)
        commitDistance(vm)
        awaitState(vm) { it.error != null }

        vm.addPoint(NormalizedPoint(0.2, 0.2))
        vm.selectTool(AnnotationTool.AREA)
        assertTrue(vm.state.value.inProgressPoints.isEmpty())
        assertEquals(AnnotationTool.DISTANCE, vm.state.value.currentTool)

        repo.failSaves = false
        vm.retryAfterError()
        assertNull(vm.state.value.error)
        vm.selectTool(AnnotationTool.AREA)
        assertEquals(AnnotationTool.AREA, vm.state.value.currentTool)
        vm.close()
    }

    @Test
    fun input_should_ContinueAfterHandledException_BecauseOnlyErrorsStopInput() {
        val vm = makeViewModel(ThrowingRepository(IllegalStateException("flaky")))
        commitDistance(vm)
        awaitState(vm) { it.error != null }

        assertEquals(false, vm.state.value.error!!.stopsInput)
        vm.selectTool(AnnotationTool.ANGLE)
        assertEquals(AnnotationTool.ANGLE, vm.state.value.currentTool)
        vm.close()
    }

    @Test
    fun depthScope_should_RouteOomToStateErrorNotDefaultHandler_When_EstimatorThrows() {
        UncaughtRecorder().use { recorder ->
            val vm = makeViewModel(InMemoryMeasurementAnnotationRepository(), ThrowingDepthEstimator(OutOfMemoryError("simulated")))

            vm.runDepthEstimation(StubImageBitmap())
            // error is set by the coordinator callback; the depth fields reach the VM state via a separate collector.
            awaitState(vm) { it.error != null && it.depthEstimationError != null && !it.isDepthInferenceRunning }

            assertTrue(recorder.uncaught.isEmpty(), "default handler saw ${recorder.uncaught}")
            assertEquals(false, vm.state.value.isDepthInferenceRunning)
            assertNotNull(vm.state.value.depthEstimationError)
            assertTrue(vm.state.value.error!!.stopsInput)
            vm.close()
        }
    }

    @Test
    fun diagnosticsSource_should_ReportToolImageSizeAndUndoDepth() {
        val vm = makeViewModel(InMemoryMeasurementAnnotationRepository())
        vm.setImageSize(4000, 3000)
        commitDistance(vm)

        val snapshot = vm.diagnosticsSource.snapshot()

        assertEquals("DISTANCE", snapshot.tool)
        assertEquals("AnnotationEditor", snapshot.screen)
        assertEquals(4000, snapshot.imageWidthPx)
        assertEquals(3000, snapshot.imageHeightPx)
        assertEquals(1, snapshot.undoDepth)
        vm.close()
    }
}
