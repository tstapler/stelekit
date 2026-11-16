// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.NormalizedPoint
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.tan
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task 3.1a / I-E3-11: the golden-scene generator must reproduce the published uncorrected error of the
 * measurement-accuracy epic (annotation-lens-distortion design.md: +0.05 cm at k = -0.01, +0.10 at -0.02,
 * about -0.14 at the frame edge) before any solver is graded against it.
 */
class GoldenSceneBaselineTest {
    // design.md publishes 18.05 / 18.10 / 17.86 to the nearest 0.01 cm or looser; the simulation itself gives
    // 18.060 / 18.121 / 17.876, so the plan's 0.01 cm tolerance cannot hold against the rounded text.
    private val PUBLISHED_ROUNDING_CM = 0.025
    private val w = 4000.0
    private val h = 3000.0

    private fun uncorrectedKnifeCm(kForward: Double, objectCx: Double, objectCy: Double): Double {
        val ruler = GoldenSceneGenerator.frontoParallelSegment(kForward, 2000.0, 2700.0, 0.0, 0.30)
        val knife = GoldenSceneGenerator.frontoParallelSegment(kForward, objectCx, objectCy, 0.0, 0.18)
        val cal = assertNotNull(CalibrationService.computeFromReference(ruler.first, ruler.second, w, h, 0.30))
        return LensMeasurement.distanceMeters(knife.first, knife.second, cal, w, h)!! * 100.0
    }

    @Test
    fun baseline_should_ReproducePublishedUncorrectedError_When_KMinus001AtCentre() {
        val cm = uncorrectedKnifeCm(-0.01, 2000.0, 1500.0)
        assertEquals(18.060138, cm, 1e-5) // LensDistortionSimulationTest scene, recomputed independently
        assertEquals(18.05, cm, PUBLISHED_ROUNDING_CM)
    }

    @Test
    fun baseline_should_ReproducePublishedUncorrectedError_When_KMinus002AtCentre() {
        val cm = uncorrectedKnifeCm(-0.02, 2000.0, 1500.0)
        assertEquals(18.120751, cm, 1e-5)
        assertEquals(18.10, cm, PUBLISHED_ROUNDING_CM)
    }

    @Test
    fun baseline_should_ReproducePublishedUncorrectedError_When_ObjectAtFrameEdge() {
        val cm = uncorrectedKnifeCm(-0.01, 3400.0, 800.0)
        assertEquals(17.876054, cm, 1e-5)
        assertEquals(17.86, cm, PUBLISHED_ROUNDING_CM)
    }

    @Test
    fun baseline_should_BeExact_When_NoDistortionAndNoTilt() {
        assertEquals(18.0, uncorrectedKnifeCm(0.0, 3400.0, 800.0), 1e-6)
    }

    @Test
    fun tiltedScene_should_HaveScaleGradientOfTanTauOverF_When_NoNoiseAndNoDistortion() {
        val tilt = 5.0
        val scene = assertNotNull(
            GoldenSceneGenerator.random(SceneFamily.IN_MODEL, Random(1), nTrain = 6, tiltDeg = tilt, tapNoisePx = 0.0, azimuthDeg = 0.0),
        )
        // Metres per pixel follows w0 / (1 + g . p)^a with a between 1 (across the gradient) and 2 (along it) and
        // |g| = tan(tau) / f_px per native pixel, so the fitted log-slope lies between |g| and 2|g|.
        val f = scene.focalPx
        val gExpected = tan(tilt * kotlin.math.PI / 180.0) / f
        val samples = (scene.training + scene.heldOut).map { s ->
            val lenPx = pxLength(s, scene)
            Triple(s.trueMeters / lenPx, (s.p1.x + s.p2.x) / 2 * scene.widthPx - scene.widthPx / 2, (s.p1.y + s.p2.y) / 2 * scene.heightPx - scene.heightPx / 2)
        }
        // Fit log(mpp) = a + gx*x + gy*y by least squares over x and y separately (small-g linearisation).
        val gx = slope(samples.map { it.second }, samples.map { kotlin.math.ln(it.first) })
        val gy = slope(samples.map { it.third }, samples.map { kotlin.math.ln(it.first) })
        val magnitude = hypot(gx, gy)
        assertTrue(magnitude in 0.75 * gExpected..2.3 * gExpected, "gradient $magnitude vs expected $gExpected")
    }

