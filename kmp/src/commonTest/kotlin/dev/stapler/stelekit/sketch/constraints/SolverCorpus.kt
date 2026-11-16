// jvmTest-reason: n/a (commonTest helper; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.constraints

import dev.stapler.stelekit.sketch.constraints.constraints.Coincident
import dev.stapler.stelekit.sketch.constraints.constraints.ConstraintEquation
import dev.stapler.stelekit.sketch.constraints.constraints.DistanceDimension
import dev.stapler.stelekit.sketch.constraints.constraints.EqualLength
import dev.stapler.stelekit.sketch.constraints.constraints.Horizontal
import dev.stapler.stelekit.sketch.constraints.constraints.Vertical
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** The Spike 0.1.2 generator on the production equations: 40 chained lines, exactly 100 constraints, start perturbed by +/-0.4. */
internal object SolverCorpus {
    class Case(val problem: ConstraintProblem, val start: DoubleArray, val truth: DoubleArray)

    const val CONSTRAINTS = 100
    const val LINES = 40
    private const val FREE_ANGLE_LINES = 30
    private const val EQUAL_COUNT = 16
    private const val EQUAL_RANGE = 25
    private const val TWO_PI_ROUNDED = 6.28318
    private const val START_SPREAD = 0.8

    /** The seeds of the spike's 30-seed corpus; 111 and 127 are the damped-plateau tail of ADR-003. */
    val corpusSeeds: IntRange = 100 until 130
    val tailSeeds: List<Int> = listOf(111, 127)

    fun hundredConstraints(seed: Int): Case {
        val rnd = Random(seed)
        val lens = doubleArrayOf(1.0, 2.0, 3.0)
        val chain = ArrayList<Double>()
        var cx = 0.0
        var cy = 0.0
        val kinds = CharArray(LINES)
        val length = DoubleArray(LINES)
        chain += cx; chain += cy
        for (i in 0 until LINES) {
            length[i] = lens[rnd.nextInt(3)]
            if (i < FREE_ANGLE_LINES) {
                kinds[i] = if (rnd.nextBoolean()) 'H' else 'V'
                val s = if (rnd.nextBoolean()) 1.0 else -1.0
                if (kinds[i] == 'H') cx += s * length[i] else cy += s * length[i]
            } else {
                kinds[i] = 'D'
                val a = rnd.nextDouble() * TWO_PI_ROUNDED
                cx += length[i] * cos(a); cy += length[i] * sin(a)
            }
            chain += cx; chain += cy
        }
        val a = IntArray(LINES)
        val b = IntArray(LINES)
        val truth = ArrayList<Double>()
        for (i in 0 until LINES) {
            a[i] = truth.size; truth += chain[2 * i]; truth += chain[2 * i + 1]
            b[i] = truth.size; truth += chain[2 * i + 2]; truth += chain[2 * i + 3]
        }
        val cs = ArrayList<ConstraintEquation>()
        for (i in 0 until LINES - 1) cs += Coincident("co$i", b[i], a[i + 1])
        for (i in 0 until FREE_ANGLE_LINES) cs += if (kinds[i] == 'H') Horizontal("h$i", a[i], b[i]) else Vertical("v$i", a[i], b[i])
        var equalCount = 0
        for (l in lens) {
            val group = (0 until EQUAL_RANGE).filter { length[it] == l }
            for (k in 0 until group.size - 1) {
                if (equalCount == EQUAL_COUNT) break
                cs += EqualLength("eq$equalCount", a[group[k]], b[group[k]], a[group[k + 1]], b[group[k + 1]]); equalCount++
            }
        }
        check(equalCount == EQUAL_COUNT) { "generator must produce $EQUAL_COUNT Equal constraints" }
        for (i in EQUAL_RANGE until LINES) cs += DistanceDimension("d$i", a[i], b[i], length[i])
        check(cs.size == CONSTRAINTS)
        val truthArr = truth.toDoubleArray()
        val start = DoubleArray(truthArr.size) { truthArr[it] + (rnd.nextDouble() - 0.5) * START_SPREAD }
        return Case(ConstraintProblem(cs, truthArr.size), start, truthArr)
    }

    fun maxResidual(problem: ConstraintProblem, x: DoubleArray): Double {
        val r = DoubleArray(problem.rowCount)
        var off = 0
        for (eq in problem.equations) { eq.residual(x, r, off); off += eq.rowCount }
        return r.maxOfOrNull { abs(it) } ?: 0.0
    }
}
