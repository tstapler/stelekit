// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
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
    fun loupe_isShownWhileHeld_andRemovedOnRelease() {
        val vm = AnnotationEditorViewModel(
            measurementRepository = InMemoryMeasurementAnnotationRepository(),
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        ).also { it.initialize(img) }
        vm.selectTool(AnnotationTool.DISTANCE)
        rule.setContent { AnnotationEditorScreen(viewModel = vm, imageAnnotation = img) }
        rule.waitForIdle()

        val canvas = rule.onNodeWithContentDescription("Annotation canvas — tap to place points")
        // Same warm-up as above: the first injected gesture is dropped.
        canvas.performTouchInput { down(Offset(300f, 20f)); up() }
        rule.waitForIdle()
        vm.cancelInProgress()

        canvas.performTouchInput {
            down(Offset(300f, 60f))   // clear of the toolbar/hint overlay surfaces
            moveTo(Offset(304f, 70f))
        }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithTag(LOUPE_OVERLAY_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        canvas.performTouchInput { up() }
        rule.waitForIdle()
        rule.onNodeWithTag(LOUPE_OVERLAY_TAG).assertDoesNotExist()
    }

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

    /** A committed horizontal DISTANCE line at y=0.3 from x=0.2 to x=0.8, SELECT tool active. */
    private fun selectScenario(): Triple<AnnotationEditorViewModel, androidx.compose.ui.test.SemanticsNodeInteraction, androidx.compose.ui.unit.IntSize> {
        val vm = AnnotationEditorViewModel(
            measurementRepository = InMemoryMeasurementAnnotationRepository(),
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        ).also { it.initialize(img) }
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.addPoint(NormalizedPoint(0.2, 0.3))
        vm.addPoint(NormalizedPoint(0.8, 0.3))
        vm.selectTool(AnnotationTool.SELECT)
        rule.setContent { AnnotationEditorScreen(viewModel = vm, imageAnnotation = img) }
        rule.waitForIdle()
        val canvas = rule.onNodeWithContentDescription("Annotation canvas — tap to place points")
        // Warm-up: the first injected gesture is dropped (see tests above).
        canvas.performTouchInput { down(Offset(10f, 10f)); up() }
        rule.waitForIdle()
        return Triple(vm, canvas, canvas.fetchSemanticsNode().size)
    }

    @Test
    fun select_dragVertex_movesThatPointOnly_andIsOneUndoStep() {
        val (vm, canvas, size) = selectScenario()
        val start = Offset(0.2f * size.width, 0.3f * size.height)
        val end = Offset(0.4f * size.width, 0.5f * size.height)
        canvas.performTouchInput { down(start); moveTo(Offset(start.x + 60f, start.y + 40f)); moveTo(end); up() }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 5_000) { vm.state.value.committedAnnotations.single().normalizedPoints[0].x > 0.3 }

        val ann = vm.state.value.committedAnnotations.single()
        assertEquals(end.x.toDouble() / size.width, ann.normalizedPoints[0].x, 1e-6)
        assertEquals(end.y.toDouble() / size.height, ann.normalizedPoints[0].y, 1e-6)
        assertEquals(0.8, ann.normalizedPoints[1].x, 1e-9)
        assertEquals(ann.uuid, vm.state.value.selectedAnnotationUuid)
        vm.undo()
        assertEquals(0.2, vm.state.value.committedAnnotations.single().normalizedPoints[0].x, 1e-9)
    }

    @Test
    fun select_dragLineBody_translatesBothPoints() {
        val (vm, canvas, size) = selectScenario()
        val mid = Offset(0.5f * size.width, 0.3f * size.height)
        canvas.performTouchInput { down(mid); moveTo(Offset(mid.x + 25f, mid.y + 15f)); moveTo(Offset(mid.x + 40f, mid.y + 20f)); up() }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 5_000) { vm.state.value.committedAnnotations.single().normalizedPoints[0].x > 0.25 }

        val pts = vm.state.value.committedAnnotations.single().normalizedPoints
        val dx = 40.0 / size.width
        assertEquals(0.2 + dx, pts[0].x, 1e-6)
        assertEquals(0.8 + dx, pts[1].x, 1e-6)
        assertEquals(pts[0].y, pts[1].y, 1e-9)
    }

    @Test
    fun select_tapEmptySpace_clearsSelection() {
        val (vm, canvas, size) = selectScenario()
        val onLine = Offset(0.5f * size.width, 0.3f * size.height)
        canvas.performTouchInput { down(onLine); up() }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 5_000) { vm.state.value.selectedAnnotationUuid != null }

        canvas.performTouchInput { down(Offset(0.5f * size.width, 0.45f * size.height)); up() }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 5_000) { vm.state.value.selectedAnnotationUuid == null }
    }
}
