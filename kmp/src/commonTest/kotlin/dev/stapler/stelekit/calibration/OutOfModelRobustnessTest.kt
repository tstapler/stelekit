// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.FitTier
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * U-E3-02 (anti-circularity): scenes the solver does NOT model. Pass per family: MAE(default) <= MAE(single
 * scale) * 1.02 with a 95 percent CI. The same table is printed for the GRADIENT-enabled selection so a tier
 * that only wins in-model is visible; a failing family would block the tier from shipping as default.
 */
class OutOfModelRobustnessTest {
    private val families = SceneFamily.entries.filter { it != SceneFamily.IN_MODEL }
    private val scenes = 300
    private val n = 6
    private val tap = 1.0

    private class Row(val family: SceneFamily, val single: List<Double>, val selected: List<Double>, val floorOk: Int, val gradientChosen: Int)

    private fun run(family: SceneFamily, options: FitOptions, seed: Int): Row {
        val rnd = Random(seed)
        val single = mutableListOf<Double>()
        val selected = mutableListOf<Double>()
        var floorOk = 0
        var gradient = 0
        while (single.size < scenes) {
            val scene = GoldenSceneGenerator.random(family, rnd, n, rnd.nextDouble() * 8.0, tap) ?: continue
            val fit = ConstraintCalibrator.fit(scene.training.map { it.toConstraint() }, scene.frame(), options.copy(k = scene.solverK)).requested.right()
            val e = heldOutErrorPct(fit, scene)
            val s = singleScaleErrorPct(scene)
            single += s
            selected += e
            if (e <= s + noiseFloorPct(tap, scene.heldOut.lengthPx(scene))) floorOk++
            if (fit.tier == FitTier.GRADIENT) gradient++
        }
        return Row(family, single, selected, floorOk, gradient)
    }

    private fun print(title: String, rows: List<Row>) {
        println(title)
        for (r in rows) {
            println(
                "  ${r.family.name.padEnd(22)} single=${r.single.average().round3()}% selected=${r.selected.average().round3()}% " +
                    "within-floor=${r.floorOk}/${scenes} gradient=${r.gradientChosen}/$scenes " +
                    "ciUpper=${ConstraintAccuracyHarness.pairedUpperBound(r.selected, r.single, 1.02).round3()}",
            )
        }
    }

    private fun Double.round3() = kotlin.math.round(this * 1000.0) / 1000.0

    @Test
    fun defaultTierSelection_should_BeNoWorseThanSingleScaleHeldOut_When_SceneOutsideSolverModelClass() {
        val rows = families.mapIndexed { i, f -> run(f, FitOptions(), 1000 + i) }
        print("out-of-model, shipped default (SCALE with LOO, GRADIENT off), N=$n, ${scenes} scenes, tilt 0-8 deg, 1 px taps", rows)
        assertEquals(7, rows.size)
        for (r in rows) {
            val upper = ConstraintAccuracyHarness.pairedUpperBound(r.selected, r.single, 1.02)
            assertTrue(upper <= 0.0, "${r.family}: MAE(default)=${r.selected.average()} vs single ${r.single.average()}, CI upper $upper")
            assertEquals(0, r.gradientChosen, "${r.family}: GRADIENT must never be chosen by default")
        }
    }

    @Test
    fun gradientEnabledSelection_should_BePrintedPerFamily_ForTheStudy() {
        val rows = families.mapIndexed { i, f -> run(f, FitOptions(gradientEnabled = true), 2000 + i) }
        print("out-of-model, GRADIENT enabled (study only)", rows)
        assertEquals(7, rows.size)
        // Informational: the shipped default is asserted above; this table is what Story 3.8 reads before flipping the default.
        assertTrue(rows.all { it.selected.isNotEmpty() })
    }
}
