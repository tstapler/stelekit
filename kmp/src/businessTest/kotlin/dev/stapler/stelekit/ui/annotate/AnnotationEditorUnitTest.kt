// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import arrow.core.Either
import androidx.compose.ui.graphics.ImageBitmap
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.platform.IMAGE_METER_DEFAULT_UNIT_KEY
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.ml.MonocularDepthEstimator
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryImageAnnotationRepository
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AC2/AC3/AC4: choosing a unit in the editor re-labels, persists per image, and becomes the global default. */
@OptIn(DirectRepositoryWrite::class)
class AnnotationEditorUnitTest {
    private object UnusedDepthEstimator : MonocularDepthEstimator {
        override val isAvailable = false
        override suspend fun initialize(): Either<DomainError, Unit> = Either.Right(Unit)
        override suspend fun estimateDepth(imageBitmap: ImageBitmap): Either<DomainError, FloatArray> =
            Either.Right(FloatArray(0))
    }

    private class MapSettings : Settings {
        val map = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun putBoolean(key: String, value: Boolean) {}
        override fun getString(key: String, defaultValue: String) = map[key] ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = key in map
    }

    private val image = ImageAnnotation(
        uuid = "img001", blockUuid = "blk001", pageUuid = "page001", graphPath = "/test",
        filePath = "/test/image.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
        unit = MeasurementUnit.METERS,
    )
    private val imageRepo = InMemoryImageAnnotationRepository()
    private val measurementRepo = InMemoryMeasurementAnnotationRepository()
    private val settings = MapSettings()

    private fun newViewModel() = AnnotationEditorViewModel(
        measurementRepository = measurementRepo,
        imageAnnotationRepository = imageRepo,
        settings = settings,
        imageWidthPx = 1000.0,
        imageHeightPx = 1000.0,
        monocularDepthEstimator = UnusedDepthEstimator,
    ).also { it.initialize(image) }

