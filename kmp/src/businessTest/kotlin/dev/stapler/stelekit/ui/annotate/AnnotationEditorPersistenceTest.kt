// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.graphics.ImageBitmap
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.AnnotationType
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Editor persistence behaviour over a recording repository: leave outcomes, failed load, late load merge. */
@OptIn(DirectRepositoryWrite::class)
class AnnotationEditorPersistenceTest {
    private object NoDepth : MonocularDepthEstimator {
        override val isAvailable = false
        override suspend fun initialize(): Either<DomainError, Unit> = Either.Right(Unit)
        override suspend fun estimateDepth(imageBitmap: ImageBitmap): Either<DomainError, FloatArray> =
            Either.Right(FloatArray(0))
    }

    private class RecordingRepository(
        private val loadResult: Either<DomainError, List<MeasurementAnnotation>> = emptyList<MeasurementAnnotation>().right(),
        private val delegate: InMemoryMeasurementAnnotationRepository = InMemoryMeasurementAnnotationRepository(),
    ) : MeasurementAnnotationRepository by delegate {
        val calls = CopyOnWriteArrayList<String>()
        @Volatile var failSaves = false

        override fun getMeasurementsForImage(imageUuid: String): Flow<Either<DomainError, List<MeasurementAnnotation>>> =
            flowOf(loadResult)

        override suspend fun saveMeasurementAnnotation(measurement: MeasurementAnnotation): Either<DomainError, Unit> {
            calls += "save:${measurement.uuid}"
            return if (failSaves) DomainError.DatabaseError.WriteFailed("disk full").left()
            else delegate.saveMeasurementAnnotation(measurement)
        }

        override suspend fun saveMeasurements(imageUuid: String, measurements: List<MeasurementAnnotation>): Either<DomainError, Unit> {
            calls += "saveAll:${measurements.size}"
            return if (failSaves) DomainError.DatabaseError.WriteFailed("disk full").left()
            else delegate.saveMeasurements(imageUuid, measurements)
        }
    }

    private val image = ImageAnnotation(
        uuid = "img-001", blockUuid = "blk-001", pageUuid = "page-001", graphPath = "/test",
        filePath = "/test/image.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
        unit = MeasurementUnit.METERS,
    )

    private fun persisted(uuid: String) = MeasurementAnnotation(
        uuid = uuid, imageUuid = image.uuid, annotationType = AnnotationType.DISTANCE,
        normalizedPoints = listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.4, 0.1)),
    )

    private fun newViewModel(repo: MeasurementAnnotationRepository) = AnnotationEditorViewModel(
        measurementRepository = repo,
        imageWidthPx = 1000.0,
        imageHeightPx = 1000.0,
        monocularDepthEstimator = NoDepth,
    ).also { it.initialize(image) }

    private fun commitDistance(vm: AnnotationEditorViewModel) {
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.addPoint(NormalizedPoint(0.0, 0.5))
        vm.addPoint(NormalizedPoint(0.2, 0.5))
    }

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    @Test
    fun back_should_CloseWithoutDialog_When_OnlyCommittedAnnotations() = await {
        val vm = newViewModel(RecordingRepository())
        commitDistance(vm)
        assertEquals(BackOutcome.Close, vm.resolveBack())
        vm.close()
    }

    @Test
    fun back_should_ConfirmDiscardingOnlyUnfinishedPoints_When_PointInProgress() = await {
        val vm = newViewModel(RecordingRepository())
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.addPoint(NormalizedPoint(0.3, 0.3))
        assertEquals(BackOutcome.ConfirmDiscard(1), vm.resolveBack())
        vm.close()
    }

    @Test
    fun back_should_OfferRetryThenClose_When_LastSaveFailedThenRetrySucceeds() = await {
        val repo = RecordingRepository().also { it.failSaves = true }
        val vm = newViewModel(repo)
        commitDistance(vm)
        assertEquals(BackOutcome.SaveFailed, vm.resolveBack())
        assertNotNull(vm.persistenceFailure.value)

        repo.failSaves = false
        vm.retryPersistence()
        assertEquals(BackOutcome.Close, vm.resolveBack())
        assertNull(vm.persistenceFailure.value)
        assertEquals(0, vm.pendingWrites.value)
        vm.close()
    }

    @Test
    fun undo_should_UpsertRowsNotBulkReplace_When_InitialLoadFailed() = await {
        val repo = RecordingRepository(loadResult = DomainError.DatabaseError.ReadFailed("locked").left())
        val vm = newViewModel(repo)
        vm.state.first { vm.state.value.imageAnnotation != null }
        commitDistance(vm)
        commitDistance(vm)
        vm.resolveBack()
        vm.undo()
        vm.resolveBack()

        assertTrue(repo.calls.none { it.startsWith("saveAll") }, "bulk replace issued after failed load: ${repo.calls}")
        assertTrue(repo.calls.count { it.startsWith("save:") } >= 3)
        vm.close()
    }

    @Test
    fun lateLoad_should_KeepPersistedAndNewRows_When_UserPlacesBeforeLoadCompletes() = await {
        val repo = RecordingRepository(loadResult = listOf(persisted("old-1"), persisted("old-2")).right())
        val vm = newViewModel(repo)
        commitDistance(vm)
        vm.resolveBack()
        val ids = vm.state.first { it.committedAnnotations.size == 3 }.committedAnnotations.map { it.uuid }
        assertTrue("old-1" in ids && "old-2" in ids)
        vm.close()
    }

    @Test
    fun undoCalibration_should_PersistRestoredSet_When_CalibrationChanged() = await {
        val repo = RecordingRepository()
        val vm = newViewModel(repo)
        commitDistance(vm)
        vm.updateCalibration(Calibration(CalibrationMethod.MANUAL_REFERENCE, 250.0, 100))
        vm.undoCalibration()
        vm.resolveBack()
        assertTrue(repo.calls.count { it.startsWith("saveAll") } >= 2, "${repo.calls}")
        vm.close()
    }

    @Test
    fun deleteAnnotation_should_NotResurrectRow_When_LoadIsLate() = await {
        val repo = RecordingRepository(loadResult = listOf(persisted("old-1")).right())
        val vm = newViewModel(repo)
        vm.deleteAnnotation("old-1")
        vm.resolveBack()
        assertTrue(vm.state.value.committedAnnotations.none { it.uuid == "old-1" })
        vm.close()
    }
}
