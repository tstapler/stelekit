package dev.stapler.stelekit.sketch.constraints.constraints

import dev.stapler.stelekit.sketch.constraints.linalg.DenseMatrix

/**
 * Points [p] and [q] are mirror images about the line [a]-[b] (2 rows): `dot(q - p, b - a) = 0` (the
 * connector is perpendicular to the axis) and `cross(b - a, m - a) = 0` with `m = (p + q) / 2` (the
 * midpoint is on the axis). Both rows are polynomial, so they stay finite for a zero-length axis.
 */
class Symmetric(override val id: String, private val p: Int, private val q: Int, private val a: Int, private val b: Int) : ConstraintEquation {
    override val rowCount = 2

    override fun residual(x: DoubleArray, out: DoubleArray, off: Int) {
        val ux = x[b] - x[a]; val uy = x[b + 1] - x[a + 1]
        out[off] = (x[q] - x[p]) * ux + (x[q + 1] - x[p + 1]) * uy
        val mx = (x[p] + x[q]) / 2.0 - x[a]
        val my = (x[p + 1] + x[q + 1]) / 2.0 - x[a + 1]
        out[off + 1] = ux * my - uy * mx
    }

    override fun jacobian(x: DoubleArray, j: DenseMatrix, row0: Int) {
        val ux = x[b] - x[a]; val uy = x[b + 1] - x[a + 1]
        val dx = x[q] - x[p]; val dy = x[q + 1] - x[p + 1]
        // Row 0: dot(d, u), d = q - p, u = b - a.
        j.addAt(row0, q, ux); j.addAt(row0, p, -ux)
        j.addAt(row0, q + 1, uy); j.addAt(row0, p + 1, -uy)
        j.addAt(row0, b, dx); j.addAt(row0, a, -dx)
        j.addAt(row0, b + 1, dy); j.addAt(row0, a + 1, -dy)
        // Row 1: cross(u, m) = ux*my - uy*mx, m = (p + q)/2 - a.
        val mx = (x[p] + x[q]) / 2.0 - x[a]
        val my = (x[p + 1] + x[q + 1]) / 2.0 - x[a + 1]
        val r = row0 + 1
        j.addAt(r, b, my); j.addAt(r, a, -my)
        j.addAt(r, b + 1, -mx); j.addAt(r, a + 1, mx)
        j.addAt(r, p + 1, ux / 2.0); j.addAt(r, q + 1, ux / 2.0)
        j.addAt(r, p, -uy / 2.0); j.addAt(r, q, -uy / 2.0)
        j.addAt(r, a + 1, -ux); j.addAt(r, a, uy)
    }
}
