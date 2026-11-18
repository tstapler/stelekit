// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** Predicts the metric length of [target] from the known distances [train]; null when the solver declines. */
fun interface HeldOutSolver {
    fun predict(scene: GoldenScene, train: List<GoldenSegment>, target: GoldenSegment): Double?
}

private const val NAME_WIDTH = 22

private fun fixed3(v: Double): String = (kotlin.math.round(v * 1000.0) / 1000.0).toString()

/** Per-scene held-out errors (percent of the true length) of several solvers, paired by scene. */
class FamilyTable(val family: SceneFamily, val n: Int, val errors: Map<String, List<Double>>) {
    fun mae(name: String): Double = errors.getValue(name).average()

    fun format(): String = buildString {
        append(family.name.padEnd(NAME_WIDTH)).append(" N=").append(n)
        for ((name, list) in errors) append("  ").append(name).append('=').append(fixed3(list.average())).append('%')
    }
}

/**
 * Held-out evaluation (Story 3.1): every solver sees the first N distances of a scene and is graded on the
 * withheld one. Errors are percent of the true length so scenes of different sizes average sensibly.
 */
object ConstraintAccuracyHarness {
    private const val PERCENT = 100.0
    private const val CI_Z = 1.96

    /** The status quo: the first known distance alone sets one scale for the whole photo. */
    val SINGLE_SCALE = HeldOutSolver { scene, train, target ->
        train.firstOrNull()?.let { target.pixelLength(scene) * it.metresPerPixel(scene) }
    }

    /** Geometric mean of every known distance's scale (the SCALE tier without gates). */
    val MEAN_SCALE = HeldOutSolver { scene, train, target ->
        if (train.isEmpty()) {
            null
        } else {
            target.pixelLength(scene) * exp(train.map { ln(it.metresPerPixel(scene)) }.average())
        }
    }

    /** Absolute held-out error in percent for one scene and N, or null when [solver] declines. */
    fun evaluate(scene: GoldenScene, solver: HeldOutSolver, n: Int): Double? {
        val predicted = solver.predict(scene, scene.training.take(n), scene.heldOut) ?: return null
        return abs(predicted - scene.heldOut.trueMeters) / scene.heldOut.trueMeters * PERCENT
    }

    /** Runs [scenes] random scenes of [family]; scenes any solver declines count as that solver's SINGLE_SCALE-free failure (skipped). */
    @Suppress("LongParameterList")
    fun runFamily(
        family: SceneFamily,
        solvers: Map<String, HeldOutSolver>,
        n: Int,
        scenes: Int,
        seed: Int,
        tiltDeg: Double,
        tapNoisePx: Double,
    ): FamilyTable {
        val rnd = Random(seed)
        val errors = solvers.keys.associateWith { mutableListOf<Double>() }
        var made = 0
        while (made < scenes) {
            val scene = GoldenSceneGenerator.random(family, rnd, n, tiltDeg, tapNoisePx) ?: continue
            val row = solvers.mapValues { (_, s) -> evaluate(scene, s, n) }
            // Paired comparison: drop the scene for every solver when any declines so the means stay comparable.
            if (row.values.any { it == null }) {
                made++
                continue
            }
            row.forEach { (name, e) -> errors.getValue(name) += e!! }
            made++
        }
        return FamilyTable(family, n, errors)
    }

    /** Upper end of the 95 percent confidence interval of the mean of `a[i] - ratio * b[i]` (paired by scene). */
    fun pairedUpperBound(a: List<Double>, b: List<Double>, ratio: Double): Double {
        require(a.size == b.size && a.isNotEmpty()) { "paired samples must be non-empty and equal length" }
        val d = a.indices.map { a[it] - ratio * b[it] }
        val mean = d.average()
        val sd = sqrt(d.sumOf { (it - mean) * (it - mean) } / (d.size - 1).coerceAtLeast(1))
        return mean + CI_Z * sd / sqrt(d.size.toDouble())
    }

    private fun GoldenSegment.pixelLength(scene: GoldenScene): Double =
        hypot((p2.x - p1.x) * scene.widthPx, (p2.y - p1.y) * scene.heightPx)

    private fun GoldenSegment.metresPerPixel(scene: GoldenScene): Double = trueMeters / pixelLength(scene)
}
