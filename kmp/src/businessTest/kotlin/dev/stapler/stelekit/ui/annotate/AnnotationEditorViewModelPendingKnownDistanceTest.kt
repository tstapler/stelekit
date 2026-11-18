// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.calibration.LensMode
import dev.stapler.stelekit.model.CalibrationTick
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.6 "Draw a known distance" flow in the view model: one-shot flag, open-editor event, cancel, undo. */
class AnnotationEditorViewModelPendingKnownDistanceTest {
    private val image = ImageAnnotation(
        uuid = "img-known", blockUuid = "blk", pageUuid = "page", graphPath = "/test", filePath = "/test/i.jpg",
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

    private fun AnnotationEditorViewModel.drawLine(x: Double = 0.2) {
        addPoint(NormalizedPoint(x, 0.5))
        addPoint(NormalizedPoint(x + 0.25, 0.5))
    }

    /** The next event, or null when none arrives; events are emitted synchronously, so a short wait is enough. */
    private fun SharedFlow<String>.drain(act: () -> Unit): String? = runBlocking {
        val deferred = async(Dispatchers.Unconfined) { withTimeoutOrNull(EVENT_WAIT_MS) { first() } }
        act()
        deferred.await()
    }

    @Test
    fun committingTheDrawnLine_should_EmitTheOpenEditorEventOnceAndClearTheFlag() {
        val vm = vm().also { it.calibrate() }
        vm.beginKnownDistance()
        assertTrue(vm.state.value.pendingKnownDistance)

        val opened = vm.openValueEditor.drain { vm.drawLine() }

        val drawn = vm.state.value.committedAnnotations.single { it.annotationType.name == "DISTANCE" }
        assertEquals(drawn.uuid, opened)
        assertFalse(vm.state.value.pendingKnownDistance, "the flag is one-shot")
        vm.close()
    }

    @Test
    fun aSecondLine_should_NotOpenTheEditor() {
        val vm = vm().also { it.calibrate() }
        vm.beginKnownDistance()
        vm.drawLine()
        vm.selectTool(AnnotationTool.DISTANCE)

        val opened = vm.openValueEditor.drain { vm.drawLine(0.1) }

        assertNull(opened)
        vm.close()
    }

    @Test
    fun pickingAnotherTool_should_DropThePendingFlag() {
        val vm = vm().also { it.calibrate() }
        vm.beginKnownDistance()
        vm.selectTool(AnnotationTool.AREA)
        assertFalse(vm.state.value.pendingKnownDistance)
        vm.close()
    }

    @Test
    fun undoAndRedo_should_KeepTheLiveFlag() {
        val vm = vm().also { it.calibrate() }
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.drawLine()
        vm.beginKnownDistance()
        vm.undo()
        assertTrue(vm.state.value.pendingKnownDistance, "restoredOver keeps the live flag")
        vm.close()
    }

    @Test
    fun cancel_should_RemoveTheDrawnLineAndLeaveNoUndoEntry() {
        val vm = vm().also { it.calibrate() }
        val depthBefore = vm.undoDepth
        vm.beginKnownDistance()
        vm.drawLine()
        val uuid = vm.state.value.committedAnnotations.single { it.annotationType.name == "DISTANCE" }.uuid

        vm.cancelKnownDistance(uuid)

        assertTrue(vm.state.value.committedAnnotations.none { it.uuid == uuid })
        assertEquals(depthBefore, vm.undoDepth)
        vm.close()
    }

    @Test
    fun apply_should_SetTheScaleAndUndoCalibrationShouldRestoreIt() {
        val vm = vm().also { it.calibrate() }
        val before = assertNotNull(vm.state.value.calibration)
        vm.beginKnownDistance()
        vm.drawLine()
        val uuid = vm.state.value.committedAnnotations.single { it.annotationType.name == "DISTANCE" }.uuid

        vm.submitValueEdit(uuid, "40", ValueEditMode.KNOWN_DISTANCE, MeasurementUnit.CENTIMETERS)
        assertEquals(1000.0 / 0.40, vm.state.value.calibration!!.pixelsPerMeter, 1e-6)

        vm.undoCalibration()
        assertEquals(before, vm.state.value.calibration)
        vm.close()
    }

    private companion object {
        const val EVENT_WAIT_MS = 200L
    }
}
