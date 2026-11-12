// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomographyTest {
    private val square = listOf(PxPoint(0.0, 0.0), PxPoint(1.0, 0.0), PxPoint(1.0, 1.0), PxPoint(0.0, 1.0))

    private fun truth(a: Double, b: Double, c: Double, d: Double) =
        Homography(listOf(1.0 + a, b, 100.0 * c, d, 1.0 - a, 50.0 * c, 1e-4 * a, 1e-4 * b, 1.0))

    private fun success(src: List<PxPoint>, dst: List<PxPoint>) = assertIs<HomographyFit.Success>(HomographyFitter.fit(src, dst))

    @Test
    fun fourPointsRecoverTheMapExactly() {
        val h = truth(0.1, 0.05, 1.0, 0.02)
        val src = listOf(PxPoint(100.0, 80.0), PxPoint(900.0, 120.0), PxPoint(950.0, 700.0), PxPoint(60.0, 650.0))
        val dst = src.map { h.apply(it)!! }
        val fit = success(src, dst)
        assertEquals(0.0, fit.rmsError, 1e-6)
        val probe = PxPoint(500.0, 400.0)
        val want = h.apply(probe)!!
        val got = fit.homography.apply(probe)!!
        assertEquals(want.x, got.x, 1e-6)
        assertEquals(want.y, got.y, 1e-6)
    }

    @Test
    fun identityComesBackAsIdentity() {
        val fit = success(square, square)
        assertEquals(listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0).map { it }, fit.homography.values.map { if (kotlin.math.abs(it) < 1e-9) 0.0 else it })
    }

    @Test
    fun moreThanFourNoisyPointsAverageTheNoiseOut() {
        val rnd = Random(7)
        val h = truth(0.05, 0.02, 0.5, 0.01)
        val src = (0 until 40).map { PxPoint(rnd.nextDouble(0.0, 1000.0), rnd.nextDouble(0.0, 800.0)) }
        val dst = src.map { h.apply(it)!!.let { p -> PxPoint(p.x + rnd.nextDouble(-0.5, 0.5), p.y + rnd.nextDouble(-0.5, 0.5)) } }
        val fit = success(src, dst)
        assertTrue(fit.rmsError < 0.5, "rms ${fit.rmsError}")
        val probe = PxPoint(500.0, 400.0)
        val want = h.apply(probe)!!
        val got = fit.homography.apply(probe)!!
        assertEquals(want.x, got.x, 0.5)
        assertEquals(want.y, got.y, 0.5)
    }

    @Test
    fun badInputIsATypedFailureNotAnException() {
        fun reason(s: List<PxPoint>, d: List<PxPoint>) = assertIs<HomographyFit.Failure>(HomographyFitter.fit(s, d)).reason
        assertEquals(HomographyFailure.TOO_FEW_POINTS, reason(square.take(3), square.take(3)))
        assertEquals(HomographyFailure.MISMATCHED_POINTS, reason(square, square.take(3)))
        val line = (0 until 4).map { PxPoint(it * 10.0, it * 5.0) }
        assertEquals(HomographyFailure.DEGENERATE_POINTS, reason(line, square))
        assertEquals(HomographyFailure.DEGENERATE_POINTS, reason(square, line))
        val threeOnALine = listOf(PxPoint(0.0, 0.0), PxPoint(1.0, 0.0), PxPoint(2.0, 0.0), PxPoint(1.0, 1.0))
        assertEquals(HomographyFailure.DEGENERATE_POINTS, reason(threeOnALine, square))
    }

    @Test
    fun pointsOnTheLineAtInfinityMapToNull() {
        val h = Homography(listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0.0))
        assertNull(h.apply(PxPoint(0.0, 5.0)))
        assertNotNull(h.apply(PxPoint(2.0, 5.0)))
    }

    @Test
    fun projectThenRecoverForRandomPlanesWithinOneMicrometre() = runTest {
        checkAll(Arb.int(-100, 100), Arb.int(-100, 100), Arb.int(-100, 100), Arb.int(-100, 100), Arb.int(0, 100000)) { a, b, c, d, seed ->
            val h = truth(a / 1000.0, b / 1000.0, c / 100.0, d / 1000.0)
            val rnd = Random(seed)
            // Corners of a quadrilateral, jittered but kept well away from degenerate.
            val src = listOf(
                PxPoint(200 + rnd.nextDouble(50.0), 200 + rnd.nextDouble(50.0)),
                PxPoint(1800 - rnd.nextDouble(50.0), 200 + rnd.nextDouble(50.0)),
                PxPoint(1800 - rnd.nextDouble(50.0), 1300 - rnd.nextDouble(50.0)),
                PxPoint(200 + rnd.nextDouble(50.0), 1300 - rnd.nextDouble(50.0)),
            )
            val dst = src.map { h.apply(it)!! }
            val fit = success(src, dst).homography
            val probe = PxPoint(900.0, 700.0)
            val want = h.apply(probe)!!
            val got = fit.apply(probe)!!
            assertEquals(want.x, got.x, 1e-6 * (1 + kotlin.math.abs(want.x)))
            assertEquals(want.y, got.y, 1e-6 * (1 + kotlin.math.abs(want.y)))
        }
    }
}
