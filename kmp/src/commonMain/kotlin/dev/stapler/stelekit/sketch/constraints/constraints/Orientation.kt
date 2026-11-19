package dev.stapler.stelekit.sketch.constraints.constraints

import dev.stapler.stelekit.sketch.constraints.linalg.DenseMatrix

/** Line [a]-[b] horizontal: `ay = by`. */
class Horizontal(override val id: String, private val a: Int, private val b: Int) : ConstraintEquation {
    override val rowCount = 1
    override fun residual(x: DoubleArray, out: DoubleArray, off: Int) { out[off] = x[a + 1] - x[b + 1] }
    override fun jacobian(x: DoubleArray, j: DenseMatrix, row0: Int) {
        j.addAt(row0, a + 1, 1.0); j.addAt(row0, b + 1, -1.0)
    }
}

/** Line [a]-[b] vertical: `ax = bx`. */
class Vertical(override val id: String, private val a: Int, private val b: Int) : ConstraintEquation {
    override val rowCount = 1
    override fun residual(x: DoubleArray, out: DoubleArray, off: Int) { out[off] = x[a] - x[b] }
    override fun jacobian(x: DoubleArray, j: DenseMatrix, row0: Int) {
        j.addAt(row0, a, 1.0); j.addAt(row0, b, -1.0)
    }
}

/** Segments a1-a2 and b1-b2 parallel: `cross(u, v) = 0` (polynomial, so exact and finite for a zero-length segment). */
class Parallel(override val id: String, a1: Int, a2: Int, b1: Int, b2: Int) : ConstraintEquation {
    private val s = TwoSegments(a1, a2, b1, b2)
    override val rowCount = 1
    override fun residual(x: DoubleArray, out: DoubleArray, off: Int) {
        out[off] = s.ux(x) * s.vy(x) - s.uy(x) * s.vx(x)
    }
    override fun jacobian(x: DoubleArray, j: DenseMatrix, row0: Int) =
        s.addPartials(j, row0, s.vy(x), -s.vx(x), -s.uy(x), s.ux(x))
}

/** Segments a1-a2 and b1-b2 perpendicular: `dot(u, v) = 0`. */
class Perpendicular(override val id: String, a1: Int, a2: Int, b1: Int, b2: Int) : ConstraintEquation {
    private val s = TwoSegments(a1, a2, b1, b2)
    override val rowCount = 1
    override fun residual(x: DoubleArray, out: DoubleArray, off: Int) {
        out[off] = s.ux(x) * s.vx(x) + s.uy(x) * s.vy(x)
    }
    override fun jacobian(x: DoubleArray, j: DenseMatrix, row0: Int) =
        s.addPartials(j, row0, s.vx(x), s.vy(x), s.ux(x), s.uy(x))
}

/** Segments of equal length: `|a1 - a2| = |b1 - b2|`. */
class EqualLength(override val id: String, private val a1: Int, private val a2: Int, private val b1: Int, private val b2: Int) : ConstraintEquation {
    override val rowCount = 1
    override fun residual(x: DoubleArray, out: DoubleArray, off: Int) { out[off] = dist(x, a1, a2) - dist(x, b1, b2) }
    override fun jacobian(x: DoubleArray, j: DenseMatrix, row0: Int) {
        addLengthGradient(x, a1, a2, 1.0, j, row0)
        addLengthGradient(x, b1, b2, -1.0, j, row0)
    }
}

/** Two circles or arcs of equal radius. */
class EqualRadius(override val id: String, private val r1: RadiusRef, private val r2: RadiusRef) : ConstraintEquation {
    override val rowCount = 1
    override fun residual(x: DoubleArray, out: DoubleArray, off: Int) { out[off] = r1.value(x) - r2.value(x) }
    override fun jacobian(x: DoubleArray, j: DenseMatrix, row0: Int) {
        r1.addGradient(x, 1.0, j, row0)
        r2.addGradient(x, -1.0, j, row0)
    }
}
