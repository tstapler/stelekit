// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.constraints

import dev.stapler.stelekit.sketch.constraints.linalg.Cholesky
import dev.stapler.stelekit.sketch.constraints.linalg.DenseMatrix
import dev.stapler.stelekit.sketch.constraints.linalg.PivotedQr
import dev.stapler.stelekit.sketch.constraints.linalg.hasNonFinite
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Story 4.1.1 acceptance: rank recovery, damped Cholesky residual, buffer reuse. */
class LinalgPropertyTest {
    private fun randomMatrix(rng: Random, rows: Int, cols: Int) =
        DenseMatrix(rows, cols, DoubleArray(rows * cols) { rng.nextDouble(-1.0, 1.0) })

    private fun product(a: DenseMatrix, b: DenseMatrix): DenseMatrix {
        val out = DenseMatrix(a.rows, b.cols)
        for (i in 0 until a.rows) for (k in 0 until a.cols) {
            val aik = a[i, k]
            for (j in 0 until b.cols) out[i, j] = out[i, j] + aik * b[k, j]
        }
        return out
    }

    @Test
    fun qrRecoversRankOfRandom40x60RankDeficientMatrix() = runTest {
        checkAll(60, Arb.int()) { seed ->
            val rng = Random(seed)
            val m = product(randomMatrix(rng, 40, 25), randomMatrix(rng, 25, 60))
            assertEquals(25, PivotedQr(40, 60).factor(m), "seed $seed")
        }
    }

    @Test
    fun qrRankIsScaleInvariantAndFullRankIsFull() = runTest {
        checkAll(30, Arb.int()) { seed ->
            val rng = Random(seed)
            val a = randomMatrix(rng, 30, 20)
            val qr = PivotedQr(30, 20)
            assertEquals(20, qr.factor(a), "seed $seed")
            val scaled = DenseMatrix(30, 20, DoubleArray(a.data.size) { a.data[it] * 1e8 })
            assertEquals(20, qr.factor(scaled), "scaled, seed $seed")
        }
    }

    @Test
    fun qrZeroAndNonFiniteMatricesHaveRankZeroWithoutThrowing() {
        val qr = PivotedQr(4, 4)
        assertEquals(0, qr.factor(DenseMatrix(4, 4)))
        val bad = DenseMatrix(4, 4).also { it[1, 1] = Double.NaN }
        assertEquals(0, qr.factor(bad))
        bad[1, 1] = Double.POSITIVE_INFINITY
        assertEquals(0, qr.factor(bad))
    }

    @Test
    fun qrDuplicateColumnIsReportedAsDependent() {
        val rng = Random(7)
        val a = randomMatrix(rng, 6, 4)
        for (i in 0 until 6) a[i, 3] = a[i, 1]
        val qr = PivotedQr(6, 4)
        assertEquals(3, qr.factor(a))
        assertTrue(qr.perm[3] == 1 || qr.perm[3] == 3)
    }

    @Test
    fun choleskyOfDampedNormalEquationsSolvesToTinyResidual() = runTest {
        checkAll(60, Arb.int()) { seed ->
            val rng = Random(seed)
            val j = randomMatrix(rng, 40, 25)
            val g = DenseMatrix(25, 25)
            j.gramInto(g)
            val rhs = DoubleArray(25) { rng.nextDouble(-1.0, 1.0) }
            val lambda = 1e-3
            val chol = Cholesky(25)
            assertTrue(chol.factor(g, lambda), "seed $seed")
            val x = DoubleArray(25)
            chol.solve(rhs, x)
            for (i in 0 until 25) {
                var s = lambda * x[i]
                for (k in 0 until 25) s += g[i, k] * x[k]
                assertTrue(abs(s - rhs[i]) < 1e-9, "seed $seed row $i residual ${abs(s - rhs[i])}")
            }
        }
    }

    @Test
    fun choleskyRejectsIndefiniteAndNaNInput() {
        val chol = Cholesky(2)
        val indefinite = DenseMatrix(2, 2, doubleArrayOf(1.0, 2.0, 2.0, 1.0))
        assertFalse(chol.factor(indefinite))
        val nan = DenseMatrix(2, 2, doubleArrayOf(Double.NaN, 0.0, 0.0, 1.0))
        assertFalse(chol.factor(nan))
        assertTrue(chol.factor(indefinite, 5.0))
    }

    @Test
    fun repeatedCallsReuseBuffersAndStayDeterministic() {
        val rng = Random(42)
        val a = randomMatrix(rng, 20, 12)
        val qr = PivotedQr(20, 12)
        val rBuffer = qr.r
        val rData = qr.r.data
        val permBuffer = qr.perm
        qr.factor(a)
        val first = qr.r.data.copyOf()
        repeat(5) { qr.factor(a) }
        assertSame(rBuffer, qr.r)
        assertSame(rData, qr.r.data)
        assertSame(permBuffer, qr.perm)
        assertTrue(first.contentEquals(qr.r.data))

        val g = DenseMatrix(12, 12)
        val rhs = DoubleArray(12) { it + 1.0 }
        val out = DoubleArray(12)
        val chol = Cholesky(12)
        a.gramInto(g)
        chol.factor(g, 1e-6)
        chol.solve(rhs, out)
        val firstSolution = out.copyOf()
        repeat(5) {
            a.gramInto(g)
            chol.factor(g, 1e-6)
            chol.solve(rhs, out)
        }
        assertTrue(firstSolution.contentEquals(out))
        assertFalse(out.hasNonFinite())
    }

    @Test
    fun matrixVectorHelpersMatchDefinition() {
        val a = DenseMatrix(2, 3, doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0))
        val out2 = DoubleArray(2)
        a.timesInto(doubleArrayOf(1.0, 0.0, -1.0), out2)
        assertEquals(listOf(-2.0, -2.0), out2.toList())
        val out3 = DoubleArray(3)
        a.transposeTimesInto(doubleArrayOf(1.0, 1.0), out3)
        assertEquals(listOf(5.0, 7.0, 9.0), out3.toList())
    }
}
