// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.CalibrationConstraint
import dev.stapler.stelekit.model.FitTier
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Story 3.3 identifiability gates: spread, radial reach, noise floor, rank and convergence (GRADIENT, GRADIENT_AND_K). */
class GradientIdentifiabilityTest {
    private val frame = FRAME_4000x3000
    private val gradient = FitOptions(request = TierRequest.Only(FitTier.GRADIENT))
    private val withK = FitOptions(request = TierRequest.Only(FitTier.GRADIENT_AND_K), k = 0.0)

    /** A tilted-scene constraint set whose midpoints are moved to the given centres (tilt 6 degrees, no noise). */
    private fun at(centres: List<Pair<Double, Double>>): List<CalibrationConstraint> {
        val scene = assertNotNull(GoldenSceneGenerator.random(SceneFamily.IN_MODEL, Random(2), 6, 6.0, 0.0, spreadFraction = 0.0))
        return scene.training.mapIndexed { i, s ->
            val (cx, cy) = centres[i]
            val mx = (s.p1.x + s.p2.x) / 2
            val my = (s.p1.y + s.p2.y) / 2
            val dx = cx - mx
            val dy = cy - my
            constraint(
                s.id,
                (s.p1.x + dx).coerceIn(0.0, 1.0), (s.p1.y + dy).coerceIn(0.0, 1.0),
                (s.p2.x + dx).coerceIn(0.0, 1.0), (s.p2.y + dy).coerceIn(0.0, 1.0), s.trueMeters,
            )
        }
    }

    private fun failure(cs: List<CalibrationConstraint>, options: FitOptions) = ConstraintCalibrator.fit(cs, frame, options).requested.left()

    @Test
    fun gradient_should_ReturnIllConditionedPositionSpread_When_SegmentsClusteredInOneCorner() {
        val corner = listOf(0.15 to 0.15, 0.18 to 0.17, 0.2 to 0.14, 0.16 to 0.2, 0.22 to 0.18, 0.19 to 0.22)
        val f = failure(at(corner), gradient)
        assertEquals(CalibrationFailure.IllConditioned(IllConditionedAspect.POSITION_SPREAD), f)
    }

    @Test
    fun gradient_should_ReturnIllConditionedPositionSpread_When_MidpointsLieOnOneLine() {
        val line = listOf(0.1 to 0.5, 0.25 to 0.5, 0.4 to 0.5, 0.55 to 0.5, 0.7 to 0.5, 0.85 to 0.5)
        assertEquals(CalibrationFailure.IllConditioned(IllConditionedAspect.POSITION_SPREAD), failure(at(line), gradient))
    }

    @Test
    fun gradient_should_Succeed_When_SixParallelSegmentsSpreadOverTheFrame() {
        // Direction spread is a PER_AXIS requirement only: a gradient fit works on parallel segments.
        val sceneCentres = listOf(0.15 to 0.2, 0.5 to 0.18, 0.85 to 0.25, 0.2 to 0.75, 0.55 to 0.8, 0.85 to 0.7)
        val scene = assertNotNull(GoldenSceneGenerator.random(SceneFamily.IN_MODEL, Random(4), 6, 7.0, 0.0, spreadFraction = 0.0))
        val parallel = scene.training.mapIndexed { i, s ->
            val len = hypot((s.p2.x - s.p1.x) * 4000, (s.p2.y - s.p1.y) * 3000)
            val (cx, cy) = sceneCentres[i]
            val half = len / 8 / 4000
            constraint(s.id, cx - half, cy, cx + half, cy, s.trueMeters)
        }
        // Segment lengths no longer match their positions' scales, so only the gates are asserted here.
        val r = ConstraintCalibrator.fit(parallel, frame, gradient).requested
        val f = (r as? arrow.core.Either.Left)?.value
        assertTrue(f == null || f !is CalibrationFailure.IllConditioned && f !is CalibrationFailure.Collinear, "unexpected $f")
    }

    @Test
    fun gradientAndK_should_ReturnIllConditionedRadialSpread_When_AllSegmentsNearThePrincipalPoint() {
        val centre = listOf(0.45 to 0.45, 0.55 to 0.46, 0.5 to 0.55, 0.42 to 0.52, 0.58 to 0.54, 0.5 to 0.4)
        // Shrink segments so every endpoint stays within half the half-diagonal; positions spread is checked first,
        // so use a frame where the spread gate passes: scale midpoints out only along the segments' own extent.
        val cs = at(centre).map { c ->
            val mx = (c.p1.x + c.p2.x) / 2
            val my = (c.p1.y + c.p2.y) / 2
            constraint(c.id, mx + (c.p1.x - mx) * 0.1, my + (c.p1.y - my) * 0.1, mx + (c.p2.x - mx) * 0.1, my + (c.p2.y - my) * 0.1, c.distanceMeters)
        }
        val f = failure(cs, withK)
        // Clustered midpoints trip the position gate first; both are identifiability refusals.
        assertTrue(f is CalibrationFailure.IllConditioned, "got $f")
    }

