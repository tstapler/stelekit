// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** X-10 (UX-10): one tool tap plus one drag makes a measurement, Select is active, and the next one-finger drag pans. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class OneShotToolFlowTest {
    @get:Rule
    val rule = createComposeRule()

    private val img = ImageAnnotation(
        uuid = "img-oneshot-001", blockUuid = "blk-oneshot-001", pageUuid = "page-oneshot-001",
        graphPath = "/test", filePath = "/test/image.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
        unit = MeasurementUnit.METERS,
    )

    private fun newVm() = AnnotationEditorViewModel(
        measurementRepository = InMemoryMeasurementAnnotationRepository(),
        imageWidthPx = 1000.0,
        imageHeightPx = 1000.0,
    ).also { it.initialize(img) }

    @Test
    fun tapDistanceThenDrag_should_CreateMeasurementReturnToSelectAndPanWithNoExtraTap() {
        val vm = newVm()
        rule.setContent { AnnotationEditorScreen(viewModel = vm, imageAnnotation = img) }
        rule.waitForIdle()
        val canvas = rule.onNodeWithContentDescription(ANNOTATION_CANVAS_DESCRIPTION)
        // The first injected gesture is dropped before the pointer handler listens (see AnnotationZoomPanTest).
        canvas.performTouchInput { down(Offset(300f, 20f)); up() }
        rule.waitForIdle()
        assertEquals(AnnotationTool.SELECT, vm.state.value.currentTool)

        rule.onNode(hasContentDescription("Distance")).performClick()
        // A chip waits out the double-tap window (Keep tool) before a single tap counts.
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(AnnotationTool.DISTANCE, vm.state.value.currentTool)

        canvas.performTouchInput { down(Offset(200f, 100f)); moveTo(Offset(350f, 100f)); moveTo(Offset(500f, 100f)); up() }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 5_000) { vm.state.value.committedAnnotations.isNotEmpty() }

        val measurement = vm.state.value.committedAnnotations.single()
        assertEquals(AnnotationTool.SELECT, vm.state.value.currentTool)
        assertEquals(measurement.uuid, vm.state.value.selectedAnnotationUuid)
        assertTrue(vm.state.value.inProgressPoints.isEmpty())
    }

    private var view by mutableStateOf(AnnotationViewTransform.Fit)

    @Test
    fun oneFingerDragOnEmptySpaceInSelect_should_PanTheZoomedViewWithoutSelectingOrPlacing() {
        val vm = newVm()
        val canvasPx = IntSize(1080, 2400)
        val geometry = ViewGeometry(1080f, 2400f, 0f, 0f, 1080f, 2400f)
        val handle = AnnotationViewHandle(
            canvasSize = { canvasPx },
            canvasOrigin = { Offset.Zero },
            view = { view },
            geometry = { geometry },
            setView = { view = it },
        )
        view = AnnotationViewTransform.Fit.stepped(2f, geometry)
        rule.setContent {
            val state by vm.state.collectAsState()
            Box(Modifier.fillMaxSize()) {
                AnnotationGestureLayer(
                    currentTool = state.currentTool,
                    annotations = state.committedAnnotations,
                    handle = handle,
                    callbacks = AnnotationGestureCallbacks(
                        onTap = {},
                        onLoupe = {},
                        onRipple = {},
                        onSelect = { uuid -> if (uuid == null) vm.clearSelection() else vm.selectAnnotation(uuid) },
                        onEditStart = {},
                        onEditPoints = { _, _ -> },
                        onEditEnd = {},
                    ),
                    modifier = Modifier.testTag("layer"),
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("layer").performTouchInput { down(Offset(540f, 1200f)); up() }
        rule.waitForIdle()

        val before = view
        rule.onNodeWithTag("layer").performTouchInput { down(Offset(540f, 1200f)); moveTo(Offset(500f, 1150f)); moveTo(Offset(440f, 1100f)); up() }
        rule.waitForIdle()

        assertNotEquals(before.offsetX, view.offsetX, "a one-finger drag pans")
        assertEquals(before.scale, view.scale)
        assertEquals(AnnotationTool.SELECT, vm.state.value.currentTool)
        assertTrue(vm.state.value.inProgressPoints.isEmpty())
    }
}
