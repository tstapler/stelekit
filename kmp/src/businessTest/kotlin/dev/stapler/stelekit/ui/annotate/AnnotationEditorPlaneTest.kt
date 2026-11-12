// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.calibration.ReferenceRectangle
import dev.stapler.stelekit.model.GeometryCalibration
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Paper-reference flow on the real view model (flat, fronto-parallel sheet: easy ground truth). */
class AnnotationEditorPlaneTest {
    private val image = ImageAnnotation(uuid = "img-plane", blockUuid = "blk", pageUuid = "page", graphPath = "/test", filePath = "/test/i.jpg")

    private fun vm() = AnnotationEditorViewModel(
        measurementRepository = InMemoryMeasurementAnnotationRepository(),
        imageWidthPx = 4000.0,
        imageHeightPx = 3000.0,
    ).also { it.initialize(image) }

    /** Letter sheet, 10 px per mm, long edge first. */
    private fun AnnotationEditorViewModel.tapSheet() {
        val x0 = 500.0
        val y0 = 400.0
        val long = 2794.0 / 1.0 // 279.4 mm * 10 px/mm
        val short = 2159.0 / 1.0
        beginPlaneSession()
        setPlaneRectangle(ReferenceRectangle.LETTER)
        listOf(x0 to y0, x0 + long to y0, x0 + long to y0 + short, x0 to y0 + short).forEach { (x, y) ->
            addPoint(NormalizedPoint(x / 4000.0, y / 3000.0))
        }
    }

    @Test
    fun fourTapsCalibrateThePlaneAndMeasurementsUseIt() {
        val vm = vm()
        vm.tapSheet()
        assertTrue(vm.planePreview()!!.canApply)
        vm.confirmPlaneSession()
        assertNull(vm.state.value.planeSession)
        val plane = assertNotNull(vm.state.value.calibration?.geometry?.plane)
        assertEquals(ReferenceRectangle.LETTER.label, plane.referenceName)
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.addPoint(NormalizedPoint(1000.0 / 4000, 1500.0 / 3000))
        vm.addPoint(NormalizedPoint(2000.0 / 4000, 1500.0 / 3000))
        assertEquals(0.1, vm.state.value.committedAnnotations.single().valueMeters!!, 1e-6)
        vm.close()
    }

    @Test
    fun applyWaitsForFourCornersAndCancelDiscards() {
        val vm = vm()
        vm.beginPlaneSession()
        vm.addPoint(NormalizedPoint(0.1, 0.1))
        assertFalse(vm.planePreview()!!.canApply)
        vm.confirmPlaneSession()
        assertNull(vm.state.value.calibration?.geometry)
        assertEquals(1, vm.state.value.inProgressPoints.size)
        vm.cancelPlaneSession()
        assertNull(vm.state.value.planeSession)
        assertTrue(vm.state.value.inProgressPoints.isEmpty())
        vm.close()
    }

    @Test
    fun heightEditsKeepThePlaneAndANewPlaneKeepsTheHeight() {
        val vm = vm()
        vm.tapSheet()
        vm.confirmPlaneSession()
        vm.setGeometry(GeometryCalibration(0.004, 0.6))
        val geometry = vm.state.value.calibration?.geometry
        assertNotNull(geometry?.plane)
        assertEquals(0.004, geometry?.heightOffsetMeters)
        vm.setGeometry(null)
        assertNotNull(vm.state.value.calibration?.geometry?.plane)
        vm.setGeometry(GeometryCalibration(0.002, 0.6))
        vm.tapSheet()
        vm.confirmPlaneSession()
        assertEquals(0.002, vm.state.value.calibration?.geometry?.heightOffsetMeters)
        vm.close()
    }

    @Test
    fun aRulerLineReferenceReplacesThePlane() {
        val vm = vm()
        vm.tapSheet()
        vm.confirmPlaneSession()
        vm.setGeometry(GeometryCalibration(0.004, 0.6))
        vm.selectTool(AnnotationTool.GRID_REF)
        vm.addPoint(NormalizedPoint(0.1, 0.5))
        vm.addPoint(NormalizedPoint(0.35, 0.5))
        vm.updateGridRefLengthText("25")
        vm.updateGridRefUnit(dev.stapler.stelekit.model.MeasurementUnit.CENTIMETERS)
        vm.confirmGridRefCalibration()
        val geometry = vm.state.value.calibration?.geometry
        assertNull(geometry?.plane)
        assertEquals(0.004, geometry?.heightOffsetMeters)
        vm.close()
    }
}