    @Test
    fun generator_should_BeDeterministicForASeed() {
        val a = GoldenSceneGenerator.random(SceneFamily.IN_MODEL, Random(42), 6, 4.0, 0.5)
        val b = GoldenSceneGenerator.random(SceneFamily.IN_MODEL, Random(42), 6, 4.0, 0.5)
        assertEquals(a, b)
    }

    @Test
    fun noiseFreeFrontoParallelScene_should_RecoverTrueLengthsFromAnySingleSegmentScale() {
        val scene = assertNotNull(GoldenSceneGenerator.random(SceneFamily.IN_MODEL, Random(3), 6, 0.0, 0.0))
        val ref = scene.training.first()
        val mpp = ref.trueMeters / pxLength(ref, scene)
        val pred = pxLength(scene.heldOut, scene) * mpp
        assertEquals(scene.heldOut.trueMeters, pred, scene.heldOut.trueMeters * 1e-9)
    }

    @Test
    fun everyFamily_should_ProduceInFrameSegmentsSpreadOverTheFrame() {
        for (family in SceneFamily.entries) {
            repeat(20) { i ->
                val scene = assertNotNull(GoldenSceneGenerator.random(family, Random(100 + i), 6, 4.0, 0.5), "$family")
                assertEquals(6, scene.training.size, "$family")
                val all = scene.training.flatMap { listOf(it.p1, it.p2) } + listOf(scene.heldOut.p1, scene.heldOut.p2)
                assertTrue(all.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 }, "$family in frame")
                assertTrue(midpointSpreadFraction(scene) >= 0.4, "$family spread ${midpointSpreadFraction(scene)}")
            }
        }
    }

    @Test
    fun rotatedFamily_should_SwapFrameDimensions() {
        val scene = assertNotNull(GoldenSceneGenerator.random(SceneFamily.ROTATED_90, Random(5), 6, 3.0, 0.5))
        assertEquals(3000.0, scene.widthPx)
        assertEquals(4000.0, scene.heightPx)
    }

    @Test
    fun twoPlaneFamily_should_MixScalesAcrossSegments() {
        val scene = assertNotNull(GoldenSceneGenerator.random(SceneFamily.TWO_PLANES, Random(9), 6, 0.0, 0.0))
        val mpps = (scene.training + scene.heldOut).map { it.trueMeters / pxLength(it, scene) }
        assertTrue(mpps.max() / mpps.min() > 1.05, "scales ${mpps}")
    }

    @Test
    fun heightsFamily_should_ChangeScaleWithoutChangingTrueLength() {
        val raised = assertNotNull(GoldenSceneGenerator.random(SceneFamily.HEIGHTS_ABOVE_PLANE, Random(11), 6, 0.0, 0.0))
        val mpps = (raised.training + raised.heldOut).map { it.trueMeters / pxLength(it, raised) }
        assertTrue(mpps.max() / mpps.min() > 1.005, "scales ${mpps}")
    }

    @Test
    fun atanOfScaleGradientTimesFocal_should_ReturnTheTiltAngle() {
        val g = tan(8.0 * kotlin.math.PI / 180.0) / 3100.0
        assertEquals(8.0, atan(g * 3100.0) * 180.0 / kotlin.math.PI, 1e-9)
    }

    private fun pxLength(s: GoldenSegment, scene: GoldenScene): Double =
        hypot((s.p2.x - s.p1.x) * scene.widthPx, (s.p2.y - s.p1.y) * scene.heightPx)

    private fun midpointSpreadFraction(scene: GoldenScene): Double {
        val mids = (scene.training).map { NormalizedPoint((it.p1.x + it.p2.x) / 2, (it.p1.y + it.p2.y) / 2) }
        val diag = hypot(scene.widthPx, scene.heightPx)
        var best = 0.0
        for (i in mids.indices) for (j in i + 1 until mids.size) {
            best = maxOf(best, hypot((mids[i].x - mids[j].x) * scene.widthPx, (mids[i].y - mids[j].y) * scene.heightPx) / diag)
        }
        return best
    }

    private fun slope(x: List<Double>, y: List<Double>): Double {
        val mx = x.average()
        val my = y.average()
        return x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / x.sumOf { (it - mx) * (it - mx) }
    }
}
