// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.model.ValueSource
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 3.6d: the Screen hooks for the value editor, the calibration pill and banner, the Known distances sheet and the drag confirm. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class AnnotationEditorValueHooksTest {
    @get:Rule
    val rule = createComposeRule()

    /** A sheet's content can sit below the Robolectric window's fold, so touch injection would hit the scrim; act on semantics instead. */
    private fun activate(text: String) {
        rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    private fun image(method: CalibrationMethod = CalibrationMethod.MANUAL_REFERENCE) = ImageAnnotation(
        uuid = "img-hooks-001", blockUuid = "blk-hooks-001", pageUuid = "page-hooks-001",
        graphPath = "/test", filePath = "/test/image.jpg",
        calibration = Calibration(
            method, if (method == CalibrationMethod.NONE) 0.0 else 100.0, 100,
            basisVersion = CalibrationBasis.NATIVE_PIXELS_LENS,
        ),
        unit = MeasurementUnit.METERS,
    )

    private fun show(img: ImageAnnotation = image()): AnnotationEditorViewModel {
        val vm = AnnotationEditorViewModel(
            measurementRepository = InMemoryMeasurementAnnotationRepository(),
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        ).also { it.initialize(img) }
        rule.setContent { AnnotationEditorScreen(viewModel = vm, imageAnnotation = img) }
        rule.waitForIdle()
        rule.onNodeWithContentDescription(ANNOTATION_CANVAS_DESCRIPTION).performTouchInput { down(Offset(5f, 5f)); up() }
        rule.waitForIdle()
        return vm
    }

    @Test
    fun notCalibratedBanner_should_OpenTheKnownDistancesSheet() {
        show(image(CalibrationMethod.NONE))
        rule.onNodeWithTag("calibration_banner").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Draw a known distance").assertExists()
    }

    @Test
    fun calibrationPill_should_OpenTheKnownDistancesSheetAndDrawArmsTheDistanceTool() {
        val vm = show()
        rule.onNodeWithTag("calibration_pill").performClick()
        rule.waitForIdle()
        activate("Draw a known distance")
        rule.waitForIdle()
        assertTrue(vm.state.value.pendingKnownDistance)
        assertEquals(AnnotationTool.DISTANCE, vm.state.value.currentTool)
        rule.onNodeWithText("Draw a known distance").assertDoesNotExist()
    }

    private fun drawKnownLine(vm: AnnotationEditorViewModel) {
        rule.onNodeWithTag("calibration_pill").performClick()
        rule.waitForIdle()
        activate("Draw a known distance")
        rule.waitForIdle()
        rule.onNodeWithContentDescription(ANNOTATION_CANVAS_DESCRIPTION)
            .performTouchInput { down(Offset(200f, 300f)); moveTo(Offset(350f, 300f)); moveTo(Offset(500f, 300f)); up() }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 5_000) { vm.state.value.committedAnnotations.isNotEmpty() }
        rule.waitForIdle()
    }

    @Test
    fun addKnownDistance_should_RoundTripThroughEditorAndReturnToSheetWithTheRow() {
        val vm = show()
        drawKnownLine(vm)
        rule.onNodeWithText("What does this mean?").assertExists()
        assertFalse(vm.state.value.pendingKnownDistance)

        rule.onNode(hasSetTextAction() and !hasTestTag("annotationTagInput")).performTextReplacement("90 cm")
        activate("Use as known distance")
        // A value far from the current scale asks for "Apply anyway"; either button commits.
        activate(if (rule.onAllNodesWithText("Apply anyway").fetchSemanticsNodes().isEmpty()) "Apply" else "Apply anyway")
        rule.waitForIdle()

        assertEquals(1, vm.state.value.calibration?.geometry?.constraints?.size)
        rule.onNodeWithText("What does this mean?").assertDoesNotExist()
        rule.onNodeWithText("Draw a known distance").assertExists()
        rule.onNodeWithText("0.9", substring = true).assertExists()
    }

    @Test
    fun cancelKnownDistance_should_RemoveTheLineAndLeaveNoUndoEntry() {
        val vm = show()
        val undoBefore = vm.canUndo.value
        drawKnownLine(vm)
        rule.onNodeWithText("What does this mean?").assertExists()
        activate("Cancel")
        rule.waitForIdle()
        assertTrue(vm.state.value.committedAnnotations.isEmpty())
        assertEquals(undoBefore, vm.canUndo.value)
        assertNull(vm.state.value.calibration?.geometry?.constraints?.firstOrNull())
    }

    @Test
    fun overrideDrag_should_AskAndResolveThroughTheViewModel() {
        val vm = show()
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.addPoint(NormalizedPoint(0.1, 0.2)); vm.addPoint(NormalizedPoint(0.4, 0.2))
        val uuid = vm.state.value.committedAnnotations.single().uuid
        vm.submitValueEdit(uuid, "2 m", ValueEditMode.LABEL_ONLY, MeasurementUnit.METERS)
        assertEquals(ValueSource.USER_OVERRIDE, vm.state.value.committedAnnotations.single().valueSource)

        vm.beginAnnotationEdit()
        vm.updateAnnotationPoints(uuid, listOf(NormalizedPoint(0.1, 0.2), NormalizedPoint(0.6, 0.2)))
        rule.waitForIdle()
        rule.onNodeWithText("This value was edited by you. Clear it and use the measured length?").assertExists()
        assertNotNull(vm.overrideDragConfirm.value)
        activate("Clear and recompute")
        rule.waitForIdle()
        assertNull(vm.overrideDragConfirm.value)
        assertEquals(ValueSource.COMPUTED, vm.state.value.committedAnnotations.single().valueSource)
    }

    @Test
    fun overrideDrag_should_PutTheLineBackWhenTheValueIsKept() {
        val vm = show()
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.addPoint(NormalizedPoint(0.1, 0.2)); vm.addPoint(NormalizedPoint(0.4, 0.2))
        val a = vm.state.value.committedAnnotations.single()
        vm.submitValueEdit(a.uuid, "2 m", ValueEditMode.LABEL_ONLY, MeasurementUnit.METERS)
        vm.beginAnnotationEdit()
        vm.updateAnnotationPoints(a.uuid, listOf(NormalizedPoint(0.1, 0.2), NormalizedPoint(0.6, 0.2)))
        rule.waitForIdle()
        activate("Keep my value")
        rule.waitForIdle()
        assertEquals(a.normalizedPoints, vm.state.value.committedAnnotations.single().normalizedPoints)
        assertEquals(ValueSource.USER_OVERRIDE, vm.state.value.committedAnnotations.single().valueSource)
    }
}
