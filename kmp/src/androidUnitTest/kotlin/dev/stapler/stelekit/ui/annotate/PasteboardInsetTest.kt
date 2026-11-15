// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import kotlin.test.assertTrue

/** I-E2-04: the canvas viewport sits inside the system-gesture inset so the outermost pixel is reachable by a press. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class PasteboardInsetTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val img = ImageAnnotation(
        uuid = "img-pasteboard-001", blockUuid = "blk-pasteboard-001", pageUuid = "page-pasteboard-001",
        graphPath = "/test", filePath = "/test/image.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
        unit = MeasurementUnit.METERS,
    )

    private fun launch(gestureInsetDp: Int): AnnotationEditorViewModel {
        val vm = AnnotationEditorViewModel(
            measurementRepository = InMemoryMeasurementAnnotationRepository(),
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        ).also { it.initialize(img) }
        vm.selectTool(AnnotationTool.DISTANCE)
        rule.setContent { AnnotationEditorScreen(viewModel = vm, imageAnnotation = img) }
        val px = gestureInsetDp * XHDPI
        rule.runOnUiThread {
            val content = rule.activity.findViewById<View>(android.R.id.content)
            ViewCompat.dispatchApplyWindowInsets(
                content,
                WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemGestures(), Insets.of(px, 0, px, 0)).build(),
            )
        }
        rule.waitForIdle()
        return vm
    }

    private fun canvas() = rule.onNodeWithContentDescription(ANNOTATION_CANVAS_DESCRIPTION)

    @Test
    fun withA28dpGestureInset_theCanvasStartsInsideItOnBothSides() {
        launch(GESTURE_INSET_DP)
        val bounds = canvas().getUnclippedBoundsInRoot()
        val rootWidth = rule.onRoot().fetchSemanticsNode().size.width
        assertTrue(bounds.left >= GESTURE_INSET_DP.dp, "left ${bounds.left} must clear the inset")
        assertTrue(bounds.right <= (rootWidth / XHDPI - GESTURE_INSET_DP).dp, "right ${bounds.right} must clear the inset")
    }

    @Test
    fun withA28dpGestureInset_aPressOnTheOutermostPixelPlacesAtXZero() {
        val vm = launch(GESTURE_INSET_DP)
        // The first injected gesture is dropped before the pointer handler listens (see AnnotationZoomPanTest).
        canvas().performTouchInput { down(Offset(40f, 20f)); up() }
        rule.waitForIdle()
        vm.cancelInProgress()
        canvas().performTouchInput { down(Offset(0f, height / 2f)); up() }
        rule.waitForIdle()
        assertEquals(0.0, vm.state.value.inProgressPoints.single().x, 1e-9)
    }

    @Test
    fun withZeroInset_theLayoutIsUnchanged() {
        launch(0)
        assertEquals(0.dp, canvas().getUnclippedBoundsInRoot().left)
    }

    private companion object {
        const val GESTURE_INSET_DP = 28
        const val XHDPI = 2
    }
}
