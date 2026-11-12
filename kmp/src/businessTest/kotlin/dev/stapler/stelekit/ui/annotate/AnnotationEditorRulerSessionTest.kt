// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.calibration.LensMode
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tick-marking flow on the real editor view model: taps become ticks, Apply goes through multi-point calibration. */
class AnnotationEditorRulerSessionTest {
    private val image = ImageAnnotation(
        uuid = "img-ruler", blockUuid = "blk", pageUuid = "page", graphPath = "/test", filePath = "/test/i.jpg",
    )

    private fun vm() = AnnotationEditorViewModel(
        measurementRepository = InMemoryMeasurementAnnotationRepository(),
        imageWidthPx = 4000.0,
        imageHeightPx = 3000.0,
    ).also { it.initialize(image) }

    private fun AnnotationEditorViewModel.tapTicks(valuesCm: List<String>) {
        valuesCm.forEachIndexed { i, text ->
            addPoint(NormalizedPoint((400.0 + 1000.0 * i) / 4000.0, 0.5))
            updateRulerTickValue(i, text)
        }
    }

    @Test
    fun tapsBecomeTicksInsteadOfAReferenceLine() {
        val vm = vm()
        vm.beginRulerSession()
        vm.addPoint(NormalizedPoint(0.1, 0.5))
        vm.addPoint(NormalizedPoint(0.2, 0.5))
        vm.addPoint(NormalizedPoint(0.3, 0.5))
        val st = vm.state.value
        assertEquals(3, st.rulerSession?.entries?.size)
        assertEquals(3, st.inProgressPoints.size)
        assertFalse(st.isGridRefDialogVisible)
        assertEquals(0, st.committedAnnotations.size)
        vm.close()
    }

    @Test
    fun applyCalibratesWithTheMultiPointFitAndClosesTheSession() {
        val vm = vm()
        vm.beginRulerSession()
        vm.tapTicks(listOf("0", "10", "20", "30"))
        assertTrue(vm.rulerPreview()!!.canApply)
        vm.setRulerLensMode(LensMode.Off)
        vm.confirmRulerSession()
        val cal = assertNotNull(vm.state.value.calibration)
        assertEquals(CalibrationBasis.NATIVE_PIXELS_LENS, cal.basisVersion)
        assertEquals(10000.0, cal.pixelsPerMeter, 1e-6)
        assertEquals(4, cal.lens?.ticks?.size)
        assertNull(vm.state.value.rulerSession)
        assertTrue(vm.state.value.inProgressPoints.isEmpty())
        vm.close()
    }

    @Test
    fun applyIsANoOpUntilTwoTicksHaveValues() {
        val vm = vm()
        vm.beginRulerSession()
        vm.tapTicks(listOf("0", ""))
        vm.confirmRulerSession()
        assertNull(vm.state.value.calibration?.lens)
        assertNotNull(vm.state.value.rulerSession)
        vm.close()
    }

    @Test
    fun cancelAndToolChangeDiscardTheSession() {
        val vm = vm()
        vm.beginRulerSession()
        vm.tapTicks(listOf("0", "10"))
        vm.cancelRulerSession()
        assertNull(vm.state.value.rulerSession)
        vm.beginRulerSession()
        vm.selectTool(AnnotationTool.DISTANCE)
        assertNull(vm.state.value.rulerSession)
        vm.close()
    }

    @Test
    fun theTwoPointDialogSeedsTheSession() {
        val vm = vm()
        vm.selectTool(AnnotationTool.GRID_REF)
        vm.addPoint(NormalizedPoint(0.1, 0.5))
        vm.addPoint(NormalizedPoint(0.35, 0.5))
        vm.updateGridRefUnit(MeasurementUnit.CENTIMETERS)
        vm.updateGridRefLengthText("25")
        vm.beginRulerSession()
        val s = assertNotNull(vm.state.value.rulerSession)
        assertEquals(listOf("0", "25"), s.entries.map { it.valueText })
        assertEquals(MeasurementUnit.CENTIMETERS, s.unit)
        assertFalse(vm.state.value.isGridRefDialogVisible)
        assertEquals(2, vm.state.value.inProgressPoints.size)
        vm.close()
    }

    @Test
    fun theSingleReferenceLineStillWorks() {
        val vm = vm()
        vm.selectTool(AnnotationTool.GRID_REF)
        vm.addPoint(NormalizedPoint(0.1, 0.5))
        vm.addPoint(NormalizedPoint(0.35, 0.5))
        vm.updateGridRefUnit(MeasurementUnit.CENTIMETERS)
        vm.updateGridRefLengthText("25")
        vm.confirmGridRefCalibration()
        assertEquals(4000.0 * 0.25 / 0.25, vm.state.value.calibration!!.pixelsPerMeter, 1e-6)
        assertNull(vm.state.value.rulerSession)
        vm.close()
    }
}