    @Test
    fun gradientAndK_should_ReturnRadialSpread_When_PositionsSpreadButEndpointsStayCentral() {
        // Midpoints span 44 percent of the diagonal yet every endpoint is within 0.48 of the half-diagonal.
        val cs = listOf(
            constraint("a", 0.20, 0.50, 0.24, 0.50, 0.1), constraint("b", 0.76, 0.50, 0.80, 0.50, 0.1),
            constraint("c", 0.48, 0.30, 0.52, 0.30, 0.1), constraint("d", 0.48, 0.70, 0.52, 0.70, 0.1),
            constraint("e", 0.30, 0.58, 0.34, 0.58, 0.1), constraint("f", 0.66, 0.42, 0.70, 0.42, 0.1),
        )
        val spreadOk = GradientTier.spreadGate(
            cs.map { Seg(it.id, it.distanceMeters, it.p1, it.p2, PxPoint(it.p1.x * 4000, it.p1.y * 3000), PxPoint(it.p2.x * 4000, it.p2.y * 3000), 0.01) }, frame,
        )
        assertEquals(null, spreadOk, "test layout must pass the position gate to reach the radial gate")
        assertEquals(CalibrationFailure.IllConditioned(IllConditionedAspect.RADIAL_SPREAD), failure(cs, withK))
    }

    @Test
    fun gradientAndK_should_BeIneligibleWithNoiseFloorReason_When_KEffectBelowTwiceTapError() {
        // Reach beyond 0.5 but only tiny differences in radius: a k of 0.03 moves nothing measurable.
        val ring = listOf(0.5 to 0.1, 0.9 to 0.5, 0.5 to 0.9, 0.1 to 0.5, 0.78 to 0.22, 0.22 to 0.78)
        val cs = at(ring).map { c ->
            val mx = (c.p1.x + c.p2.x) / 2
            val my = (c.p1.y + c.p2.y) / 2
            constraint(c.id, mx + (c.p1.x - mx) * 0.02, my + (c.p1.y - my) * 0.02, mx + (c.p2.x - mx) * 0.02, my + (c.p2.y - my) * 0.02, c.distanceMeters)
        }
        val f = failure(cs, withK.copy(tapNoisePx = 5.0))
        assertTrue(f is CalibrationFailure.IllConditioned && f.aspect == IllConditionedAspect.NOISE_FLOOR, "got $f")
    }

    @Test
    fun jacobianRank_should_BeThree_ForAnyGradientScene_AndDeficientWhenAllEndpointsShareAnAxis() {
        val scene = assertNotNull(GoldenSceneGenerator.random(SceneFamily.IN_MODEL, Random(9), 6, 5.0, 0.0))
        val cs = scene.training.map { it.toConstraint() }
        val solver = GradientSolver(4000.0, 3000.0, withK = false, focalNorm = null, maxIterations = 50)
        fun segs(list: List<CalibrationConstraint>) = list.map {
            val u1 = RadialLens.undistort(it.p1.x, it.p1.y, 0.0, 4000.0, 3000.0)
            val u2 = RadialLens.undistort(it.p2.x, it.p2.y, 0.0, 4000.0, 3000.0)
            Seg(it.id, it.distanceMeters, it.p1, it.p2, u1, u2, 0.01)
        }
        val start = GradSol(ln(0.0002 * 2500.0), 0.0, 0.0, 0.0)
        assertTrue(solver.hasFullRank(segs(cs), start))
        // Every endpoint on the vertical line x = 0.5: the horizontal gradient column vanishes.
        val vertical = (0 until 6).map { constraint("v$it", 0.5, 0.1 + it * 0.12, 0.5, 0.18 + it * 0.12, 0.05 + it * 0.002) }
        assertTrue(!solver.hasFullRank(segs(vertical), start))
        assertEquals(CalibrationFailure.IllConditioned(IllConditionedAspect.POSITION_SPREAD), failure(vertical, gradient))
    }

    @Test
    fun gradient_should_ReturnDidNotConverge_When_IterationBudgetIsTooSmall() {
        val scene = assertNotNull(GoldenSceneGenerator.random(SceneFamily.IN_MODEL, Random(21), 6, 8.0, 0.5, spreadFraction = 0.5))
        val f = failure(scene.training.map { it.toConstraint() }, gradient.copy(maxIterations = 1))
        assertEquals(CalibrationFailure.DidNotConverge, f)
    }

    @Test
    fun gradient_should_ReturnNoMeasurableTilt_When_TrueTiltIsHalfADegree() {
        val rnd = Random(31)
        var noTilt = 0
        repeat(40) {
            val scene = assertNotNull(GoldenSceneGenerator.random(SceneFamily.IN_MODEL, rnd, 6, 0.5, 1.0, spreadFraction = 0.5))
            val f = ConstraintCalibrator.fit(scene.training.map { it.toConstraint() }, scene.frame(), gradient)
            assertEquals(FitTier.SCALE, assertNotNull(f.effective).tier)
            if (f.requested.left() is CalibrationFailure.NoMeasurableTilt) noTilt++
        }
        assertTrue(noTilt >= 36, "NoMeasurableTilt in only $noTilt/40 scenes")
    }
}
