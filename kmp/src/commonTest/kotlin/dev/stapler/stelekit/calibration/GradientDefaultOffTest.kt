// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.FitTier
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** U-E3-32: GRADIENT ships default-off; selection returns SCALE with leave-one-out unless the Settings key is on. */
class GradientDefaultOffTest {
    private val scene = assertNotNull(GoldenSceneGenerator.random(SceneFamily.IN_MODEL, Random(5), 6, 9.0, 0.2, spreadFraction = 0.6))
    private val cs = scene.training.map { it.toConstraint() }

    @Test
    fun freshInstall_should_HaveGradientDefaultOffWithTheDocumentedSettingsKey() {
        assertFalse(ConstraintTuning.GRADIENT_DEFAULT_ON)
        assertEquals("calibration_gradient_tier", ConstraintTuning.SETTINGS_KEY_GRADIENT_TIER)
        assertFalse(ConstraintTuning.gradientEnabled(null))
        assertTrue(ConstraintTuning.gradientEnabled(true))
        assertFalse(ConstraintTuning.gradientEnabled(false))
        assertFalse(FitOptions().gradientEnabled)
        assertFalse(FitOptions().kTierEnabled)
        assertFalse(FitOptions().perAxisEnabled)
    }

    @Test
    fun tierSelection_should_ReturnScaleWithLoo_When_GradientDefaultOffAndSettingOff() {
        val fit = ConstraintCalibrator.fit(cs, scene.frame()).requested.right()
        assertEquals(FitTier.SCALE, fit.tier)
        assertNotNull(fit.report.looPct)
        assertNull(fit.homography)
        assertTrue(fit.declined.any { it.tier == FitTier.GRADIENT && it.failure == CalibrationFailure.TierDisabled(FitTier.GRADIENT) })
    }

    @Test
    fun gradient_should_StillBeComputedAndLoggedForTheStudy_When_Off() {
        val fit = ConstraintCalibrator.fit(cs, scene.frame()).requested.right()
        val shadow = fit.diagnostics.firstOrNull { it.tier == FitTier.GRADIENT }
        assertNotNull(shadow, "the study needs the GRADIENT outcome even when it is not used")
        assertNotNull(shadow.looPct)
        assertTrue(fit.notes.none { it.contains("GRADIENT") }, "a disabled tier must not nag: ${fit.notes}")
    }

    @Test
    fun tierSelection_should_ReturnGradient_When_SettingOn() {
        val fit = ConstraintCalibrator.fit(cs, scene.frame(), FitOptions(gradientEnabled = ConstraintTuning.gradientEnabled(true))).requested.right()
        assertEquals(FitTier.GRADIENT, fit.tier)
        assertNotNull(fit.homography)
    }
}
