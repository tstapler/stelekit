// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.CalibrationConstraint
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One mistyped known distance (typo, wrong unit) must be flagged, never silently averaged into the scale. */
class ConstraintOutlierPropertyTest {
    private val frame = FRAME_4000x3000
    private val mpp = 0.0005

    private fun scene(rnd: Random, n: Int, badIndex: Int?, factor: Double): List<CalibrationConstraint> = List(n) { i ->
        val x1 = 0.1 + rnd.nextDouble() * 0.3
        val y1 = 0.1 + rnd.nextDouble() * 0.8
        val x2 = x1 + 0.2 + rnd.nextDouble() * 0.3
        val y2 = (y1 + (rnd.nextDouble() - 0.5) * 0.2).coerceIn(0.05, 0.95)
        val px = hypot((x2 - x1) * frame.widthPx, (y2 - y1) * frame.heightPx)
        val noise = 1.0 + (rnd.nextDouble() - 0.5) * 0.04
        constraint("c$i", x1, y1, x2, y2, px * mpp * noise * if (i == badIndex) factor else 1.0)
    }

    @Test
    fun fit_should_FlagTheMistypedDistance_When_OneOfNIsOffByMoreThan3x() {
        val rnd = Random(679)
        repeat(300) {
            val n = 3 + rnd.nextInt(6)
            val bad = rnd.nextInt(n)
            val factor = (3.0 + rnd.nextDouble() * 12.0).let { if (rnd.nextBoolean()) it else 1.0 / it }
            val failure = ConstraintCalibrator.fit(scene(rnd, n, bad, factor), frame).requested.left()
            assertTrue(failure is CalibrationFailure.ConflictingConstraints, "n=$n factor=$factor got $failure")
            assertEquals("c$bad", failure.worstId)
        }
    }

    @Test
    fun fit_should_NotFlagAnything_When_AllDistancesAgreeWithinTwoPercent() {
        val rnd = Random(680)
        repeat(300) {
            val response = ConstraintCalibrator.fit(scene(rnd, 3 + rnd.nextInt(6), null, 1.0), frame)
            assertTrue(response.requested.isRight(), "clean scene was refused: ${response.requested}")
        }
    }

    /** 1.2x-2x typos are not refused, but they must not drag the scale off the clean majority nor go unflagged. */
    @Test
    fun fit_should_KeepScaleOfTheCleanMajorityAndFlagTheTypo_When_OneOfNIsOff1p2To2x() {
        val rnd = Random(681)
        repeat(300) {
            val n = 4 + rnd.nextInt(5)
            val bad = rnd.nextInt(n)
            val factor = (1.3 + rnd.nextDouble() * 0.6).let { if (rnd.nextBoolean()) it else 1.0 / it }
            val response = ConstraintCalibrator.fit(scene(rnd, n, bad, factor), frame)
            val fit = response.requested.right()
            val scaleErrorPct = kotlin.math.abs(1.0 / fit.pixelsPerMeter / mpp - 1.0) * 100.0
            assertTrue(scaleErrorPct < 4.0, "n=$n factor=$factor scale off by $scaleErrorPct%")
            val worst = fit.report.worst
            assertEquals("c$bad", worst?.id, "n=$n factor=$factor")
            val residual = kotlin.math.abs(fit.report.perConstraint.first { it.id == "c$bad" }.residualPct)
            assertTrue(residual > ConstraintTuningProbe.FLAG_MIN_PCT, "typo residual only $residual%")
        }
    }

    @Test
    fun fit_should_RefuseTwoDistancesThatDisagreeBy2xOrMore_AndAcceptCloseOnes() {
        val rnd = Random(682)
        repeat(100) {
            val apart = scene(rnd, 2, 1, 2.3 + rnd.nextDouble() * 4.0)
            assertTrue(ConstraintCalibrator.fit(apart, frame).requested.left() is CalibrationFailure.ConflictingConstraints)
            val close = scene(rnd, 2, 1, 1.0 + rnd.nextDouble() * 0.3)
            assertTrue(ConstraintCalibrator.fit(close, frame).requested.isRight())
        }
    }

    private object ConstraintTuningProbe {
        const val FLAG_MIN_PCT = 3.0
    }
}
