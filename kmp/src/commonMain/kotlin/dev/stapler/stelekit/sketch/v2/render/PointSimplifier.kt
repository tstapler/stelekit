// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.render

import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import kotlin.math.abs
import kotlin.math.sqrt

/** A committed stroke: [pressures] is empty exactly when [simulatePressure] is true. */
data class SimplifiedStroke(val points: List<ScenePoint>, val pressures: List<Double>, val simulatePressure: Boolean)

/**
 * Ramer-Douglas-Peucker thinning of a raw freedraw stroke at commit time (Story 2.1.4). Always runs at
 * [BASE_EPSILON] scene units; when more
 * than `SceneLimits.maxPointsPerElement` points survive, the tolerance doubles until the stroke fits, so the cap
 * always holds and the endpoints always survive. Pure and deterministic; iterative, so a 20,000-point stroke
 * cannot overflow the stack.
 */
object PointSimplifier {
    /** Tolerance of the first pass, in scene units. */
    const val BASE_EPSILON = 0.25
    private const val EPSILON_GROWTH = 2.0
    private const val MIN_STROKE_POINTS = 2

    /**
     * [pressures] pairs with [points] one to one; any other length (including none, as a mouse gives) means the
     * reading is absent and the stroke simulates pressure instead.
     */
    fun simplify(points: List<ScenePoint>, pressures: List<Double>, limits: SceneLimits = SceneLimits.Default): SimplifiedStroke {
        val paired = pressures.size == points.size && points.isNotEmpty()
        val cap = maxOf(limits.maxPointsPerElement, MIN_STROKE_POINTS)
        val xs = DoubleArray(points.size) { points[it].x.value }
        val ys = DoubleArray(points.size) { points[it].y.value }
        var kept = keepIndices(xs, ys, BASE_EPSILON)
        var epsilon = BASE_EPSILON
        while (kept.size > cap) {
            epsilon *= EPSILON_GROWTH
            kept = keepIndices(xs, ys, epsilon)
        }
        return SimplifiedStroke(
            points = kept.map { points[it] },
            pressures = if (paired) kept.map { pressures[it] } else emptyList(),
            simulatePressure = !paired,
        )
    }

    /** Ascending indices of the points that survive RDP at [epsilon]; the first and last always do. */
    internal fun keepIndices(xs: DoubleArray, ys: DoubleArray, epsilon: Double): List<Int> {
        val n = xs.size
        if (n <= MIN_STROKE_POINTS) return List(n) { it }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var worst = -1.0
            var worstAt = -1
            for (i in a + 1 until b) {
                val d = distanceToSegment(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b])
                if (d > worst) {
                    worst = d
                    worstAt = i
                }
            }
            if (worstAt >= 0 && worst > epsilon) {
                keep[worstAt] = true
                stack.addLast(intArrayOf(a, worstAt))
                stack.addLast(intArrayOf(worstAt, b))
            }
        }
        return (0 until n).filter { keep[it] }
    }

    private fun distanceToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val lengthSq = dx * dx + dy * dy
        if (lengthSq == 0.0) return sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
        return abs(dy * (px - ax) - dx * (py - ay)) / sqrt(lengthSq)
    }
}
