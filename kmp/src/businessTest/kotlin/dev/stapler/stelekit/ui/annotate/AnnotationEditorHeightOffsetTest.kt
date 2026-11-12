// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.model.GeometryCalibration
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.ImageSensorData
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Golden scene T-G4 through the real view model: 1 cm raised 18 cm edge, camera 60 cm above the ruler. */
class AnnotationEditorHeightOffsetTest {
    private val fPx = 3100.0
    private val distance = 0.6
    private val image = ImageAnnotation(
        uuid = "img-height", blockUuid = "blk", pageUuid = "page", graphPath = "/test", filePath = "/test/i.jpg",
        sensorData = ImageSensorData(focalLength35mmEq = fPx / 5000.0 * 43.2666),
    )

    private fun vm() = AnnotationEditorViewModel(
        measurementRepository = InMemoryMeasurementAnnotationRepository(),
        imageWidthPx = 4000.0,
        imageHeightPx = 3000.0,
    ).also { it.initialize(image) }

    private fun AnnotationEditorViewModel.calibrateThirtyCm() {
        val ppm = fPx / distance
        selectTool(AnnotationTool.GRID_REF)
        addPoint(NormalizedPoint(500.0 / 4000, 0.8))
        addPoint(NormalizedPoint((500.0 + 0.30 * ppm) / 4000, 0.8))
        updateGridRefUnit(MeasurementUnit.CENTIMETERS)
        updateGridRefLengthText("30")
        confirmGridRefCalibration()
    }

    /** An 18 cm edge raised 1 cm above the ruler plane, as the camera sees it. */
    private fun AnnotationEditorViewModel.measureRaisedEdge(): Double {
        val apparentPx = fPx * 0.18 / (distance - 0.01)
        selectTool(AnnotationTool.DISTANCE)
        addPoint(NormalizedPoint(1000.0 / 4000, 0.5))
        addPoint(NormalizedPoint((1000.0 + apparentPx) / 4000, 0.5))
        return assertNotNull(state.value.committedAnnotations.single { it.annotationType.name == "DISTANCE" }.valueMeters)
    }

    @Test
    fun withoutAnOffsetTheRaisedEdgeReadsHigh() {
        val vm = vm()
        vm.calibrateThirtyCm()
        assertEquals(0.1830, vm.measureRaisedEdge(), 0.0005)
        vm.close()
    }

    @Test
    fun heightOffsetBringsItBackWithinThreeTenthsOfAMillimetre() {
        val vm = vm()
        vm.calibrateThirtyCm()
        vm.measureRaisedEdge()
        vm.setGeometry(GeometryCalibration(0.01))
        assertEquals(0.18, vm.state.value.committedAnnotations.single { it.annotationType.name == "DISTANCE" }.valueMeters!!, 0.0003)
        assertEquals(0.01, vm.state.value.imageAnnotation?.calibration?.geometry?.heightOffsetMeters)
        vm.close()
    }

    @Test
    fun referenceLinesAreNeverScaledAndClearingRestoresTheReading() {
        val vm = vm()
        vm.calibrateThirtyCm()
        vm.setGeometry(GeometryCalibration(0.01))
        vm.measureRaisedEdge()
        vm.setGeometry(null)
        assertNull(vm.state.value.calibration?.geometry)
        assertEquals(0.1830, vm.state.value.committedAnnotations.single { it.annotationType.name == "DISTANCE" }.valueMeters!!, 0.0005)
        vm.close()
    }

    @Test
    fun recalibratingKeepsTheEnteredHeight() {
        val vm = vm()
        vm.calibrateThirtyCm()
        vm.setGeometry(GeometryCalibration(0.004))
        vm.calibrateThirtyCm()
        assertEquals(0.004, vm.state.value.calibration?.geometry?.heightOffsetMeters)
        vm.close()
    }

    @Test
    fun anAreaScalesBySquareOfTheFactor() {
        val vm = vm()
        vm.calibrateThirtyCm()
        vm.selectTool(AnnotationTool.AREA)
        listOf(0.2 to 0.4, 0.3 to 0.4, 0.3 to 0.5, 0.2 to 0.5).forEach { (x, y) -> vm.addPoint(NormalizedPoint(x, y)) }
        vm.closePolygon()
        val flat = vm.state.value.committedAnnotations.single { it.annotationType.name == "AREA" }.valueMeters!!
        vm.setGeometry(GeometryCalibration(0.01))
        val corrected = vm.state.value.committedAnnotations.single { it.annotationType.name == "AREA" }.valueMeters!!
        val f = (distance - 0.01) / distance
        assertEquals(flat * f * f, corrected, flat * 1e-9)
        vm.close()
    }
}
