// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import kotlin.math.abs
import kotlin.math.sqrt

/** Small dense-matrix and Student-t helpers for the constraint fits. Pure; no dependencies. */
internal object FitStatistics {
    /** Two-sided 99 percent (`t_{0.995}`) critical values for 1..30 degrees of freedom (standard tables). */
    private val T995 = doubleArrayOf(
        63.657, 9.925, 5.841, 4.604, 4.032, 3.707, 3.499, 3.355, 3.250, 3.169,
        3.106, 3.055, 3.012, 2.977, 2.947, 2.921, 2.898, 2.878, 2.861, 2.845,
        2.831, 2.819, 2.807, 2.797, 2.787, 2.779, 2.771, 2.763, 2.756, 2.750,
    )
    private const val ASYMPTOTE = 2.576
    private const val TAIL_COEFFICIENT = 5.2
    private const val MIN_PIVOT = 1e-7

    /** `t_{0.995, nu}`; beyond the table a `1/nu` interpolation to the normal value. */
    fun studentT995(nu: Int): Double = when {
        nu < 1 -> Double.POSITIVE_INFINITY
        nu <= T995.size -> T995[nu - 1]
        else -> ASYMPTOTE + TAIL_COEFFICIENT / nu
    }

    fun median(values: List<Double>): Double {
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }

    /** Inverse of a small square matrix by solving against the unit vectors; null when singular. */
    fun invert(a: Array<DoubleArray>): Array<DoubleArray>? {
        val n = a.size
        val cols = (0 until n).map { c ->
            HomographyFitter.solve(a, DoubleArray(n) { if (it == c) 1.0 else 0.0 }) ?: return null
        }
        return Array(n) { r -> DoubleArray(n) { c -> cols[c][r] } }
    }

    /**
     * True when the Gram matrix `a = J^T J` has full rank after scaling every column to unit length, so
     * a column in a different unit (a gradient against a scale) cannot hide a dependency.
     */
    fun isFullRank(a: Array<DoubleArray>): Boolean {
        val n = a.size
        val d = DoubleArray(n) { sqrt(a[it][it]) }
        if (d.any { !it.isFinite() || it < MIN_PIVOT * MIN_PIVOT }) return false
        val c = Array(n) { i -> DoubleArray(n) { j -> a[i][j] / (d[i] * d[j]) } }
        for (k in 0 until n) {
            var pivot = c[k][k]
            for (j in 0 until k) pivot -= c[k][j] * c[k][j]
            if (pivot < MIN_PIVOT) return false
            c[k][k] = sqrt(pivot)
            for (i in k + 1 until n) {
                var v = c[i][k]
                for (j in 0 until k) v -= c[i][j] * c[k][j]
                c[i][k] = v / c[k][k]
            }
        }
        return true
    }

    fun relativeErrorPct(predicted: Double, truth: Double): Double = abs(predicted - truth) / truth * PERCENT

    const val PERCENT = 100.0
}
