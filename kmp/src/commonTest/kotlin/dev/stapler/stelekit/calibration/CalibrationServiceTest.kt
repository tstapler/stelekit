package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.NormalizedPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for [CalibrationService.computeFromReference].
 *
 * All tests use normalized [0,1] image coordinates with a known image resolution so the
 * pixel distance is predictable and the expected pixelsPerMeter can be computed by hand.
 */
class CalibrationServiceComputeFromReferenceTest {

    // 1000 x 1000 px image makes the math trivial.
    private val imageW = 1000.0
    private val imageH = 1000.0

    @Test
    fun `horizontal line 100px over 1m reference returns 100 px per meter`() {
        // Points span 0.1 of normalized width = 100 px in a 1000-wide image
        val start = NormalizedPoint(0.0, 0.5)
        val end = NormalizedPoint(0.1, 0.5)
        val cal = CalibrationService.computeFromReference(start, end, imageW, imageH, knownLengthMeters = 1.0)
        assertNotNull(cal)
        assertEquals(CalibrationMethod.MANUAL_REFERENCE, cal.method)
        assertApprox(100.0, cal.pixelsPerMeter, tolerance = 0.001)
        assertEquals(100, cal.confidencePercent)
    }

    @Test
    fun `horizontal line 500px over 2m reference returns 250 px per meter`() {
        val start = NormalizedPoint(0.0, 0.0)
        val end = NormalizedPoint(0.5, 0.0)
        val cal = CalibrationService.computeFromReference(start, end, imageW, imageH, knownLengthMeters = 2.0)
        assertNotNull(cal)
        assertApprox(250.0, cal.pixelsPerMeter, tolerance = 0.001)
        assertEquals(100, cal.confidencePercent)
    }

    @Test
    fun `diagonal line with known length returns correct pixelsPerMeter`() {
        // 3-4-5 right triangle: dx=300, dy=400 → distance=500 px over 2.5 m → 200 px/m
        val start = NormalizedPoint(0.0, 0.0)
        val end = NormalizedPoint(0.3, 0.4)
        val cal = CalibrationService.computeFromReference(start, end, imageW, imageH, knownLengthMeters = 2.5)
        assertNotNull(cal)
        assertApprox(200.0, cal.pixelsPerMeter, tolerance = 0.01)
    }

    @Test
    fun `zero-length line returns null`() {
        val point = NormalizedPoint(0.5, 0.5)
        val cal = CalibrationService.computeFromReference(point, point, imageW, imageH, knownLengthMeters = 1.0)
        assertNull(cal)
    }

    @Test
    fun `very short reference object in cm returns correct px per meter`() {
        // 50 px line representing a 0.10 m (10 cm) object → 500 px/m
        val start = NormalizedPoint(0.0, 0.0)
        val end = NormalizedPoint(0.05, 0.0) // 50 px in 1000-wide image
        val cal = CalibrationService.computeFromReference(start, end, imageW, imageH, knownLengthMeters = 0.10)
        assertNotNull(cal)
        assertApprox(500.0, cal.pixelsPerMeter, tolerance = 0.01)
    }
}

/**
 * Unit tests for [CalibrationService.computeFromDepthFrame].
 */
class CalibrationServiceDepthFrameTest {

    @Test
    fun `returns ARCORE_DEPTH calibration from valid depth frame`() {
        val width = 10
        val height = 10
        val depthMm = FloatArray(width * height) { 2000f } // 2 m depth everywhere
        val confidence = FloatArray(width * height) { 255f } // max confidence

        val frame = DepthFrame(depthMm, confidence, width, height)
        val tap = NormalizedPoint(0.5, 0.5)

        val cal = CalibrationService.computeFromDepthFrame(frame, tap, imageWidthPx = 1000.0)
        assertNotNull(cal)
        assertEquals(CalibrationMethod.ARCORE_DEPTH, cal.method)
        // At 2m depth, imageWidth=1000 → pixelsPerMeter = 1000/2 = 500
        assertApprox(500.0, cal.pixelsPerMeter, tolerance = 0.01)
        assertEquals(100, cal.confidencePercent)
    }

    @Test
    fun `zero depth at tap point returns null`() {
        val width = 4
        val height = 4
        val depthMm = FloatArray(width * height) { 0f }
        val confidence = FloatArray(width * height) { 200f }
        val frame = DepthFrame(depthMm, confidence, width, height)
        val cal = CalibrationService.computeFromDepthFrame(frame, NormalizedPoint(0.5, 0.5), 1000.0)
        assertNull(cal)
    }

    @Test
    fun `zero confidence at tap point returns null`() {
        val width = 4
        val height = 4
        val depthMm = FloatArray(width * height) { 1500f }
        val confidence = FloatArray(width * height) { 0f }
        val frame = DepthFrame(depthMm, confidence, width, height)
        val cal = CalibrationService.computeFromDepthFrame(frame, NormalizedPoint(0.5, 0.5), 1000.0)
        assertNull(cal)
    }

    @Test
    fun `confidence is scaled correctly from ARCore 0-255 range`() {
        val width = 4
        val height = 4
        val depthMm = FloatArray(width * height) { 1000f }
        val confidence = FloatArray(width * height) { 127f } // ~50%
        val frame = DepthFrame(depthMm, confidence, width, height)
        val cal = CalibrationService.computeFromDepthFrame(frame, NormalizedPoint(0.5, 0.5), 1000.0)
        assertNotNull(cal)
        // 127/255 * 100 ≈ 49
        assertEquals(49, cal.confidencePercent)
    }
}

/**
 * Unit tests for [CalibrationService.computeFromMLDepth].
 */
class CalibrationServiceMLDepthTest {

    @Test
    fun `returns MONOCULAR_ML calibration with confidence 15`() {
        val w = 10
        val h = 10
        val depthMap = FloatArray(w * h) { 3.0f } // 3 m everywhere (relative scale)
        val tap = NormalizedPoint(0.5, 0.5)

        val cal = CalibrationService.computeFromMLDepth(depthMap, tap, w.toDouble(), h.toDouble())
        assertNotNull(cal)
        assertEquals(CalibrationMethod.MONOCULAR_ML, cal.method)
        assertEquals(15, cal.confidencePercent)
        // pixelsPerMeter = imageWidth / depthM = 10 / 3
        assertApprox(10.0 / 3.0, cal.pixelsPerMeter, tolerance = 0.001)
    }

    @Test
    fun `zero depth returns null`() {
        val w = 4
        val h = 4
        val depthMap = FloatArray(w * h) { 0f }
        val cal = CalibrationService.computeFromMLDepth(depthMap, NormalizedPoint(0.5, 0.5), w.toDouble(), h.toDouble())
        assertNull(cal)
    }

    @Test
    fun `tap at corners samples correct index`() {
        val w = 4
        val h = 4
        val depthMap = FloatArray(w * h) { idx ->
            if (idx == 0) 4.0f else 1.0f // only top-left is 4m
        }
        // Top-left tap should sample idx=0 → depth=4m
        val cal = CalibrationService.computeFromMLDepth(depthMap, NormalizedPoint(0.0, 0.0), w.toDouble(), h.toDouble())
        assertNotNull(cal)
        assertApprox(1.0, cal.pixelsPerMeter, tolerance = 0.001) // 4/4 = 1
    }
}

// ── Test helper ───────────────────────────────────────────────────────────────

private fun assertApprox(expected: Double, actual: Double, tolerance: Double) {
    val diff = abs(expected - actual)
    if (diff > tolerance) {
        throw AssertionError("Expected $expected ±$tolerance but was $actual (diff=$diff)")
    }
}
