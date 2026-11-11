// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import arrow.core.Either
import androidx.compose.ui.graphics.ImageBitmap
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.platform.ml.MonocularDepthEstimator
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UndoHistoryTest {
    private object UnusedDepthEstimator : MonocularDepthEstimator {
        override val isAvailable = false
        override suspend fun initialize(): Either<DomainError, Unit> = Either.Right(Unit)
        override suspend fun estimateDepth(imageBitmap: ImageBitmap): Either<DomainError, FloatArray> =
            Either.Right(FloatArray(0))
    }

    private val image = ImageAnnotation(
        uuid = "img-001", blockUuid = "blk-001", pageUuid = "page-001", graphPath = "/test",
        filePath = "/test/image.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
        unit = MeasurementUnit.METERS,
    )

    private fun newViewModel() = AnnotationEditorViewModel(
        measurementRepository = InMemoryMeasurementAnnotationRepository(),
        imageWidthPx = 1000.0,
        imageHeightPx = 1000.0,
        monocularDepthEstimator = UnusedDepthEstimator,
    ).also { it.initialize(image) }

    private fun commitDistance(vm: AnnotationEditorViewModel) {
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.addPoint(NormalizedPoint(0.0, 0.5))
        vm.addPoint(NormalizedPoint(0.1, 0.5))
    }

    @Test
    fun history_undo_redo_round_trips_and_caps() {
        val h = UndoHistory<Int>(maxHistory = 2)
        h.push(1); h.push(2); h.push(3)
        assertEquals(2, h.undoDepth)
        assertEquals(3, h.undo(4))
        assertTrue(h.canRedo)
        assertEquals(4, h.redo(3))
        h.push(9)
        assertTrue(!h.canRedo)
        assertNull(UndoHistory<Int>().undo(0))
    }

    @Test
    fun undo_after_commit_leaves_no_in_progress_points_and_keeps_tool() {
        val vm = newViewModel()
        commitDistance(vm)
        vm.addPoint(NormalizedPoint(0.5, 0.5))
        assertEquals(1, vm.state.value.inProgressPoints.size)
        vm.undo()
        assertEquals(0, vm.state.value.committedAnnotations.size)
        assertTrue(vm.state.value.inProgressPoints.isEmpty())
        assertEquals(AnnotationTool.DISTANCE, vm.state.value.currentTool)
        vm.close()
    }

    @Test
    fun tap_after_undo_starts_a_fresh_one_point_in_progress() {
        val vm = newViewModel()
        commitDistance(vm)
        vm.undo()
        vm.addPoint(NormalizedPoint(0.5, 0.5))
        assertEquals(1, vm.state.value.inProgressPoints.size)
        assertEquals(0, vm.state.value.committedAnnotations.size)
        vm.close()
    }

    @Test
    fun redo_never_restores_in_progress_points() {
        val vm = newViewModel()
        commitDistance(vm)
        vm.undo()
        vm.addPoint(NormalizedPoint(0.5, 0.5))
        vm.redo()
        assertEquals(1, vm.state.value.committedAnnotations.size)
        assertTrue(vm.state.value.inProgressPoints.isEmpty())
        vm.close()
    }

    @Test
    fun undo_all_empties_committed_and_in_progress_for_any_op_sequence() = runTest {
        // op: 0 = commit a distance, 1 = place one stray point, 2 = undo, 3 = redo
        checkAll(Arb.list(Arb.int(0..3), 0..30)) { ops ->
            val vm = newViewModel()
            vm.selectTool(AnnotationTool.DISTANCE)
            for (op in ops) when (op) {
                0 -> commitDistance(vm)
                1 -> vm.addPoint(NormalizedPoint(0.7, 0.2))
                2 -> vm.undo()
                else -> vm.redo()
            }
            commitDistance(vm) // guarantees at least one undo step so the last undo lands on a snapshot
            repeat(60) { vm.undo() }
            assertTrue(vm.state.value.committedAnnotations.isEmpty())
            assertTrue(vm.state.value.inProgressPoints.isEmpty())
            vm.close()
        }
    }
}
