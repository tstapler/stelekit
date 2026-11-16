// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.FitTier
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * U-E3-07. A fixed 3 SE gate passes about 9.5 percent of pure-noise fits at 2 degrees of freedom; the Student-t
 * gate with the leave-one-out veto must accept at most 1 percent at N = 5 and N = 6.
 */
class GradientNoiseFalsePositiveRateTest {
    private val scenes = 500

    private fun run(n: Int, seed: Int): Pair<Int, Int> {
        val rnd = Random(seed)
        var accepted = 0
        var gatePassed = 0
        repeat(scenes) {
            val scene = assertNotNull(GoldenSceneGenerator.random(SceneFamily.IN_MODEL, rnd, n, 0.0, 1.0, spreadFraction = 0.5))
            val cs = scene.training.map { it.toConstraint() }
            val auto = ConstraintCalibrator.fit(cs, scene.frame(), FitOptions(gradientEnabled = true)).requested.right()
            if (auto.tier == FitTier.GRADIENT) accepted++
            val raw = ConstraintCalibrator.fit(cs, scene.frame(), FitOptions(request = TierRequest.Only(FitTier.GRADIENT))).requested
            val failure = raw.fold({ it }, { null })
            if (failure == null || (failure !is CalibrationFailure.NoMeasurableTilt && failure is CalibrationFailure.NotBetterThanSimpler)) gatePassed++
        }
        return accepted to gatePassed
    }

    @Test
    fun gradientGate_should_AcceptAtMost1Percent_On500PureNoiseScenes_At_N5() {
        val (accepted, raw) = run(5, 51)
        println("N=5 pure noise: accepted $accepted/$scenes, t-gate alone passed $raw/$scenes")
        assertTrue(accepted <= scenes / 100, "accepted $accepted of $scenes")
    }

    @Test
    fun gradientGate_should_AcceptAtMost1Percent_On500PureNoiseScenes_At_N6() {
        val (accepted, raw) = run(6, 61)
        println("N=6 pure noise: accepted $accepted/$scenes, t-gate alone passed $raw/$scenes")
        assertTrue(accepted <= scenes / 100, "accepted $accepted of $scenes")
    }

    @Test
    fun gradientGate_should_PassAtMost2Percent_OnPureNoise_WithoutTheLooVeto_At_N5_N6_N10_N20() {
        for ((n, seed) in listOf(5 to 5, 6 to 6, 10 to 10, 20 to 20)) {
            val (_, raw) = run(n, seed)
            println("N=$n pure noise: Wald gate alone passed $raw/$scenes")
            assertTrue(raw <= scenes * 2 / 100, "N=$n: Wald gate alone passed $raw of $scenes")
        }
    }

    @Test
    fun gradientGate_should_ExceedTheOneDimensionalThreshold_AndMatchTheChiSquareLimit() {
        for (nu in listOf(2, 3, 5, 10, 30)) assertTrue(FitStatistics.gradientGate(nu) > FitStatistics.studentT995(nu), "nu=$nu")
        assertEquals(3.035, FitStatistics.gradientGate(100_000), 0.01)
        assertTrue(FitStatistics.gradientGate(0).isInfinite())
    }

    @Test
    fun studentT995_should_MatchTheStandardTable() {
        assertEquals(9.925, FitStatistics.studentT995(2), 1e-9)
        assertEquals(5.841, FitStatistics.studentT995(3), 1e-9)
        assertEquals(4.604, FitStatistics.studentT995(4), 1e-9)
        assertTrue(FitStatistics.studentT995(200) in 2.576..2.65)
        assertTrue(FitStatistics.studentT995(0).isInfinite())
    }
}
