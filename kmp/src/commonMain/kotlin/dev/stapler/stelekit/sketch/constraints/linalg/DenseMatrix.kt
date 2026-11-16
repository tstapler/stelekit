package dev.stapler.stelekit.sketch.constraints.linalg

/**
 * Row-major dense matrix over a flat [DoubleArray]. Clean-room (ADR-003, OD-3): textbook numerical
 * linear algebra only. Operations take caller-supplied output buffers so the solve loop allocates
 * nothing per iteration after warm-up.
 */
class DenseMatrix(val rows: Int, val cols: Int, val data: DoubleArray = DoubleArray(rows * cols)) {
    init {
        require(rows >= 0 && cols >= 0) { "negative shape" }
        require(data.size == rows * cols) { "data size ${data.size} != $rows x $cols" }
    }

    operator fun get(r: Int, c: Int): Double = data[r * cols + c]
    operator fun set(r: Int, c: Int, v: Double) { data[r * cols + c] = v }

    fun copy(): DenseMatrix = DenseMatrix(rows, cols, data.copyOf())

    fun copyFrom(other: DenseMatrix) {
        require(other.rows == rows && other.cols == cols) { "shape mismatch" }
        other.data.copyInto(data)
    }

    fun fill(v: Double) = data.fill(v)

    /** Writes A^T A (cols x cols) into [out], exploiting symmetry. */
    fun gramInto(out: DenseMatrix) {
        require(out.rows == cols && out.cols == cols) { "gram target must be ${cols}x$cols" }
        out.fill(0.0)
        for (i in 0 until rows) {
            val base = i * cols
            for (a in 0 until cols) {
                val va = data[base + a]
                if (va == 0.0) continue
                for (b in a until cols) {
                    val vb = data[base + b]
                    if (vb != 0.0) out.data[a * cols + b] += va * vb
                }
            }
        }
        for (a in 0 until cols) for (b in 0 until a) out.data[a * cols + b] = out.data[b * cols + a]
    }

    /** Writes A^T v into [out] (length cols). */
    fun transposeTimesInto(v: DoubleArray, out: DoubleArray) {
        require(v.size >= rows && out.size >= cols) { "vector too short" }
        out.fill(0.0, 0, cols)
        for (i in 0 until rows) {
            val vi = v[i]
            if (vi == 0.0) continue
            val base = i * cols
            for (c in 0 until cols) out[c] += data[base + c] * vi
        }
    }

    /** Writes A v into [out] (length rows). */
    fun timesInto(v: DoubleArray, out: DoubleArray) {
        require(v.size >= cols && out.size >= rows) { "vector too short" }
        for (i in 0 until rows) {
            var s = 0.0
            val base = i * cols
            for (c in 0 until cols) s += data[base + c] * v[c]
            out[i] = s
        }
    }
}

fun DoubleArray.hasNonFinite(): Boolean {
    for (x in this) if (!x.isFinite()) return true
    return false
}
