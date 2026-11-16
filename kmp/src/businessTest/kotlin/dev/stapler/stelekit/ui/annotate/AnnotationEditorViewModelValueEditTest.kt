// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import arrow.core.Either
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.ConstraintSource
import dev.stapler.stelekit.calibration.LensMode
import dev.stapler.stelekit.model.CalibrationTick
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.MeasurementValue
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.4 through the real view model: override model, recompute skip, drag confirm, undo. */
class AnnotationEditorViewModelValueEditTest {
    private val ppm = 5000.0
    private val image = ImageAnnotation(
        uuid = "img-edit", blockUuid = "blk", pageUuid = "page", graphPath = "/test", filePath = "/test/i.jpg",
    )

    private fun vm() = AnnotationEditorViewModel(
        measurementRepository = InMemoryMeasurementAnnotationRepository(),
        imageWidthPx = 4000.0,
        imageHeightPx = 3000.0,
    ).also { it.initialize(image) }

    /** Native-basis 5000 px/m reference; a legacy-basis photo cannot take known distances. */
    private fun AnnotationEditorViewModel.calibrate() {
        confirmMultiPointCalibration(listOf(CalibrationTick(0.1, 0.9, 0.0), CalibrationTick(0.475, 0.9, 0.3)), LensMode.Off)
    }

    private fun AnnotationEditorViewModel.draw(x: Double, y: Double = 0.5, lengthPx: Double = 1000.0): MeasurementAnnotation {
        selectTool(AnnotationTool.DISTANCE)
        addPoint(NormalizedPoint(x, y))
        addPoint(NormalizedPoint(x + lengthPx / 4000, y))
        return state.value.committedAnnotations.last { it.annotationType == AnnotationType.DISTANCE }
    }

    private fun AnnotationEditorViewModel.byUuid(uuid: String) = state.value.committedAnnotations.single { it.uuid == uuid }

    @Test
    fun labelOnlyEdit_should_StoreOverrideKeepGeometryAndAddOneUndoEntry() {
        val vm = vm().also { it.calibrate() }
        val m = vm.draw(0.2)
        val computed = assertNotNull(m.valueMeters)
        val calibrationBefore = vm.state.value.calibration
        val depthBefore = vm.undoDepth

        val result = vm.submitValueEdit(m.uuid, "0.90", ValueEditMode.LABEL_ONLY, MeasurementUnit.METERS)

        assertTrue(result.isRight())
        val edited = vm.byUuid(m.uuid)
        assertEquals(MeasurementValue.Overridden(0.90, computed), edited.value)
        assertEquals(m.normalizedPoints, edited.normalizedPoints)
        assertEquals(calibrationBefore, vm.state.value.calibration)
        assertEquals(depthBefore + 1, vm.undoDepth)
        vm.close()
    }

    @Test
    fun undo_should_RestoreTheComputedValue() {
        val vm = vm().also { it.calibrate() }
        val m = vm.draw(0.2)
        vm.submitValueEdit(m.uuid, "0.90", ValueEditMode.LABEL_ONLY, MeasurementUnit.METERS)
        vm.undo()
        assertEquals(MeasurementValue.Computed(assertNotNull(m.valueMeters)), vm.byUuid(m.uuid).value)
        vm.close()
    }

    @Test
    fun invalidText_should_BeRejectedAndStoreNothing() {
        val vm = vm().also { it.calibrate() }
        val m = vm.draw(0.2)
        val depth = vm.undoDepth
        for (text in listOf("0", "-2", "", "abc")) {
            val result = vm.submitValueEdit(m.uuid, text, ValueEditMode.LABEL_ONLY, MeasurementUnit.METERS)
            assertIs<Either.Left<MeasurementValueEdit.Error>>(result, "text '$text'")
        }
        assertEquals(m, vm.byUuid(m.uuid))
        assertEquals(depth, vm.undoDepth)
        vm.close()
    }

    @Test
    fun feetInchesEntry_should_ParseToMeters() {
        val vm = vm().also { it.calibrate() }
        val m = vm.draw(0.2)
        vm.submitValueEdit(m.uuid, "5'3\"", ValueEditMode.LABEL_ONLY, MeasurementUnit.FEET_INCHES)
        assertEquals(1.6002, vm.byUuid(m.uuid).valueMeters!!, 1e-4)
        vm.close()
    }

