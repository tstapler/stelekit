// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** A 3x3 projective map, row-major in [values] (9 numbers), taking source points to destination points. */
class Homography(val values: List<Double>) {
    init {
        require(values.size == CELLS) { "a homography has 9 entries, got ${values.size}" }
    }

    /** Maps [p]; null when it lands on the line at infinity. */
    fun apply(p: PxPoint): PxPoint? {
        val w = values[6] * p.x + values[7] * p.y + values[8]
        if (abs(w) < MIN_W) return null
        return PxPoint(
            (values[0] * p.x + values[1] * p.y + values[2]) / w,
            (values[3] * p.x + values[4] * p.y + values[5]) / w,
        )
    }

    companion object {
        private const val CELLS = 9
        private const val MIN_W = 1e-12
    }
}

/** Why a homography could not be fitted. */
enum class HomographyFailure { TOO_FEW_POINTS, MISMATCHED_POINTS, DEGENERATE_POINTS }

sealed interface HomographyFit {
    /** [rmsError] is the root-mean-square reprojection error in destination units. */
    data class Success(val homography: Homography, val rmsError: Double, val maxError: Double) : HomographyFit

    data class Failure(val reason: HomographyFailure) : HomographyFit
}

/**
 * Direct linear transform with Hartley normalisation. Four points fit exactly; more are fitted by least
 * squares. Pure and platform-agnostic.
 */
object HomographyFitter {
    private const val MIN_POINTS = 4
    private const val UNKNOWNS = 8
    private const val COLLINEAR_AREA_FRACTION = 1e-6
    private const val PIVOT_EPSILON = 1e-12

    fun fit(src: List<PxPoint>, dst: List<PxPoint>): HomographyFit {
        if (src.size != dst.size) return HomographyFit.Failure(HomographyFailure.MISMATCHED_POINTS)
        if (src.size < MIN_POINTS) return HomographyFit.Failure(HomographyFailure.TOO_FEW_POINTS)
        if (anyThreeCollinear(src) || anyThreeCollinear(dst)) return HomographyFit.Failure(HomographyFailure.DEGENERATE_POINTS)

        val ts = Normalisation.of(src)
        val td = Normalisation.of(dst)
        val a = src.map(ts::forward)
        val b = dst.map(td::forward)

        // Unknowns h1..h8 with h9 = 1; normal equations (A^T A) h = A^T r.
        val ata = Array(UNKNOWNS) { DoubleArray(UNKNOWNS) }
        val atr = DoubleArray(UNKNOWNS)
        for (i in a.indices) {
            val (x, y) = a[i]
            val (u, v) = b[i]
            accumulate(ata, atr, doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y), u)
            accumulate(ata, atr, doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y), v)
        }
        val h = solve(ata, atr) ?: return HomographyFit.Failure(HomographyFailure.DEGENERATE_POINTS)
        val normalised = listOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0)
        val full = denormalise(normalised, ts, td)
        val homography = Homography(full)

        val errors = src.indices.map { i ->
            val mapped = homography.apply(src[i]) ?: return HomographyFit.Failure(HomographyFailure.DEGENERATE_POINTS)
            hypot(mapped.x - dst[i].x, mapped.y - dst[i].y)
        }
        val rms = sqrt(errors.sumOf { it * it } / errors.size)
        return HomographyFit.Success(homography, rms, errors.max())
    }

    private fun accumulate(ata: Array<DoubleArray>, atr: DoubleArray, row: DoubleArray, rhs: Double) {
        for (i in 0 until UNKNOWNS) {
            atr[i] += row[i] * rhs
            for (j in 0 until UNKNOWNS) ata[i][j] += row[i] * row[j]
        }
    }

    /** Gaussian elimination with partial pivoting; null when the system is singular. */
    private fun solve(m: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
        val n = rhs.size
        val a = Array(n) { m[it].copyOf() }
        val b = rhs.copyOf()
        val scale = a.maxOf { row -> row.maxOf { abs(it) } }.coerceAtLeast(1.0)
        for (col in 0 until n) {
            val pivot = (col until n).maxBy { abs(a[it][col]) }
            if (abs(a[pivot][col]) < PIVOT_EPSILON * scale) return null
            a[col] = a[pivot].also { a[pivot] = a[col] }
            b[col] = b[pivot].also { b[pivot] = b[col] }
            for (r in col + 1 until n) {
                val f = a[r][col] / a[col][col]
                for (c in col until n) a[r][c] -= f * a[col][c]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (c in r + 1 until n) s -= a[r][c] * x[c]
            x[r] = s / a[r][r]
        }
        return x
    }

    /** `Td^-1 * Hn * Ts`, scaled so the last entry is 1 (or left alone when it is ~0). */
    private fun denormalise(hn: List<Double>, ts: Normalisation, td: Normalisation): List<Double> {
        val ht = multiply(hn, ts.matrix())
        val result = multiply(td.inverseMatrix(), ht)
        val last = result[8]
        return if (abs(last) < PIVOT_EPSILON) result else result.map { it / last }
    }

    private fun multiply(p: List<Double>, q: List<Double>): List<Double> = List(9) { idx ->
        val r = idx / 3
        val c = idx % 3
        (0 until 3).sumOf { k -> p[r * 3 + k] * q[k * 3 + c] }
    }

    private fun anyThreeCollinear(pts: List<PxPoint>): Boolean {
        val extent = max(
            pts.maxOf { it.x } - pts.minOf { it.x },
            pts.maxOf { it.y } - pts.minOf { it.y },
        )
        if (extent <= 0.0) return true
        val tolerance = COLLINEAR_AREA_FRACTION * extent * extent
        for (i in pts.indices) for (j in i + 1 until pts.size) for (k in j + 1 until pts.size) {
            val twiceArea = abs((pts[j].x - pts[i].x) * (pts[k].y - pts[i].y) - (pts[k].x - pts[i].x) * (pts[j].y - pts[i].y))
            if (twiceArea < tolerance) return true
        }
        return false
    }

    /** Translate to the centroid and scale so the mean distance from it is sqrt(2). */
    private class Normalisation(val cx: Double, val cy: Double, val s: Double) {
        fun forward(p: PxPoint): Pair<Double, Double> = (p.x - cx) * s to (p.y - cy) * s

        fun matrix(): List<Double> = listOf(s, 0.0, -s * cx, 0.0, s, -s * cy, 0.0, 0.0, 1.0)

        fun inverseMatrix(): List<Double> = listOf(1.0 / s, 0.0, cx, 0.0, 1.0 / s, cy, 0.0, 0.0, 1.0)

        companion object {
            fun of(pts: List<PxPoint>): Normalisation {
                val cx = pts.sumOf { it.x } / pts.size
                val cy = pts.sumOf { it.y } / pts.size
                val mean = pts.sumOf { hypot(it.x - cx, it.y - cy) } / pts.size
                return Normalisation(cx, cy, if (mean > 0.0) sqrt(2.0) / mean else 1.0)
            }
        }
    }
}
