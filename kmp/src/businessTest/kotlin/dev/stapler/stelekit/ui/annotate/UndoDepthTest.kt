// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.platform.ml.MonocularDepthEstimator
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import arrow.core.Either
import androidx.compose.ui.graphics.ImageBitmap
import dev.stapler.stelekit.error.DomainError
import kotlin.test.Test
import kotlin.test.assertEquals

/** The `undoDepth` seam the benchmark probe reads for its `undoDepth <= 50` gate (plan B.2c). */
class UndoDepthTest {
    private object UnusedDepthEstimator : MonocularDepthEstimator {
        override val isAvailable = false
        override suspend fun initialize(): Either<DomainError, Unit> = Either.Right(Unit)
        override suspend fun estimateDepth(imageBitmap: ImageBitmap): Either<DomainError, FloatArray> =
            Either.Right(FloatArray(0))
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
    ).also { it.initialize(image) }

    private fun commitDistance(vm: AnnotationEditorViewModel) {
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.addPoint(NormalizedPoint(0.0, 0.5))
        vm.addPoint(NormalizedPoint(0.1, 0.5))
    }

    @Test
    fun undoDepth_should_EqualCommitCount_When_FourCommits() {
        val vm = newViewModel()
        repeat(4) { commitDistance(vm) }
        assertEquals(4, vm.undoDepth)
        vm.close()
    }

    @Test
    fun undoDepth_should_CapAtFifty_When_MoreCommitsThanHistory() {
        val vm = newViewModel()
        repeat(60) { commitDistance(vm) }
        assertEquals(50, vm.undoDepth)
        vm.close()
    }
}
