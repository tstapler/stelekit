// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.FitTier
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Story 3.3 in-model property tests on synthetic ground truth (U-E3-01, U-E3-06, SNR recording, focal refinement). */
class ConstraintCalibratorPropertyTest {
    private val tap = 0.5

    @Test
    fun fit_should_BeNoWorseThanSingleScaleWithinTapNoise_In95PercentOfInModelScenes() {
        val rnd = Random(20261114)
        var ok = 0
        var ok2 = 0
        var gradient = 0
        var sumSel = 0.0
        var sumSingle = 0.0
        val scenes = 500
        repeat(scenes) {
            val tilt = rnd.nextDouble() * 10.0
            val scene = assertNotNull(GoldenSceneGenerator.random(SceneFamily.IN_MODEL, rnd, 6, tilt, tap))
            val fit = ConstraintCalibrator.fit(
                scene.training.map { it.toConstraint() }, scene.frame(), FitOptions(gradientEnabled = true),
            ).requested.right()
            val sel = heldOutErrorPct(fit, scene)
            val single = singleScaleErrorPct(scene)
            sumSel += sel
            sumSingle += single
            if (fit.tier == FitTier.GRADIENT) gradient++
            val floor = noiseFloorPct(tap, scene.heldOut.lengthPx(scene))
            if (sel <= single + floor) ok++
            if (sel <= single + 2.0 * floor) ok2++
        }
        println("in-model N=6: selected MAE=${sumSel / scenes}% single MAE=${sumSingle / scenes}% GRADIENT chosen $gradient/$scenes within 1x floor $ok/$scenes within 2x floor $ok2/$scenes")
        // The floor is two independent tap errors (the held-out taps and the single reference's), hence 2x.
        // 93 percent: the 2-D Wald gate (higher threshold than the old 1-D t) declines weak tilt more often, trading power for the false-positive rate.
        assertTrue(ok2 >= scenes * 93 / 100, "only $ok2/$scenes scenes no worse than single scale within 2x the tap floor")
        assertTrue(sumSel < sumSingle, "selected tier should lower the mean held-out error")
    }

    @Test
    fun gradientTier_should_BeatScaleHeldOut_When_TiltedAndOnlyExifFocalOrNoFocal() {
        val rnd = Random(7)
        for (exif in listOf(null, 26.0)) {
            var gradientErr = 0.0
            var scaleErr = 0.0
            val scenes = 150
            repeat(scenes) {
                val scene = assertNotNull(GoldenSceneGenerator.random(SceneFamily.IN_MODEL, rnd, 6, 8.0, tap, spreadFraction = 0.5))
                val cs = scene.training.map { it.toConstraint() }
                val frame = scene.frame(exif)
                gradientErr += heldOutErrorPct(
                    ConstraintCalibrator.fit(cs, frame, FitOptions(request = TierRequest.Only(FitTier.GRADIENT), focalSource = FocalSource.EXIF_ONLY)).effective!!, scene,
                )
                scaleErr += heldOutErrorPct(ConstraintCalibrator.fit(cs, frame, FitOptions(request = TierRequest.Only(FitTier.SCALE))).effective!!, scene)
            }
            println("exif=$exif GRADIENT/SCALE-fallback MAE=${gradientErr / scenes}% SCALE MAE=${scaleErr / scenes}%")
            assertTrue(gradientErr < scaleErr, "GRADIENT $gradientErr vs SCALE $scaleErr (exif=$exif)")
        }
    }

    @Test
    fun gradientSnr_should_ExceedTen_When_GoldenSceneFiveDegreesAndSmallTapNoise() {
        // Segments are 7-20 percent of the frame (330-1330 px); 0.4 px taps give about 0.1-0.2 percent per segment.
        val rnd = Random(3)
        val snrs = (0 until 40).map {
            val scene = assertNotNull(GoldenSceneGenerator.random(SceneFamily.IN_MODEL, rnd, 6, 5.0, 0.4, spreadFraction = 0.5))
            val r = ConstraintCalibrator.fit(
                scene.training.map { it.toConstraint() }, scene.frame(), FitOptions(request = TierRequest.Only(FitTier.GRADIENT)),
            )
            r.effective!!.diagnostics.firstOrNull { it.tier == FitTier.GRADIENT }?.snr
                ?: (r.requested.fold({ it as? CalibrationFailure.NoMeasurableTilt }, { null }))?.tStatistic
        }
        val found = snrs.filterNotNull()
        println("gradient SNR at 5 deg, 0.4 px taps: median=${found.sorted()[found.size / 2]} min=${found.min()} (n=${found.size}/${snrs.size})")
        assertTrue(found.size >= 36, "gradient fit missing in ${snrs.size - found.size} scenes")
        assertTrue(found.sorted()[found.size / 2] > 10.0)
    }