    @Test
    fun recalibration_should_RecomputeOthersAndKeepTheOverride() {
        val vm = vm().also { it.calibrate() }
        val others = listOf(vm.draw(0.05, 0.3), vm.draw(0.05, 0.4), vm.draw(0.05, 0.6))
        val target = vm.draw(0.5, 0.5)
        vm.submitValueEdit(target.uuid, "0.90", ValueEditMode.LABEL_ONLY, MeasurementUnit.METERS)

        val before = vm.state.value.calibration!!
        vm.updateCalibration(before.copy(pixelsPerMeter = before.pixelsPerMeter * 1.04))

        others.forEach { o -> assertEquals(o.valueMeters!! / 1.04, vm.byUuid(o.uuid).valueMeters!!, 1e-6) }
        assertEquals(0.90, vm.byUuid(target.uuid).valueMeters)
        val message = vm.calibrationChangeMessage.value
        assertTrue(message!!.contains("1 measurement keeps your edited value"), message)
        assertTrue(message.contains("3 measurements recalculated"), message)
        vm.close()
    }

    @Test
    fun draggingAnOverride_should_AskAndKeepTheValueUntilResolved() {
        val vm = vm().also { it.calibrate() }
        val m = vm.draw(0.2)
        vm.submitValueEdit(m.uuid, "0.90", ValueEditMode.LABEL_ONLY, MeasurementUnit.METERS)

        vm.beginAnnotationEdit()
        val moved = listOf(m.normalizedPoints[0], NormalizedPoint(m.normalizedPoints[1].x + 0.1, m.normalizedPoints[1].y))
        vm.updateAnnotationPoints(m.uuid, moved)

        assertEquals(m.uuid, vm.overrideDragConfirm.value?.uuid)
        assertEquals(0.90, vm.byUuid(m.uuid).valueMeters)
        vm.close()
    }

    @Test
    fun declineDragConfirm_should_RevertPointsAndKeepTheOverride() {
        val vm = vm().also { it.calibrate() }
        val m = vm.draw(0.2)
        vm.submitValueEdit(m.uuid, "0.90", ValueEditMode.LABEL_ONLY, MeasurementUnit.METERS)
        val edited = vm.byUuid(m.uuid)
        val depth = vm.undoDepth

        vm.beginAnnotationEdit()
        vm.updateAnnotationPoints(m.uuid, listOf(m.normalizedPoints[0], NormalizedPoint(0.9, 0.5)))
        vm.resolveOverrideDrag(clearOverride = false)

        assertEquals(edited, vm.byUuid(m.uuid))
        assertNull(vm.overrideDragConfirm.value)
        assertEquals(depth, vm.undoDepth, "a declined drag leaves no undo entry")
        vm.close()
    }

    @Test
    fun acceptDragConfirm_should_ClearTheOverrideAndMeasureTheNewLength() {
        val vm = vm().also { it.calibrate() }
        val m = vm.draw(0.2)
        vm.submitValueEdit(m.uuid, "0.90", ValueEditMode.LABEL_ONLY, MeasurementUnit.METERS)

        vm.beginAnnotationEdit()
        vm.updateAnnotationPoints(m.uuid, listOf(m.normalizedPoints[0], NormalizedPoint(m.normalizedPoints[0].x + 2000.0 / 4000, 0.5)))
        vm.resolveOverrideDrag(clearOverride = true)

        assertEquals(2000.0 / ppm, vm.byUuid(m.uuid).valueMeters!!, 1e-9)
        assertIs<MeasurementValue.Computed>(vm.byUuid(m.uuid).value)
        assertNull(vm.overrideDragConfirm.value)
        vm.close()
    }

    @Test
    fun draggingAComputedMeasurement_should_NotAsk() {
        val vm = vm().also { it.calibrate() }
        val m = vm.draw(0.2)
        vm.beginAnnotationEdit()
        vm.updateAnnotationPoints(m.uuid, listOf(m.normalizedPoints[0], NormalizedPoint(0.9, 0.5)))
        assertNull(vm.overrideDragConfirm.value)
        vm.close()
    }

    @Test
    fun useAsKnownDistance_should_PromoteTheMeasurementToAConstraintAndSetTheScale() {
        val vm = vm().also { it.calibrate() }
        val m = vm.draw(0.2, lengthPx = 1000.0)

        vm.submitValueEdit(m.uuid, "40", ValueEditMode.KNOWN_DISTANCE, MeasurementUnit.CENTIMETERS)

        val geometry = vm.state.value.calibration?.geometry
        val constraint = assertNotNull(geometry?.constraints?.single())
        assertEquals(ConstraintSource.FROM_MEASUREMENT, constraint.source)
        assertEquals(m.uuid, constraint.measurementUuid)
        assertEquals(0.40, constraint.distanceMeters, 1e-9)
        assertEquals(1000.0 / 0.40, vm.state.value.calibration!!.pixelsPerMeter, 1e-6)
        assertEquals(0.40, vm.byUuid(m.uuid).valueMeters!!, 1e-6)
        vm.close()
    }
}
