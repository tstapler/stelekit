package dev.stapler.stelekit.annotate

import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import dev.stapler.stelekit.ui.annotate.AnnotationTool
import dev.stapler.stelekit.ui.annotate.AnnotationEditorViewModel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [AnnotationEditorViewModel].
 *
 * All tests use [InMemoryMeasurementAnnotationRepository] so no DB setup is needed.
 */
class AnnotationEditorViewModelTest {

    private fun makeImageAnnotation(ppm: Double = 100.0) = ImageAnnotation(
        uuid = "img-001",
        blockUuid = "blk-001",
        pageUuid = "page-001",
        graphPath = "/test",
        filePath = "/test/image.jpg",
        calibration = Calibration(
            method = CalibrationMethod.MANUAL_REFERENCE,
            pixelsPerMeter = ppm,
            confidencePercent = 100,
        ),
        unit = MeasurementUnit.METERS,
    )

    private fun makeViewModel(ppm: Double = 100.0): AnnotationEditorViewModel {
        val repo = InMemoryMeasurementAnnotationRepository()
        return AnnotationEditorViewModel(
            measurementRepository = repo,
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        ).also { it.initialize(makeImageAnnotation(ppm)) }
    }

    // ── Tool selection ────────────────────────────────────────────────────────

    @Test
    fun selectTool_updatesCurrentTool() {
        val vm = makeViewModel()
        vm.selectTool(AnnotationTool.ANGLE)
        assertEquals(AnnotationTool.ANGLE, vm.state.value.currentTool)
    }

    @Test
    fun selectTool_clearsInProgressPoints() {
        val vm = makeViewModel()
        vm.addPoint(NormalizedPoint(0.1, 0.1))
        assertEquals(1, vm.state.value.inProgressPoints.size)

        vm.selectTool(AnnotationTool.AREA)
        assertTrue(vm.state.value.inProgressPoints.isEmpty())
    }

    // ── DISTANCE annotation ───────────────────────────────────────────────────

    @Test
    fun addPoint_distance_commitsAfterTwoPoints() {
        val vm = makeViewModel()
        vm.selectTool(AnnotationTool.DISTANCE)

        vm.addPoint(NormalizedPoint(0.0, 0.0))
        assertTrue(vm.state.value.committedAnnotations.isEmpty())
        assertEquals(1, vm.state.value.inProgressPoints.size)

        vm.addPoint(NormalizedPoint(0.1, 0.0))  // 100px horizontally
        assertEquals(1, vm.state.value.committedAnnotations.size)
        assertTrue(vm.state.value.inProgressPoints.isEmpty())
    }

    @Test
    fun commitDistance_computesCorrectMeters() {
        val vm = makeViewModel(ppm = 100.0)
        vm.updateCalibration(Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100))
        vm.selectTool(AnnotationTool.DISTANCE)

        // 0.0 to 0.1 on a 1000px-wide image → 100px → 1.0m at 100px/m
        vm.addPoint(NormalizedPoint(0.0, 0.5))
        vm.addPoint(NormalizedPoint(0.1, 0.5))

