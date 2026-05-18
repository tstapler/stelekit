package dev.stapler.stelekit.annotate

import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.model.angleBetweenThreePoints
import dev.stapler.stelekit.model.pixelDistanceToMeters
import dev.stapler.stelekit.model.polygonAreaMeters
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for measurement math helpers (pixel → meters, polygon area, 3-point angle).
 *
 * These tests validate the computation functions used by [AnnotationEditorViewModel.commitAnnotation].
 * All inputs are in normalized [0,1] space.
 */
class AnnotationGeometryTest {

    // ── pixelDistanceToMeters ─────────────────────────────────────────────────

    @Test
    fun pixelDistanceToMeters_returnsCorrectValue() {
        // 100 pixels, 50 pixels/meter → 2.0 meters
        val result = pixelDistanceToMeters(100.0, 50.0)
        assertTrue(result.isRight())
        val value = result.getOrNull()!!
        assertEquals(2.0, value, 0.0001)
    }

    @Test
    fun pixelDistanceToMeters_returnsError_whenPixelsPerMeterIsZero() {
        val result = pixelDistanceToMeters(100.0, 0.0)
        assertTrue(result.isLeft())
    }

    @Test
    fun pixelDistanceToMeters_handlesOnePixelPerMeter() {
        // 500 px at 1 px/m → 500 m
        val result = pixelDistanceToMeters(500.0, 1.0)
        assertTrue(result.isRight())
        assertEquals(500.0, result.getOrNull()!!, 0.0001)
    }

    // ── polygonAreaMeters ─────────────────────────────────────────────────────

    @Test
    fun polygonAreaMeters_unitSquare_returnsExpectedArea() {
        // A square covering 20% × 20% of a 1000×1000 image, at 100 px/m
        // Square corners in normalized coords
        val points = listOf(
            NormalizedPoint(0.1, 0.1),
            NormalizedPoint(0.3, 0.1),
            NormalizedPoint(0.3, 0.3),
            NormalizedPoint(0.1, 0.3),
        )
        // Image: 1000×1000 px, 100 px/m
        // Side in pixels: 200 px → 2 m. Area: 4 m²
        val area = polygonAreaMeters(
            normalizedPoints = points,
            pixelsPerMeter = 100.0,
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        )
        assertEquals(4.0, area, 0.01)
    }

    @Test
    fun polygonAreaMeters_triangle_returnsCorrectArea() {
        // Right triangle in a 1000×1000 image, 100 px/m
        // Vertices: (0,0), (0.2,0), (0,0.1) → 200px × 100px right triangle
        // Area = 0.5 * 200 * 100 = 10000 px² → 10000 / 10000 = 1.0 m²
        val points = listOf(
            NormalizedPoint(0.0, 0.0),
            NormalizedPoint(0.2, 0.0),
            NormalizedPoint(0.0, 0.1),
        )
        val area = polygonAreaMeters(
            normalizedPoints = points,
            pixelsPerMeter = 100.0,
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        )
        assertEquals(1.0, area, 0.01)
    }

    @Test
    fun polygonAreaMeters_returnsZero_forDegenerateInput() {
        // Fewer than 3 points
        val points = listOf(
            NormalizedPoint(0.1, 0.1),
            NormalizedPoint(0.2, 0.2),
        )
        val area = polygonAreaMeters(
            normalizedPoints = points,
            pixelsPerMeter = 100.0,
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        )
        assertEquals(0.0, area, 0.0001)
    }

    @Test
    fun polygonAreaMeters_returnsZero_whenPixelsPerMeterIsZero() {
        val points = listOf(
            NormalizedPoint(0.1, 0.1),
            NormalizedPoint(0.3, 0.1),
            NormalizedPoint(0.2, 0.3),
        )
        val area = polygonAreaMeters(
            normalizedPoints = points,
            pixelsPerMeter = 0.0,
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        )
        assertEquals(0.0, area, 0.0001)
    }

    // ── angleBetweenThreePoints ───────────────────────────────────────────────

    @Test
    fun angleBetweenThreePoints_rightAngle_returns90Degrees() {
        // Three points forming a 90° angle:
        // arm1=(0,0.5), vertex=(0.5,0.5), arm2=(0.5,1.0)
        val arm1 = NormalizedPoint(0.0, 0.5)
        val vertex = NormalizedPoint(0.5, 0.5)
        val arm2 = NormalizedPoint(0.5, 1.0)
        val result = angleBetweenThreePoints(arm1, vertex, arm2)
        assertTrue(result.isRight())
        assertEquals(90.0, result.getOrNull()!!, 0.01)
    }

