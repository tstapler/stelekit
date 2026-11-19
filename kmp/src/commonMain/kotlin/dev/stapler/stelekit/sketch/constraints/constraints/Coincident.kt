package dev.stapler.stelekit.sketch.constraints.constraints

import dev.stapler.stelekit.sketch.constraints.linalg.DenseMatrix

/** Points [p] and [q] coincide (2 rows). */
class Coincident(override val id: String, private val p: Int, private val q: Int) : ConstraintEquation {
    override val rowCount = 2
    override fun residual(x: DoubleArray, out: DoubleArray, off: Int) {
        out[off] = x[p] - x[q]
        out[off + 1] = x[p + 1] - x[q + 1]
    }
    override fun jacobian(x: DoubleArray, j: DenseMatrix, row0: Int) {
        j.addAt(row0, p, 1.0); j.addAt(row0, q, -1.0)
        j.addAt(row0 + 1, p + 1, 1.0); j.addAt(row0 + 1, q + 1, -1.0)
    }
}

/** Point [p] stays at ([tx], [ty]): Fixed, and the pointer target of a drag (2 rows). */
class FixedPoint(override val id: String, private val p: Int, var tx: Double, var ty: Double) : ConstraintEquation {
    override val rowCount = 2
    override fun residual(x: DoubleArray, out: DoubleArray, off: Int) {
        out[off] = x[p] - tx
        out[off + 1] = x[p + 1] - ty
    }
    override fun jacobian(x: DoubleArray, j: DenseMatrix, row0: Int) {
        j.addAt(row0, p, 1.0)
        j.addAt(row0 + 1, p + 1, 1.0)
    }
}

/** Scalar variable [v] (a radius) stays at [value] (1 row). */
class FixedScalar(override val id: String, private val v: Int, var value: Double) : ConstraintEquation {
    override val rowCount = 1
    override fun residual(x: DoubleArray, out: DoubleArray, off: Int) { out[off] = x[v] - value }
    override fun jacobian(x: DoubleArray, j: DenseMatrix, row0: Int) { j.addAt(row0, v, 1.0) }
}

/** Point [p] lies on the infinite line through [a] and [b]: `cross(b - a, p - a) = 0` (1 row, polynomial). */
class PointOnLine(override val id: String, private val p: Int, private val a: Int, private val b: Int) : ConstraintEquation {
    override val rowCount = 1
    override fun residual(x: DoubleArray, out: DoubleArray, off: Int) {
        out[off] = (x[b] - x[a]) * (x[p + 1] - x[a + 1]) - (x[b + 1] - x[a + 1]) * (x[p] - x[a])
    }

    override fun jacobian(x: DoubleArray, j: DenseMatrix, row0: Int) {
        val ux = x[b] - x[a]; val uy = x[b + 1] - x[a + 1]
        val wx = x[p] - x[a]; val wy = x[p + 1] - x[a + 1]
        // cross = ux*wy - uy*wx with u = b - a, w = p - a.
        j.addAt(row0, b, wy); j.addAt(row0, a, -wy)
        j.addAt(row0, b + 1, -wx); j.addAt(row0, a + 1, wx)
        j.addAt(row0, p + 1, ux); j.addAt(row0, a + 1, -ux)
        j.addAt(row0, p, -uy); j.addAt(row0, a, uy)
    }
}

/** Point [p] lies on the circle (or arc circle) at [center] with [radius] (1 row: `|p - c| - r`). */
class PointOnCircle(override val id: String, private val p: Int, private val center: Int, private val radius: RadiusRef) : ConstraintEquation {
    override val rowCount = 1
    override fun residual(x: DoubleArray, out: DoubleArray, off: Int) { out[off] = dist(x, center, p) - radius.value(x) }
    override fun jacobian(x: DoubleArray, j: DenseMatrix, row0: Int) {
        addLengthGradient(x, center, p, 1.0, j, row0)
        radius.addGradient(x, -1.0, j, row0)
    }
}
