// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.performTouchInput
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Zoom controls and pinch change the view, and a tap while zoomed lands on the right normalized point. */
@RunWith(RobolectricTestRunner::class)
class AnnotationZoomPanTest {
    @get:Rule
    val rule = createComposeRule()

    private val img = ImageAnnotation(
        uuid = "img-zoom-001",
        blockUuid = "blk-zoom-001",
        pageUuid = "page-zoom-001",
        graphPath = "/test",
        filePath = "/test/image.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
        unit = MeasurementUnit.METERS,
    )

    private fun launch(tool: AnnotationTool): AnnotationEditorViewModel {
        val vm = AnnotationEditorViewModel(
            measurementRepository = InMemoryMeasurementAnnotationRepository(),
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        ).also { it.initialize(img) }
        vm.selectTool(tool)
        rule.setContent { AnnotationEditorScreen(viewModel = vm, imageAnnotation = img) }
        rule.waitForIdle()
        // The first injected gesture is dropped before the pointer handler listens (see the gesture tests).
        rule.onNodeWithContentDescription(ANNOTATION_CANVAS_DESCRIPTION).performTouchInput { down(Offset(300f, 20f)); up() }
        rule.waitForIdle()
        vm.cancelInProgress()
        return vm
    }

    @Test
    fun zoomControlsStartAtFitAndEnableOnceZoomed() {
        launch(AnnotationTool.DISTANCE)
        rule.onNodeWithContentDescription(ZOOM_FIT_DESCRIPTION).assertIsNotEnabled()
        rule.onNodeWithContentDescription(ZOOM_OUT_DESCRIPTION).assertIsNotEnabled()
        rule.onNodeWithContentDescription(ZOOM_IN_DESCRIPTION).performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription(ZOOM_FIT_DESCRIPTION).assertIsEnabled()
        // Semantic click: the Robolectric screen is small enough for the toolbar to overlap the lowest button.
        rule.onNodeWithContentDescription(ZOOM_FIT_DESCRIPTION).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithContentDescription(ZOOM_FIT_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun pinchZoomsTheViewWithoutPlacingAPoint() {
        val vm = launch(AnnotationTool.DISTANCE)
        val canvas = rule.onNodeWithContentDescription(ANNOTATION_CANVAS_DESCRIPTION)
        val size = canvas.fetchSemanticsNode().size
        val cx = size.width / 2f
        val cy = size.height / 2f
        canvas.performTouchInput {
            pinch(Offset(cx - 50f, cy), Offset(cx - 150f, cy), Offset(cx + 50f, cy), Offset(cx + 150f, cy))
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription(ZOOM_FIT_DESCRIPTION).assertIsEnabled()
        assertTrue(vm.state.value.inProgressPoints.isEmpty(), "a two-finger gesture must not place a point")
    }

    @Test
    fun tapWhileZoomedFourStepsPlacesAtTheCorrectNormalizedPoint() {
        val vm = launch(AnnotationTool.DISTANCE)
        repeat(ZOOM_STEPS) {
            rule.onNodeWithContentDescription(ZOOM_IN_DESCRIPTION).performClick()
            rule.waitForIdle()
        }
        val canvas = rule.onNodeWithContentDescription(ANNOTATION_CANVAS_DESCRIPTION)
        val size = canvas.fetchSemanticsNode().size
        val scale = ZOOM_STEP_FACTOR.pow(ZOOM_STEPS).toDouble()
        // Zoom buttons scale about the viewport centre, which is the canvas centre here (no image loaded).
        val dx = 60f
        val dy = -40f
        canvas.performTouchInput { down(Offset(size.width / 2f + dx, size.height / 2f + dy)); up() }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 5_000) { vm.state.value.inProgressPoints.isNotEmpty() }

        val point = vm.state.value.inProgressPoints.single()
        assertEquals(0.5 + dx / (size.width * scale), point.x, 1e-3)
        assertEquals(0.5 + dy / (size.height * scale), point.y, 1e-3)
    }

    private companion object {
        const val ZOOM_STEPS = 4
    }
}