    private suspend fun eventually(check: suspend () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!check()) delay(10) }
    }

    @Test
    fun selectedUnitStartsAsTheStoredUnitOfTheImage() = runTest {
        val vm = newViewModel()
        assertEquals(MeasurementUnit.METERS, vm.state.value.selectedUnit)
        vm.close()
    }

    @Test
    fun setDisplayUnitUpdatesStateAndRelabelsCommittedMeasurements() = runTest {
        val vm = newViewModel()
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.addPoint(NormalizedPoint(0.0, 0.5))
        vm.addPoint(NormalizedPoint(0.1, 0.5))
        val before = vm.state.value.committedAnnotations.single().valueDisplay
        assertTrue(before!!.endsWith(" m"), "expected metres label, was $before")

        vm.setDisplayUnit(MeasurementUnit.FEET_INCHES)

        assertEquals(MeasurementUnit.FEET_INCHES, vm.state.value.selectedUnit)
        val after = vm.state.value.committedAnnotations.single().valueDisplay
        assertTrue(after!!.contains("'"), "expected feet-inches label, was $after")
        vm.close()
    }

    @Test
    fun setDisplayUnitPersistsUnitOnTheImageAnnotation() = runTest {
        val vm = newViewModel()
        vm.setDisplayUnit(MeasurementUnit.FEET_INCHES)
        eventually { imageRepo.getImageAnnotationByUuid(image.uuid).first().getOrNull()?.unit == MeasurementUnit.FEET_INCHES }
        assertEquals(MeasurementUnit.FEET_INCHES, vm.state.value.imageAnnotation?.unit)
        vm.close()
    }

    @Test
    fun setDisplayUnitWritesTheGlobalDefault() = runTest {
        val vm = newViewModel()
        vm.setDisplayUnit(MeasurementUnit.CENTIMETERS)
        assertEquals("CENTIMETERS", settings.map[IMAGE_METER_DEFAULT_UNIT_KEY])
        vm.close()
    }

    @Test
    fun gridRefUnitIsIndependentOfTheDisplayUnit() = runTest {
        val vm = newViewModel()
        vm.setDisplayUnit(MeasurementUnit.FEET_INCHES)
        assertEquals(MeasurementUnit.METERS, vm.state.value.gridRefUnit)
        vm.close()
    }

    private suspend fun storedMeasurements() = measurementRepo.getMeasurementsForImage(image.uuid).first().getOrNull().orEmpty()

    @Test
    fun unitChangeRelabelsStoredMeasurementsAndKeepsThemAll() = runTest {
        val stored = MeasurementAnnotation(
            uuid = "m0000000001", imageUuid = image.uuid, annotationType = AnnotationType.DISTANCE,
            normalizedPoints = listOf(NormalizedPoint(0.0, 0.5), NormalizedPoint(0.1, 0.5)),
            valueMeters = 1.0, valueDisplay = "1.0 m",
        )
        measurementRepo.saveMeasurementAnnotation(stored)
        val vm = newViewModel()
        eventually { vm.state.value.committedAnnotations.size == 1 }

        vm.setDisplayUnit(MeasurementUnit.FEET_INCHES)

        eventually { storedMeasurements().singleOrNull()?.valueDisplay?.contains("'") == true }
        assertEquals(1, storedMeasurements().size)
        vm.close()
    }

    @Test
    fun unitChangeBeforeTheInitialLoadDoesNotDeleteStoredMeasurements() = runTest {
        measurementRepo.saveMeasurementAnnotation(
            MeasurementAnnotation(
                uuid = "m0000000002", imageUuid = image.uuid, annotationType = AnnotationType.DISTANCE,
                normalizedPoints = listOf(NormalizedPoint(0.0, 0.5), NormalizedPoint(0.1, 0.5)),
                valueMeters = 1.0, valueDisplay = "1.0 m",
            ),
        )
        val vm = newViewModel()
        // Added before the load has necessarily finished: the unit change must not replace the stored set with this one.
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.addPoint(NormalizedPoint(0.0, 0.2)); vm.addPoint(NormalizedPoint(0.1, 0.2))
        vm.setDisplayUnit(MeasurementUnit.FEET_INCHES)

        eventually { storedMeasurements().any { it.uuid == "m0000000002" } }
        eventually { storedMeasurements().size >= 2 }
        vm.close()
    }

    @Test
    fun tagEditThenUnitChangeKeepsBothInTheSavedImage() = runTest {
        val vm = newViewModel()
        vm.addTag("kitchen")
        vm.setDisplayUnit(MeasurementUnit.FEET_INCHES)

        eventually {
            val saved = imageRepo.getImageAnnotationByUuid(image.uuid).first().getOrNull()
            saved?.unit == MeasurementUnit.FEET_INCHES && "kitchen" in saved.tags
        }
        vm.close()
    }

    @Test
    fun secondInitializeKeepsTheUnitTheUserChose() = runTest {
        val vm = newViewModel()
        vm.setDisplayUnit(MeasurementUnit.FEET_INCHES)
        vm.initialize(image) // stale copy still says METERS
        assertEquals(MeasurementUnit.FEET_INCHES, vm.state.value.selectedUnit)
        assertEquals(MeasurementUnit.FEET_INCHES, vm.state.value.imageAnnotation?.unit)
        vm.close()
    }

    @Test
    fun undoAndRedoNeverRevertTheChosenUnit() = runTest {
        val vm = newViewModel()
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.addPoint(NormalizedPoint(0.0, 0.5)); vm.addPoint(NormalizedPoint(0.1, 0.5))
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.addPoint(NormalizedPoint(0.0, 0.2)); vm.addPoint(NormalizedPoint(0.1, 0.2))
        vm.setDisplayUnit(MeasurementUnit.FEET_INCHES)

        vm.undo()
        assertEquals(MeasurementUnit.FEET_INCHES, vm.state.value.selectedUnit)
        assertEquals(MeasurementUnit.FEET_INCHES, vm.state.value.imageAnnotation?.unit)
        assertTrue(vm.state.value.committedAnnotations.all { it.valueDisplay!!.contains("'") }, "labels: ${vm.state.value.committedAnnotations.map { it.valueDisplay }}")

        vm.redo()
        assertEquals(MeasurementUnit.FEET_INCHES, vm.state.value.selectedUnit)
        assertTrue(vm.state.value.committedAnnotations.all { it.valueDisplay!!.contains("'") })
        vm.close()
    }
}
