package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.graphics.ImageBitmap
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.platform.ml.MonocularDepthEstimator
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import dev.stapler.stelekit.ui.placement.NudgeDirection
import dev.stapler.stelekit.ui.placement.NudgeStep
import dev.stapler.stelekit.ui.placement.PlacementState
import dev.stapler.stelekit.ui.placement.PxOffset
import dev.stapler.stelekit.ui.placement.crosshairPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnnotationPlacementTest {
    private val space = AnnotationPlacementSpace(1000, 500)

    @Test
    fun toggleOnShowsCenterCrosshairAndOffClears() {
        val on = AnnotationPlacementSession().toggled(space)
        assertTrue(on.active)
        assertEquals(NormalizedPoint(0.5, 0.5), on.state.crosshairPoint)
        assertFalse(on.toggled(space).active)
    }

    @Test
    fun nudgeMovesByPixelsInNormalisedUnits() {
        val moved = AnnotationPlacementSession().toggled(space).nudged(space, NudgeDirection.RIGHT, NudgeStep.TEN_PIXELS)
        assertEquals(NormalizedPoint(0.51, 0.5), moved.state.crosshairPoint)
        val down = moved.nudged(space, NudgeDirection.DOWN, NudgeStep.ONE_PERCENT)
        assertEquals(NormalizedPoint(0.51, 0.52), down.state.crosshairPoint)
    }

    @Test
    fun tapOnlyMovesAndNeverCommits() {
        val tapped = AnnotationPlacementSession().toggled(space).tapped(space, NormalizedPoint(0.2, 0.3))
        assertEquals(NormalizedPoint(0.2, 0.3), tapped.state.crosshairPoint)
        assertNull(AnnotationPlacementSession().confirmed(space).second)
    }

    @Test
    fun confirmPlacesAndKeepsTheCrosshairForTheNextPoint() {
        val aimed = AnnotationPlacementSession().toggled(space).tapped(space, NormalizedPoint(0.2, 0.3))
        val (next, commit) = aimed.confirmed(space)
        assertEquals(AnnotationPlacementCommit.Place(NormalizedPoint(0.2, 0.3)), commit)
        assertTrue(next.state is PlacementState.Placed)
        assertTrue(next.nudged(space, NudgeDirection.LEFT, NudgeStep.ONE_PIXEL).state is PlacementState.Crosshair)
    }

    @Test
    fun startMoveJumpsToTheVertexAndConfirmMovesItThenEndsTheMode() {
        val target = VertexTarget("a", 1)
        val moving = AnnotationPlacementSession().startMove(space, target, NormalizedPoint(0.4, 0.4))
            .nudged(space, NudgeDirection.UP, NudgeStep.TEN_PIXELS)
        val (next, commit) = moving.confirmed(space)
        assertEquals(AnnotationPlacementCommit.Move(target, NormalizedPoint(0.4, 0.38)), commit)
        assertFalse(next.active)
    }

    @Test
    fun respacedKeepsThePointAndZeroSizedCanvasIsSafe() {
        val aimed = AnnotationPlacementSession().toggled(space).tapped(space, NormalizedPoint(0.2, 0.3))
        assertEquals(NormalizedPoint(0.2, 0.3), aimed.respaced(AnnotationPlacementSpace(300, 300)).state.crosshairPoint)
        val zero = AnnotationPlacementSpace(0, 0)
        assertEquals(NormalizedPoint(1.0, 1.0), zero.shift(zero.center(), PxOffset(5, 5)))
    }

    @Test
    fun replaceVertexIgnoresOutOfRangeIndex() {
        val pts = listOf(NormalizedPoint(0.0, 0.0), NormalizedPoint(1.0, 1.0))
        assertEquals(listOf(NormalizedPoint(0.0, 0.0), NormalizedPoint(0.5, 0.5)), replaceVertex(pts, 1, NormalizedPoint(0.5, 0.5)))
        assertEquals(pts, replaceVertex(pts, 5, NormalizedPoint(0.5, 0.5)))
    }

    // ── ViewModel wiring ──────────────────────────────────────────────────────

    private object UnusedDepthEstimator : MonocularDepthEstimator {
        override val isAvailable = false
        override suspend fun initialize(): Either<DomainError, Unit> = Either.Right(Unit)
        override suspend fun estimateDepth(imageBitmap: ImageBitmap): Either<DomainError, FloatArray> = Either.Right(FloatArray(0))
    }

    private val image = ImageAnnotation(
        uuid = "img-001",
        blockUuid = "blk-001",
        pageUuid = "page-001",
        graphPath = "/test",
        filePath = "/test/image.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
        unit = MeasurementUnit.METERS,
    )

    private fun newViewModel() = AnnotationEditorViewModel(
        measurementRepository = InMemoryMeasurementAnnotationRepository(),
        imageWidthPx = 1000.0,
        imageHeightPx = 1000.0,
        monocularDepthEstimator = UnusedDepthEstimator,
    ).also { it.initialize(image); it.selectTool(AnnotationTool.DISTANCE) }

    @Test
    fun crosshairConfirmsTwoPointsIntoOneDistanceAndHapticsCallerSeesTheCommit() {
        val vm = newViewModel()
        vm.togglePlacementMode(space)
        vm.tapCrosshair(space, NormalizedPoint(0.1, 0.5))
        assertTrue(vm.confirmCrosshair(space))
        assertEquals(1, vm.state.value.inProgressPoints.size)
        vm.nudgeCrosshair(space, NudgeDirection.RIGHT, NudgeStep.ONE_PERCENT)
        assertTrue(vm.confirmCrosshair(space))
        assertEquals(1, vm.state.value.committedAnnotations.size)
        assertTrue(vm.state.value.placement.active)
        vm.cancelPlacement()
        assertFalse(vm.state.value.placement.active)
        assertFalse(vm.confirmCrosshair(space))
        vm.close()
    }

    @Test
    fun beginVertexMoveThenConfirmMovesThatVertexAndUndoRestoresIt() {
        val vm = newViewModel()
        vm.addPoint(NormalizedPoint(0.1, 0.5))
        vm.addPoint(NormalizedPoint(0.3, 0.5))
        val uuid = vm.state.value.committedAnnotations.single().uuid
        assertFalse(vm.beginVertexMove(space, uuid, 9))
        assertFalse(vm.beginVertexMove(space, "missing", 0))
        assertTrue(vm.beginVertexMove(space, uuid, 1))
        vm.tapCrosshair(space, NormalizedPoint(0.6, 0.5))
        assertTrue(vm.confirmCrosshair(space))
        assertEquals(NormalizedPoint(0.6, 0.5), vm.state.value.committedAnnotations.single().normalizedPoints[1])
        assertFalse(vm.state.value.placement.active)
        vm.undo()
        assertEquals(NormalizedPoint(0.3, 0.5), vm.state.value.committedAnnotations.single().normalizedPoints[1])
        vm.close()
    }

    @Test
    fun switchingToSelectEndsPlacementMode() {
        val vm = newViewModel()
        vm.togglePlacementMode(space)
        vm.selectTool(AnnotationTool.SELECT)
        assertFalse(vm.state.value.placement.active)
        vm.close()
    }
}
