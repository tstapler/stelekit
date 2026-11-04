// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import kotlin.test.assertEquals

/** Drives the real gesture loop and asserts which pointer position reaches the ViewModel. */
@RunWith(RobolectricTestRunner::class)
class InProgressAnnotationCanvasGestureTest {
    @get:Rule
    val rule = createComposeRule()

    private val img = ImageAnnotation(
        uuid = "img-gesture-001",
        blockUuid = "blk-gesture-001",
        pageUuid = "page-gesture-001",
        graphPath = "/test",
        filePath = "/test/image.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
        unit = MeasurementUnit.METERS,
    )

    @Test
    fun downMoveUp_addsPointAtUpPosition_notAnEarlierDragPosition() {
        val vm = AnnotationEditorViewModel(
            measurementRepository = InMemoryMeasurementAnnotationRepository(),
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        ).also { it.initialize(img) }
        vm.selectTool(AnnotationTool.DISTANCE)
        rule.setContent { AnnotationEditorScreen(viewModel = vm, imageAnnotation = img) }
        rule.waitForIdle()

        val canvas = rule.onNodeWithContentDescription("Annotation canvas — tap to place points")
        val size = canvas.fetchSemanticsNode().size
        val downPos = Offset(300f, 20f)
        val midPos = Offset(304f, 26f)
        val upPos = Offset(308f, 23f) // clear of the overlay surfaces; all within the 16dp tap threshold

        // Measured: the first gesture injected into a fresh composition is dropped before the
        // pointer handler is listening; later ones land. Warm up, then reset.
        canvas.performTouchInput { down(downPos); up() }
        rule.waitForIdle()
        vm.cancelInProgress()

        canvas.performTouchInput {
            down(downPos)
            moveTo(midPos)
            moveTo(upPos)
            up()
        }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 5_000) { vm.state.value.inProgressPoints.isNotEmpty() }

        val points = vm.state.value.inProgressPoints
        assertEquals(1, points.size, "gesture must register exactly one tap")
        assertEquals(upPos.x.toDouble() / size.width, points[0].x, 1e-6)
        assertEquals(upPos.y.toDouble() / size.height, points[0].y, 1e-6)
    }
}
