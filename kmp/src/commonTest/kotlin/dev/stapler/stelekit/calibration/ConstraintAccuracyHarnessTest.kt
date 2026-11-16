// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task 3.1b: the held-out harness must discriminate (averaging beats one reference under noise), cover all
 * eight families (in-model plus the seven out-of-model ones) and print the table Story 3.8 reads.
 */
class ConstraintAccuracyHarnessTest {
    private val solvers = mapOf("single" to ConstraintAccuracyHarness.SINGLE_SCALE, "mean" to ConstraintAccuracyHarness.MEAN_SCALE)

    @Test
    fun evaluate_should_ReturnZero_When_NoiseFreeFrontoParallelScene() {
        val scene = assertNotNull(GoldenSceneGenerator.random(SceneFamily.IN_MODEL, Random(1), 6, 0.0, 0.0))
        assertEquals(0.0, ConstraintAccuracyHarness.evaluate(scene, ConstraintAccuracyHarness.MEAN_SCALE, 6)!!, 1e-9)
        assertEquals(0.0, ConstraintAccuracyHarness.evaluate(scene, ConstraintAccuracyHarness.SINGLE_SCALE, 1)!!, 1e-9)
    }

    @Test
    fun evaluate_should_ReturnNull_When_SolverDeclines() {
        val scene = assertNotNull(GoldenSceneGenerator.random(SceneFamily.IN_MODEL, Random(1), 6, 0.0, 0.0))
        assertEquals(null, ConstraintAccuracyHarness.evaluate(scene, { _, _, _ -> null }, 6))
    }

    @Test
    fun harness_should_ShowAveragingBeatsASingleReference_When_OnlyTapNoise() {
        val table = ConstraintAccuracyHarness.runFamily(SceneFamily.IN_MODEL, solvers, n = 6, scenes = 300, seed = 7, tiltDeg = 0.0, tapNoisePx = 1.0)
        println(table.format())
        assertTrue(table.mae("mean") < table.mae("single"), "mean=${table.mae("mean")} single=${table.mae("single")}")
    }

    @Test
    fun harness_should_PrintMaePerNAndPerFamily_ForEveryFamily() {
        val lines = mutableListOf<String>()
        for (family in SceneFamily.entries) {
            for (n in listOf(3, 4, 5, 6)) {
                val table = ConstraintAccuracyHarness.runFamily(family, solvers, n, scenes = 120, seed = 100 + n, tiltDeg = 5.0, tapNoisePx = 1.0)
                lines += table.format()
                assertTrue(table.errors.getValue("mean").size == 120, "$family N=$n")
            }
        }
        println("held-out MAE table (single-scale vs mean-scale; tilt 5 deg, 1 px taps)\n" + lines.joinToString("\n"))
        assertEquals(SceneFamily.entries.size * 4, lines.size)
    }

    @Test
    fun pairedUpperBound_should_BeNegative_When_FirstIsClearlySmaller() {
        val a = List(100) { 1.0 }
        val b = List(100) { 2.0 }
        assertTrue(ConstraintAccuracyHarness.pairedUpperBound(a, b, 1.02) < 0.0)
        assertTrue(ConstraintAccuracyHarness.pairedUpperBound(b, a, 1.02) > 0.0)
    }
}
