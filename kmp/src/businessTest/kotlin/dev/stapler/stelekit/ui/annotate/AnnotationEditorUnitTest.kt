// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import arrow.core.Either
import androidx.compose.ui.graphics.ImageBitmap
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
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
    private val settings = MapSettings()

    private fun newViewModel() = AnnotationEditorViewModel(
        measurementRepository = InMemoryMeasurementAnnotationRepository(),
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
}
