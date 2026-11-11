package dev.stapler.stelekit.calibration

import kotlin.math.hypot

/** A point in (possibly undistorted) image pixels; unlike NormalizedPoint it may lie outside the frame. */
data class PxPoint(val x: Double, val y: Double)

/**
 * One-parameter radial division model: `undistorted = c + (p - c) / (1 + k r^2)`, with `c` the image
 * centre and `r` the distance from it divided by the half-diagonal. `k < 0` undoes barrel distortion
 * (typical phone main camera: roughly -0.005 to -0.02), `k > 0` pincushion, `k = 0` is the identity.
 */
object RadialLens {
    const val MAX_ABS_K = 0.15
    private const val MIN_DENOMINATOR = 0.2

    fun undistort(xNorm: Double, yNorm: Double, k: Double, widthPx: Double, heightPx: Double): PxPoint {
        val x = xNorm * widthPx
        val y = yNorm * heightPx
        if (k == 0.0) return PxPoint(x, y)
        val cx = widthPx / 2.0
        val cy = heightPx / 2.0
        val dx = x - cx
        val dy = y - cy
        val r = hypot(dx, dy) / halfDiagonal(widthPx, heightPx)
        val denominator = (1.0 + k * r * r).coerceAtLeast(MIN_DENOMINATOR)
        return PxPoint(cx + dx / denominator, cy + dy / denominator)
    }

    /** Distance of a normalized point from the image centre as a fraction of the half-diagonal (0 centre, 1 corner). */
    fun radius(xNorm: Double, yNorm: Double, widthPx: Double, heightPx: Double): Double =
        hypot((xNorm - 0.5) * widthPx, (yNorm - 0.5) * heightPx) / halfDiagonal(widthPx, heightPx)

    private fun halfDiagonal(widthPx: Double, heightPx: Double): Double = hypot(widthPx, heightPx) / 2.0
}
