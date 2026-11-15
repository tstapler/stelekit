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

/** U-E3-31 and U-E3-10: the leave-one-out comparison vetoes a gradient the t-test accepted. */
class GradientLooVetoTest {
    private val enabled = FitOptions(gradientEnabled = true)

    /** Finds scenes where the t-gate passes but GRADIENT does not beat SCALE's leave-one-out error by the margin. */
    private fun vetoedScenes(): List<GoldenScene> {
        val rnd = Random(77)
        val found = mutableListOf<GoldenScene>()
        var tries = 0
        while (found.size < 3 && tries < 4000) {
            tries++
            val scene = GoldenSceneGenerator.random(SceneFamily.IN_MODEL, rnd, 5, rnd.nextDouble() * 6.0, 2.0 + rnd.nextDouble() * 3.0, spreadFraction = 0.5) ?: continue
            val r = ConstraintCalibrator.fit(scene.training.map { it.toConstraint() }, scene.frame(), FitOptions(request = TierRequest.Only(FitTier.GRADIENT)))
            if (r.requested.fold({ it is CalibrationFailure.NotBetterThanSimpler }, { false })) found += scene
        }
        println("LOO veto scenes found: ${found.size} after $tries scenes")
        return found
    }

    @Test
    fun gradient_should_BeRejected_When_TTestPassesButLeaveOneOutDoesNotBeatScaleByMargin() {
        val scenes = vetoedScenes()
        assertTrue(scenes.isNotEmpty(), "no scene where the t-test passes and LOO vetoes was found")
        for (scene in scenes) {
            val fit = ConstraintCalibrator.fit(scene.training.map { it.toConstraint() }, scene.frame(), enabled).requested.right()
            assertEquals(FitTier.SCALE, fit.tier)
            val declined = fit.declined.first { it.tier == FitTier.GRADIENT }.failure
            assertTrue(declined is CalibrationFailure.NotBetterThanSimpler, "got $declined")
            val diag = fit.diagnostics.first { it.tier == FitTier.GRADIENT }
            assertTrue(diag.tStatistic!! > diag.tThreshold!!, "the t-test must have passed: ${diag.tStatistic} vs ${diag.tThreshold}")
        }
    }

    @Test
    fun selection_should_PreferSimplerTier_When_LeaveOneOutErrorRisesDespiteLowerTrainingResidual() {
        val scenes = vetoedScenes()
        assertTrue(scenes.isNotEmpty())
        val rises = scenes.count { scene ->
            val fit = ConstraintCalibrator.fit(scene.training.map { it.toConstraint() }, scene.frame(), enabled).requested.right()
            val g = fit.diagnostics.first { it.tier == FitTier.GRADIENT }
            g.residualMeanPct < fit.report.residualMeanPct && g.looPct!! > (1.0 - ConstraintTuning.LOO_MARGIN) * fit.report.looPct!!
        }
        assertTrue(rises > 0, "no vetoed scene had lower training residual with higher LOO")
    }

    @Test
    fun gradient_should_BeSelected_When_TiltIsLargeAndNoiseSmall() {
        val rnd = Random(5)
        val scene = assertNotNull(GoldenSceneGenerator.random(SceneFamily.IN_MODEL, rnd, 6, 9.0, 0.2, spreadFraction = 0.6))
        val fit = ConstraintCalibrator.fit(scene.training.map { it.toConstraint() }, scene.frame(), enabled).requested.right()
        assertEquals(FitTier.GRADIENT, fit.tier)
        assertNotNull(fit.homography)
    }
}