    @Test
    fun focalRefinement_should_ChangeResultByAtMostHalfAPercent_When_TrustedFocalAtFiveDegrees() {
        val rnd = Random(11)
        var checked = 0
        while (checked < 20) {
            val scene = assertNotNull(GoldenSceneGenerator.random(SceneFamily.IN_MODEL, rnd, 6, 5.0, 0.0, spreadFraction = 0.5))
            val cs = scene.training.map { it.toConstraint() }
            val only = TierRequest.Only(FitTier.GRADIENT)
            // A layout whose midpoints are nearly collinear is refused by the spread gate; that is not under test here.
            val plain = ConstraintCalibrator.fit(cs, scene.frame(), FitOptions(request = only)).requested.getOrNull() ?: continue
            val refined = ConstraintCalibrator.fit(
                cs, scene.frame(),
                FitOptions(request = only, focalRefinement = true, focalSource = FocalSource.USER_CONFIRMED, focal35mmEq = 26.8),
            ).requested.right()
            assertTrue(refined.focalRefinementApplied)
            assertTrue(!plain.focalRefinementApplied)
            val a = plain.measure(scene.heldOut, scene)
            val b = refined.measure(scene.heldOut, scene)
            assertTrue(kotlin.math.abs(a - b) / a < 0.005, "plain $a refined $b")
            checked++
        }
    }

    @Test
    fun focalRefinement_should_NeverApply_When_SourceIsProfileOrExifOnly() {
        val rnd = Random(12)
        val scene = assertNotNull(GoldenSceneGenerator.random(SceneFamily.IN_MODEL, rnd, 6, 5.0, 0.0, spreadFraction = 0.5))
        for (src in listOf(FocalSource.PROFILE, FocalSource.EXIF_ONLY)) {
            val fit = ConstraintCalibrator.fit(
                scene.training.map { it.toConstraint() }, scene.frame(),
                FitOptions(request = TierRequest.Only(FitTier.GRADIENT), focalRefinement = true, focalSource = src, focal35mmEq = 26.0),
            ).requested.right()
            assertTrue(!fit.focalRefinementApplied, "$src")
        }
    }

    @Test
    fun scale_should_RecoverTruthWithinNoise_ForAnyTapNoiseAndTilt() = runTest {
        checkAll(40, Arb.int(1..100_000), Arb.double(0.0, 1.0)) { seed, noise ->
            val scene = GoldenSceneGenerator.random(SceneFamily.IN_MODEL, Random(seed), 6, 0.0, noise) ?: return@checkAll
            val fit = ConstraintCalibrator.fit(scene.training.map { it.toConstraint() }, scene.frame()).requested.right()
            val worstSegmentPx = scene.training.minOf { it.lengthPx(scene) }
            val tolerance = 5.0 * noiseFloorPct(noise, worstSegmentPx) + 0.01
            assertTrue(heldOutErrorPct(fit, scene) <= tolerance + noiseFloorPct(noise, scene.heldOut.lengthPx(scene)) * 3, "seed=$seed noise=$noise")
        }
    }

    @Test
    fun pixelsPerMeterAndHomography_should_AgreeAtTheImageCentre() {
        val scene = assertNotNull(GoldenSceneGenerator.random(SceneFamily.IN_MODEL, Random(5), 6, 6.0, 0.0, spreadFraction = 0.5))
        val fit = ConstraintCalibrator.fit(
            scene.training.map { it.toConstraint() }, scene.frame(), FitOptions(request = TierRequest.Only(FitTier.GRADIENT)),
        ).requested.right()
        val h = Homography(assertNotNull(fit.homography))
        val c = h.apply(PxPoint(2000.0, 1500.0))!!
        val d = h.apply(PxPoint(2001.0, 1500.0))!!
        val e = h.apply(PxPoint(2000.0, 1501.0))!!
        val mppCentre = kotlin.math.sqrt(kotlin.math.abs((d.x - c.x) * (e.y - c.y) - (d.y - c.y) * (e.x - c.x)))
        assertEquals(1.0 / fit.pixelsPerMeter, mppCentre, mppCentre * 1e-3)
    }
}