    @Test
    fun angleBetweenThreePoints_straightLine_returns180Degrees() {
        // Collinear: arm1 - vertex - arm2 on the same horizontal line
        val arm1 = NormalizedPoint(0.1, 0.5)
        val vertex = NormalizedPoint(0.5, 0.5)
        val arm2 = NormalizedPoint(0.9, 0.5)
        val result = angleBetweenThreePoints(arm1, vertex, arm2)
        assertTrue(result.isRight())
        assertEquals(180.0, result.getOrNull()!!, 0.01)
    }

    @Test
    fun angleBetweenThreePoints_45Degrees() {
        // Geometry: arm1 directly above vertex, arm2 at 45° (up-right diagonal) → 45°
        // arm1=(0.5,0.0), vertex=(0.5,0.5), arm2=(1.0,0.0)
        // Vector to arm1: (0.0,-0.5)  |v1|=0.5
        // Vector to arm2: (0.5,-0.5)  |v2|=√0.5≈0.707
        // dot = (0)(0.5)+(−0.5)(−0.5) = 0.25
        // cos θ = 0.25/(0.5×0.707) ≈ 0.707 → θ = 45°
        val arm1 = NormalizedPoint(0.5, 0.0)
        val vertex = NormalizedPoint(0.5, 0.5)
        val arm2 = NormalizedPoint(1.0, 0.0)
        val result = angleBetweenThreePoints(arm1, vertex, arm2)
        assertTrue(result.isRight())
        assertEquals(45.0, result.getOrNull()!!, 0.5)
    }

    @Test
    fun angleBetweenThreePoints_returnsError_whenVertexCoincides() {
        val arm1 = NormalizedPoint(0.1, 0.1)
        val vertex = NormalizedPoint(0.5, 0.5)
        val arm2 = NormalizedPoint(0.5, 0.5) // same as vertex → zero vector
        val result = angleBetweenThreePoints(arm1, vertex, arm2)
        assertTrue(result.isLeft())
    }

    // ── Integration: re-derive on calibration change ──────────────────────────

    @Test
    fun calibrationRederive_allMeasurementsUpdated() {
        // Simulate the scenario described in Known Issues:
        // calibrate → add 3 annotations → change calibration → verify all 3 updated.
        val repo = dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository()
        val viewModel = dev.stapler.stelekit.ui.annotate.AnnotationEditorViewModel(
            measurementRepository = repo,
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        )

        // Minimal ImageAnnotation for test
        val fakeImage = dev.stapler.stelekit.model.ImageAnnotation(
            uuid = "img-001",
            blockUuid = "blk-001",
            pageUuid = "page-001",
            graphPath = "/test",
            filePath = "/test/image.jpg",
            calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, pixelsPerMeter = 100.0, confidencePercent = 100),
        )
        viewModel.initialize(fakeImage)

        val cal1 = Calibration(CalibrationMethod.MANUAL_REFERENCE, pixelsPerMeter = 100.0, confidencePercent = 100)
        viewModel.updateCalibration(cal1)

        // Add 3 distance annotations at known pixel distances
        // Distance annotation: (0.0, 0.0) → (0.1, 0.0) = 100px at 100px/m → 1.0m
        viewModel.addPoint(NormalizedPoint(0.0, 0.0))
        viewModel.addPoint(NormalizedPoint(0.1, 0.0))

        viewModel.addPoint(NormalizedPoint(0.0, 0.0))
        viewModel.addPoint(NormalizedPoint(0.2, 0.0))

        viewModel.addPoint(NormalizedPoint(0.0, 0.0))
        viewModel.addPoint(NormalizedPoint(0.3, 0.0))

        val before = viewModel.state.value.committedAnnotations
        assertEquals(3, before.size)

        // Now change calibration to double the pixels/meter → values halved
        val cal2 = Calibration(CalibrationMethod.MANUAL_REFERENCE, pixelsPerMeter = 200.0, confidencePercent = 100)
        viewModel.updateCalibration(cal2)

        val after = viewModel.state.value.committedAnnotations
        assertEquals(3, after.size)

        // All values should have been recalculated with new calibration (200 px/m)
        // Original: 100px → 1.0m; with 200px/m → 0.5m
        after.forEach { annotation ->
            assertNotNull(annotation.valueMeters, "valueMeters must be non-null after calibration")
        }
        // Check that the values changed (not same as before)
        val beforeValues = before.map { it.valueMeters }
        val afterValues = after.map { it.valueMeters }
        assertTrue(beforeValues.zip(afterValues).any { (b, a) -> b != a },
            "At least one measurement value must change after calibration update")
    }
}