        val annotation = vm.state.value.committedAnnotations.first()
        assertEquals(AnnotationType.DISTANCE, annotation.annotationType)
        assertNotNull(annotation.valueMeters)
        assertEquals(1.0, annotation.valueMeters!!, 0.01)
    }

    @Test
    fun commitDistance_noCalibration_returnsNullMeters() {
        val repo = InMemoryMeasurementAnnotationRepository()
        val vm = AnnotationEditorViewModel(
            measurementRepository = repo,
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        )
        val image = makeImageAnnotation(0.0) // ppm=0 means no calibration
        vm.initialize(image)
        vm.selectTool(AnnotationTool.DISTANCE)

        vm.addPoint(NormalizedPoint(0.0, 0.0))
        vm.addPoint(NormalizedPoint(0.5, 0.0))

        val annotation = vm.state.value.committedAnnotations.firstOrNull()
        assertNotNull(annotation)
        assertNull(annotation.valueMeters)
    }

    // ── ANGLE annotation ──────────────────────────────────────────────────────

    @Test
    fun commitAngle_rightAngle_returns90Degrees() {
        val vm = makeViewModel()
        vm.selectTool(AnnotationTool.ANGLE)

        // arm1=(0.0,0.5), vertex=(0.5,0.5), arm2=(0.5,1.0) → 90°
        vm.addPoint(NormalizedPoint(0.0, 0.5))
        vm.addPoint(NormalizedPoint(0.5, 0.5))
        vm.addPoint(NormalizedPoint(0.5, 1.0))

        val annotation = vm.state.value.committedAnnotations.first()
        assertEquals(AnnotationType.ANGLE, annotation.annotationType)
        assertNotNull(annotation.valueMeters)
        assertEquals(90.0, annotation.valueMeters!!, 0.5)
    }

    // ── AREA annotation ───────────────────────────────────────────────────────

    @Test
    fun commitArea_squarePolygon_computesCorrectArea() {
        val vm = makeViewModel(ppm = 100.0)
        vm.updateCalibration(Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100))
        vm.selectTool(AnnotationTool.AREA)

        // 200×200 px square = 2m × 2m = 4 m² at 100px/m
        vm.addPoint(NormalizedPoint(0.1, 0.1))
        vm.addPoint(NormalizedPoint(0.3, 0.1))
        vm.addPoint(NormalizedPoint(0.3, 0.3))
        vm.addPoint(NormalizedPoint(0.1, 0.3))
        vm.closePolygon()

        assertEquals(1, vm.state.value.committedAnnotations.size)
        val annotation = vm.state.value.committedAnnotations.first()
        assertEquals(AnnotationType.AREA, annotation.annotationType)
        assertNotNull(annotation.valueMeters)
        assertEquals(4.0, annotation.valueMeters!!, 0.1)
    }

    // ── Undo / redo ───────────────────────────────────────────────────────────

    @Test
    fun undo_removesLastCommittedAnnotation() {
        val vm = makeViewModel()
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.addPoint(NormalizedPoint(0.0, 0.0))
        vm.addPoint(NormalizedPoint(0.1, 0.0))
        assertEquals(1, vm.state.value.committedAnnotations.size)
        assertTrue(vm.canUndo.value)

        vm.undo()
        assertTrue(vm.state.value.committedAnnotations.isEmpty())
        assertFalse(vm.canUndo.value)
        assertTrue(vm.canRedo.value)
    }

    @Test
    fun redo_restoresUndoneAnnotation() {
        val vm = makeViewModel()
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.addPoint(NormalizedPoint(0.0, 0.0))
        vm.addPoint(NormalizedPoint(0.1, 0.0))
        vm.undo()
        assertTrue(vm.state.value.committedAnnotations.isEmpty())

        vm.redo()
        assertEquals(1, vm.state.value.committedAnnotations.size)
    }

    @Test
    fun undoStack_limitedTo50() {
        val vm = makeViewModel()
        vm.selectTool(AnnotationTool.DISTANCE)

        // Commit 55 annotations — stack should cap at 50
        repeat(55) {
            val x = (it * 2 + 1).toDouble() / 1000.0
            vm.addPoint(NormalizedPoint(x.coerceAtMost(0.99), 0.1))
            vm.addPoint(NormalizedPoint((x + 0.01).coerceAtMost(1.0), 0.1))
        }
        assertEquals(55, vm.state.value.committedAnnotations.size)

        // Undo 55 times — should stop after 50 because that's the stack limit
        var undoCount = 0
        while (vm.canUndo.value && undoCount < 60) {
            vm.undo()
            undoCount++
        }
        assertTrue(undoCount <= 50, "Undo stack must not exceed 50 steps, got $undoCount")
    }

    @Test
    fun calibrationUndo_restoresAnnotationValues() {
        val vm = makeViewModel()
        vm.selectTool(AnnotationTool.DISTANCE)
        // Commit one annotation at default (uncalibrated) state
        vm.addPoint(NormalizedPoint(0.1, 0.1))
        vm.addPoint(NormalizedPoint(0.5, 0.1))
        assertEquals(1, vm.state.value.committedAnnotations.size)

        // Apply first calibration
        val cal1 = dev.stapler.stelekit.model.Calibration(
            method = dev.stapler.stelekit.model.CalibrationMethod.MANUAL_REFERENCE,
            pixelsPerMeter = 100.0,
            confidencePercent = 90,
        )
        vm.updateCalibration(cal1)
        assertEquals(100.0, vm.state.value.calibration?.pixelsPerMeter)
        assertTrue(vm.canUndoCalibration.value)

        // Apply second calibration — snapshot of first is now in history
        val cal2 = dev.stapler.stelekit.model.Calibration(
            method = dev.stapler.stelekit.model.CalibrationMethod.BLE_LASER,
            pixelsPerMeter = 200.0,
            confidencePercent = 100,
        )
        vm.updateCalibration(cal2)
        assertEquals(200.0, vm.state.value.calibration?.pixelsPerMeter)

        // Undo — should restore cal1
        vm.undoCalibration()
        assertEquals(100.0, vm.state.value.calibration?.pixelsPerMeter)
        assertFalse(vm.canUndoCalibration.value)
    }

    @Test
    fun minLengthGuard_dropsLineShorterThanThreshold() {
        val vm = makeViewModel()
        vm.selectTool(AnnotationTool.DISTANCE)

        // Commit a very short line (normalized distance ≈ 0.001 — well below 0.005 threshold)
        vm.addPoint(NormalizedPoint(0.5, 0.5))
        vm.addPoint(NormalizedPoint(0.5005, 0.5)) // dx = 0.0005, length = 0.0005
        assertEquals(0, vm.state.value.committedAnnotations.size, "Line shorter than 0.005 threshold should be dropped")

        // Commit a line at the threshold (normalized distance = 0.01 — above threshold)
        vm.addPoint(NormalizedPoint(0.1, 0.1))
        vm.addPoint(NormalizedPoint(0.11, 0.1)) // dx = 0.01, length = 0.01
        assertEquals(1, vm.state.value.committedAnnotations.size, "Line of 0.01 length should be committed")
    }

    // ── DELETE ────────────────────────────────────────────────────────────────

    @Test
    fun deleteAnnotation_removesFromState() {
        val vm = makeViewModel()
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.addPoint(NormalizedPoint(0.0, 0.0))
        vm.addPoint(NormalizedPoint(0.1, 0.0))
        val uuid = vm.state.value.committedAnnotations.first().uuid

        vm.deleteAnnotation(uuid)
        assertTrue(vm.state.value.committedAnnotations.isEmpty())
    }

    // ── LABEL tool ────────────────────────────────────────────────────────────

    @Test
    fun labelTool_showsInputOverlay_onFirstTap() {
        val vm = makeViewModel()
        vm.selectTool(AnnotationTool.LABEL)
        assertFalse(vm.state.value.isLabelInputVisible)

        vm.addPoint(NormalizedPoint(0.5, 0.5))
        assertTrue(vm.state.value.isLabelInputVisible)
        assertNotNull(vm.state.value.pendingLabelPoint)
    }

    @Test
    fun labelTool_confirmLabel_commitsAnnotation() {
        val vm = makeViewModel()
        vm.selectTool(AnnotationTool.LABEL)
        vm.addPoint(NormalizedPoint(0.5, 0.5))
        vm.updateLabelText("Window width")
        vm.confirmLabel()

        assertFalse(vm.state.value.isLabelInputVisible)
        assertEquals(1, vm.state.value.committedAnnotations.size)
        val annotation = vm.state.value.committedAnnotations.first()
        assertEquals(AnnotationType.LABEL, annotation.annotationType)
        assertEquals("Window width", annotation.label)
    }

    @Test
    fun labelTool_dismissLabel_doesNotCommit() {
        val vm = makeViewModel()
        vm.selectTool(AnnotationTool.LABEL)
        vm.addPoint(NormalizedPoint(0.5, 0.5))
        vm.dismissLabelInput()

        assertFalse(vm.state.value.isLabelInputVisible)
        assertTrue(vm.state.value.committedAnnotations.isEmpty())
    }
}
