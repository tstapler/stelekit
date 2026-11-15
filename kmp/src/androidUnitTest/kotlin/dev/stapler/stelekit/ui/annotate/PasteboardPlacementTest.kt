// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
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
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** X-19 (UX-19): a press on the pasteboard, outside the zoomed-out image, stores the outermost pixel: exactly 0 or 1. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class PasteboardPlacementTest {
    @get:Rule
    val rule = createComposeRule()

    private val img = ImageAnnotation(
        uuid = "img-pasteplace-001", blockUuid = "blk-pasteplace-001", pageUuid = "page-pasteplace-001",
        graphPath = "/test", filePath = "/test/image.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
        unit = MeasurementUnit.METERS,
    )

    @Test
    fun dragIntoPasteboard_should_StoreExactly0Or1() {
        val vm = AnnotationEditorViewModel(
            measurementRepository = InMemoryMeasurementAnnotationRepository(),
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        ).also { it.initialize(img) }
        vm.selectTool(AnnotationTool.DISTANCE)
        rule.setContent { AnnotationEditorScreen(viewModel = vm, imageAnnotation = img) }
        rule.waitForIdle()
        val canvas = rule.onNodeWithContentDescription(ANNOTATION_CANVAS_DESCRIPTION)
        // The first injected gesture is dropped before the pointer handler listens (see AnnotationZoomPanTest).
        canvas.performTouchInput { down(Offset(300f, 20f)); up() }
        rule.waitForIdle()
        vm.cancelInProgress()

        repeat(2) {
            rule.onNodeWithContentDescription(ZOOM_OUT_DESCRIPTION).performClick()
            rule.waitForIdle()
        }
        // Zoomed out to about 44 percent the image is a centred rectangle; both corners of the viewport are pasteboard.
        canvas.performTouchInput { down(Offset(2f, 2f)); up() }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 5_000) { vm.state.value.inProgressPoints.isNotEmpty() }
        val topLeft = vm.state.value.inProgressPoints.single()
        assertEquals(0.0, topLeft.x)
        assertEquals(0.0, topLeft.y)

        vm.cancelInProgress()
        canvas.performTouchInput { down(Offset(width - 2f, height - 2f)); up() }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 5_000) { vm.state.value.inProgressPoints.isNotEmpty() }
        val bottomRight = vm.state.value.inProgressPoints.single()
        assertEquals(1.0, bottomRight.x)
        assertEquals(1.0, bottomRight.y)
    }
}
