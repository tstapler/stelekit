// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.calibration.MeasurementAdvisor
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.CalibrationTick
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.ImageSensorData
import dev.stapler.stelekit.model.LensCalibration
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the editor surfaces (pill marks and status strip) for a real view model with measurements. */
class AnnotationEditorReliabilityTest {
    private val lensCalibration = Calibration(
        method = CalibrationMethod.MANUAL_REFERENCE,
        pixelsPerMeter = 8000.0,
        confidencePercent = 100,
        basisVersion = CalibrationBasis.NATIVE_PIXELS_LENS,
        lens = LensCalibration(0.0, listOf(CalibrationTick(0.1, 0.5, 0.0), CalibrationTick(0.115, 0.5, 0.1)), 0.0),
    )

    private fun vm(calibration: Calibration, sensor: ImageSensorData): AnnotationEditorViewModel {
        val image = ImageAnnotation(
            uuid = "img-rel", blockUuid = "blk", pageUuid = "page", graphPath = "/test", filePath = "/test/i.jpg",
            calibration = calibration, sensorData = sensor,
        )
        return AnnotationEditorViewModel(
            measurementRepository = InMemoryMeasurementAnnotationRepository(),
            imageWidthPx = 4000.0,
            imageHeightPx = 3000.0,
        ).also {
            it.initialize(image)
            it.selectTool(AnnotationTool.DISTANCE)
            it.addPoint(NormalizedPoint(0.4, 0.5))
            it.addPoint(NormalizedPoint(0.6, 0.5))
        }
    }

    @Test
    fun shortReferenceMarksThePillAndFillsTheStatusStrip() {
        val vm = vm(lensCalibration, ImageSensorData(pitchDeg = 0.0, rollDeg = 0.0))
        val uuid = vm.state.value.committedAnnotations.single().uuid
        assertEquals(setOf(uuid), vm.warnedAnnotationUuids())
        val status = assertNotNull(vm.statusReliability())
        assertTrue(status.warnings.any { it.startsWith("Short reference") })
        assertNotNull(status.guidance)
        vm.close()
    }

    @Test
    fun selectedMeasurementDrivesTheStatusStrip() {
        val vm = vm(lensCalibration, ImageSensorData(pitchDeg = 0.0, rollDeg = 0.0))
        val uuid = vm.state.value.committedAnnotations.single().uuid
        vm.selectAnnotation(uuid)
        assertEquals(vm.reliabilityOf(uuid).warnings, assertNotNull(vm.statusReliability()).warnings)
        vm.close()
    }

    @Test
    fun tiltWarnsEvenWithoutRulerTicks() {
        val basic = Calibration(CalibrationMethod.MANUAL_REFERENCE, 8000.0, 100, CalibrationBasis.NATIVE_PIXELS)
        val vm = vm(basic, ImageSensorData(pitchDeg = 6.0, rollDeg = 0.0))
        assertTrue(vm.warnedAnnotationUuids().isNotEmpty())
        vm.close()
    }

    @Test
    fun galleryImagesReportTiltUnknownAsANoteNotAWarning() {
        val basic = Calibration(CalibrationMethod.MANUAL_REFERENCE, 8000.0, 100, CalibrationBasis.NATIVE_PIXELS)
        val vm = vm(basic, ImageSensorData())
        assertTrue(vm.warnedAnnotationUuids().isEmpty())
        assertEquals(listOf(MeasurementAdvisor.TILT_UNKNOWN_NOTE), vm.statusReliability()?.notes)
        vm.close()
    }

    @Test
    fun uncalibratedImagesHaveNoStatus() {
        val vm = vm(Calibration(), ImageSensorData())
        assertNull(vm.statusReliability())
        assertTrue(vm.warnedAnnotationUuids().isEmpty())
        vm.close()
    }
}
