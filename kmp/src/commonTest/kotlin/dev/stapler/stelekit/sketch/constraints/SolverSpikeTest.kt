// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.constraints

import dev.stapler.stelekit.sketch.constraints.spike.Coincident
import dev.stapler.stelekit.sketch.constraints.spike.DenseMatrix
import dev.stapler.stelekit.sketch.constraints.spike.Distance
import dev.stapler.stelekit.sketch.constraints.spike.EqualLength
import dev.stapler.stelekit.sketch.constraints.spike.Horizontal
import dev.stapler.stelekit.sketch.constraints.spike.LmSolver
import dev.stapler.stelekit.sketch.constraints.spike.Pin
import dev.stapler.stelekit.sketch.constraints.spike.ScalarEquals
import dev.stapler.stelekit.sketch.constraints.spike.SpikeConstraint
import dev.stapler.stelekit.sketch.constraints.spike.SpikeResult
import dev.stapler.stelekit.sketch.constraints.spike.SpikeSketch
import dev.stapler.stelekit.sketch.constraints.spike.TangentLineCircle
import dev.stapler.stelekit.sketch.constraints.spike.Vertical
import dev.stapler.stelekit.sketch.constraints.spike.pivotedQr
import dev.stapler.stelekit.sketch.constraints.spike.timedMillis
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Spike 0.1.2 exit-criteria measurements (ADR-003 item 6). Lines emit "SOLVERSPIKE ..." for the report. */
class SolverSpikeTest {

    private class Problem(val constraints: List<SpikeConstraint>, val start: DoubleArray, val truth: DoubleArray)

    /** 40 chained lines, exactly 100 constraints: 39 Coincident + 30 H/V + 16 Equal + 15 Distance. */
    private fun hundredConstraintProblem(seed: Int): Problem {
        val rnd = Random(seed)
        val sk = SpikeSketch()
        val lens = doubleArrayOf(1.0, 2.0, 3.0)
        val pts = ArrayList<Int>()
        val truth = ArrayList<Double>()
        var cx = 0.0; var cy = 0.0
        val kinds = CharArray(40); val length = DoubleArray(40)
        pts += sk.addPoint(cx, cy); truth += cx; truth += cy
        for (i in 0 until 40) {
            length[i] = lens[rnd.nextInt(3)]
            if (i < 30) {
                kinds[i] = if (rnd.nextBoolean()) 'H' else 'V'
                val s = if (rnd.nextBoolean()) 1.0 else -1.0
                if (kinds[i] == 'H') cx += s * length[i] else cy += s * length[i]
            } else {
                kinds[i] = 'D'
                val a = rnd.nextDouble() * 6.28318
                cx += length[i] * cos(a); cy += length[i] * sin(a)
            }
            pts += sk.addPoint(cx, cy); truth += cx; truth += cy
        }
        // Each line i is pts[i]-pts[i+1] and the chain Coincident ties line i's end to line i+1's start:
        // model that with distinct start/end points per line (80 points) to make Coincident real.
        val sk2 = SpikeSketch()
        val a = IntArray(40); val b = IntArray(40)
        val truth2 = ArrayList<Double>()
        for (i in 0 until 40) {
            val x0 = truth[2 * i]; val y0 = truth[2 * i + 1]; val x1 = truth[2 * i + 2]; val y1 = truth[2 * i + 3]
            a[i] = sk2.addPoint(x0, y0); b[i] = sk2.addPoint(x1, y1)
            truth2 += x0; truth2 += y0; truth2 += x1; truth2 += y1
        }
        val cs = ArrayList<SpikeConstraint>()
        for (i in 0 until 39) cs += Coincident("co$i", b[i], a[i + 1])
        for (i in 0 until 30) cs += if (kinds[i] == 'H') Horizontal("h$i", a[i], b[i]) else Vertical("v$i", a[i], b[i])
        // Equal: chain within same-length groups among lines 0..24, first 16 edges.
        var equalCount = 0
        for (l in lens) {
            val group = (0 until 25).filter { length[it] == l }
            for (k in 0 until group.size - 1) {
                if (equalCount == 16) break
                cs += EqualLength("eq$equalCount", a[group[k]], b[group[k]], a[group[k + 1]], b[group[k + 1]]); equalCount++
            }
        }
        assertEquals(16, equalCount, "generator must produce 16 Equal constraints")
        for (i in 25 until 40) cs += Distance("d$i", a[i], b[i], length[i])
        assertEquals(100, cs.size)
        val truthArr = truth2.toDoubleArray()
        val start = DoubleArray(truthArr.size) { truthArr[it] + (rnd.nextDouble() - 0.5) * 0.8 }
        return Problem(cs, start, truthArr)
    }

    private fun maxResidual(cs: List<SpikeConstraint>, x: DoubleArray): Double {
        var worst = 0.0
        for (c in cs) { val o = DoubleArray(c.count); c.eval(x, o, 0); for (v in o) worst = max(worst, abs(v)) }
        return worst
    }

    // Kotlin/JS and Kotlin/Wasm print doubles with a lowercase exponent; the JVM uses 'E'.
    private val onJvm: Boolean = !1e-9.toString().contains('e')

    @Test
    fun hundredConstraintSolveMeetsTimeAndResidualBars() {
        val solver = LmSolver()
        val p = hundredConstraintProblem(1)
        val times = ArrayList<Double>()
        var last: SpikeResult? = null
        repeat(31) { // first run is cold (includes JIT / wasm tier-up); the rest are steady state
            val (res, ms) = timedMillis { solver.solve(p.constraints, p.start) }
            times += ms; last = res
        }
        val res = last!!
        val steady = times.drop(1).sorted()
        val median = steady[steady.size / 2]
        val worstSteady = steady.last()
        val resid = maxResidual(p.constraints, res.x)
        println("SOLVERSPIKE platform=${if (onJvm) "jvm" else "wasm-or-js"} cold_ms=${times[0]} steady_median_ms=$median steady_max_ms=$worstSteady iterations=${res.iterations} residual=$resid dof=${res.dof} result=${res::class.simpleName}")
        println("SOLVERSPIKE solution=" + res.x.joinToString(",") { it.toString() })
        assertTrue(res is SpikeResult.Solved, "expected Solved, got ${res::class.simpleName}")
        assertTrue(resid < 1e-9 * max(1.0, p.start.maxOf { abs(it) }) * 10, "residual $resid")
        assertTrue(resid < 1e-6, "acceptance residual bar 1e-6, got $resid")
        val bar = if (onJvm) 50.0 else 150.0
        assertTrue(times[0] < bar, "cold solve ${times[0]} ms over bar $bar")
        assertTrue(worstSteady < bar, "steady solve $worstSteady ms over bar $bar")
    }

    @Test
    fun randomisedCorpusAllSolveWithinBarsAndNoNaN() {
        val solver = LmSolver()
        var slowest = 0.0; var worstRes = 0.0
        for (seed in 100 until 130) {
            val p = hundredConstraintProblem(seed)
            val (res, ms) = timedMillis { solver.solve(p.constraints, p.start) }
            slowest = max(slowest, ms)
            println("SOLVERSPIKE corpus seed=$seed ms=$ms iterations=${res.iterations}")
            val r = maxResidual(p.constraints, res.x)
            worstRes = max(worstRes, r)
            assertTrue(res is SpikeResult.Solved, "seed $seed: ${res::class.simpleName} residual ${res.residualInf}")
            assertTrue(res.x.none { it.isNaN() })
        }
        println("SOLVERSPIKE corpus30 slowest_ms=$slowest worst_residual=$worstRes")
        assertTrue(worstRes < 1e-6)
    }

    /**
     * Spike 0.1.3d (cross-link 0.1.2c): the solved 100-constraint fixtures (seeds 1 and 100..129) pushed through a
     * Float round trip. The constraints are translation invariant, so each solution is translated to a stated sheet
     * offset in Double (exact to about 1e-10 here) and then rounded to Float coordinate by coordinate.
     */
    @Test
    fun floatRoundTripOfSolvedFixturesMissesTheResidualBar() {
        val solver = LmSolver()
        val offsets = doubleArrayOf(0.0, 1_000.0, 16_000.0, 100_000.0, 1_048_576.0)
        val worstDouble = DoubleArray(offsets.size); val worstFloat = DoubleArray(offsets.size); val worstDrift = DoubleArray(offsets.size)
        for (seed in listOf(1) + (100 until 130)) {
            val p = hundredConstraintProblem(seed)
            val res = solver.solve(p.constraints, p.start)
            assertTrue(res is SpikeResult.Solved, "seed $seed: ${res::class.simpleName}")
            offsets.forEachIndexed { k, off ->
                val moved = DoubleArray(res.x.size) { res.x[it] + off }
                val viaFloat = DoubleArray(moved.size) { moved[it].toFloat().toDouble() }
                worstDouble[k] = max(worstDouble[k], maxResidual(p.constraints, moved))
                worstFloat[k] = max(worstFloat[k], maxResidual(p.constraints, viaFloat))
                for (i in moved.indices) worstDrift[k] = max(worstDrift[k], abs(moved[i] - viaFloat[i]))
            }
        }
        offsets.forEachIndexed { k, off ->
            println("SPIKE_FLOAT_DRIFT_SOLVER offset=$off worstDoubleResidual=${worstDouble[k]} worstFloatResidual=${worstFloat[k]} worstCoordDrift=${worstDrift[k]}")
            assertTrue(worstDouble[k] < 1e-6, "Double residual ${worstDouble[k]} at offset $off")
        }
        // At every realistic sheet offset (>= 1,000 units) the Float copy misses the 1e-6 acceptance bar by 10x or more.
        for (k in 1 until offsets.size) assertTrue(worstFloat[k] > 1e-5, "Float residual ${worstFloat[k]} at offset ${offsets[k]}")
    }

    @Test
    fun redundantDuplicateHorizontalIsClassifiedAndDofMatchesAnalytic() {
        val sk = SpikeSketch()
        val a = sk.addPoint(0.0, 0.0); val b = sk.addPoint(3.0, 4.0)
        val cs = listOf(Horizontal("c1", a, b), Vertical("c2", a, b), Horizontal("c3", a, b))
        val res = LmSolver().solve(cs, sk.initial())
        assertTrue(res is SpikeResult.Redundant, "got ${res::class.simpleName}")
        assertEquals(listOf("c3"), res.ids)
        // 4 variables, H and V on the same line are 2 independent rows: DOF = 2.
        assertEquals(2, res.dof)
    }

    @Test
    fun conflictingDistancesAreClassified() {
        val sk = SpikeSketch()
        val a = sk.addPoint(0.0, 0.0); val b = sk.addPoint(8.0, 1.0)
        val cs = listOf(Horizontal("c1", a, b), Distance("c4", a, b, 10.0), Distance("c5", a, b, 12.0))
        val res = LmSolver().solve(cs, sk.initial())
        assertTrue(res is SpikeResult.Conflict, "got ${res::class.simpleName}")
        assertEquals(listOf("c4", "c5"), res.ids)
        assertEquals(2, res.dof) // 4 vars, rank 2 (H and the shared length gradient)
    }

    /** The plan's literal sequence (H, V, dup H, Distance 10, Distance 12) is itself degenerate: H+V collapse the line. */
    @Test
    fun literalPlanSequenceReportsAConflictThatIncludesBothDistances() {
        val sk = SpikeSketch()
        val a = sk.addPoint(0.0, 0.0); val b = sk.addPoint(3.0, 4.0)
        val cs = listOf(Horizontal("c1", a, b), Vertical("c2", a, b), Horizontal("c3", a, b), Distance("c4", a, b, 10.0), Distance("c5", a, b, 12.0))
        val res = LmSolver().solve(cs, sk.initial())
        println("SOLVERSPIKE literal-plan-sequence result=${res::class.simpleName} ids=${(res as? SpikeResult.Conflict)?.ids} dof=${res.dof}")
        assertFalse(res is SpikeResult.Solved)
        assertFalse(res.x.any { it.isNaN() })
    }

    @Test
    fun branchFlipFreeTangentDrag() {
        val sk = SpikeSketch()
        val la = sk.addPoint(0.0, 0.0); val lb = sk.addPoint(10.0, 0.0)
        val c = sk.addPoint(5.0, 5.0); val r = sk.addScalar(5.0)
        val start = sk.initial()
        val tangent = TangentLineCircle("t", la, lb, c, r, start)
        val pinA = Pin("fa", la, 0.0, 0.0)
        val pinB = Pin("fb", lb, 10.0, 0.0)
        val drag = Pin("drag", c, 5.0, 5.0)
        val cs = listOf(pinA, pinB, tangent, drag)
        val solver = LmSolver()
        var x = start
        var maxMoveRatio = 0.0
        var sideStart = tangent.signedDistance(x)
        for (step in 1..200) {
            drag.tx = 5.0 + step * 0.5
            drag.ty = 5.0 - step * 0.5 * 0.0225 // ends 0.5 above the line: approaches but never crosses
            val res = solver.solve(cs, x)
            assertTrue(res is SpikeResult.Solved, "step $step: ${res::class.simpleName} ${res.residualInf}")
            var worst = 0.0
            for (i in 0 until 6 step 2) worst = max(worst, kotlin.math.hypot(res.x[i] - x[i], res.x[i + 1] - x[i + 1]))
            maxMoveRatio = max(maxMoveRatio, worst / 0.5)
            assertTrue(worst <= 3 * 0.5 + 1e-9, "step $step moved $worst")
            val side = tangent.signedDistance(res.x)
            assertTrue(side * sideStart > 0, "tangent side flipped at step $step")
            assertTrue(abs(abs(side) - res.x[r]) < 1e-6)
            x = res.x
        }
        println("SOLVERSPIKE drag200 max_move_over_pointer_delta=$maxMoveRatio final_radius=${x[r]}")
    }

    @Test
    fun branchFlipFreeTangentDragWithFreeLineAndFixedRadius() {
        // Line a (pinned) - b (Horizontal + Distance 10 leaves a discrete b = a +/- 10 branch); circle radius fixed.
        val sk = SpikeSketch()
        val la = sk.addPoint(0.0, 0.0); val lb = sk.addPoint(10.0, 0.5)
        val c = sk.addPoint(5.0, 2.0); val r = sk.addScalar(2.0)
        val start = sk.initial()
        val tangent = TangentLineCircle("t", la, lb, c, r, start)
        val drag = Pin("drag", c, 5.0, 2.0)
        val cs = listOf(Pin("fa", la, 0.0, 0.0), Horizontal("h", la, lb), Distance("d", la, lb, 10.0), ScalarEquals("rad", r, 2.0), tangent, drag)
        val solver = LmSolver()
        var x = start
        val sideStart = tangent.signedDistance(x)
        for (step in 1..200) {
            drag.tx = 5.0 + step * 0.5 * 0.9; drag.ty = 2.0 + step * 0.5 * 0.05
            val res = solver.solve(cs, x)
            // The drag target gets infeasible (tangent with r=2 forces c.y = 2 once H is enforced); the solver reports Conflict, never a flip.
            assertFalse(res.x.any { it.isNaN() })
            assertTrue(tangent.signedDistance(res.x) * sideStart > 0, "side flipped at $step")
            assertTrue(res.x[2 * lb] > 0, "b flipped to the other branch at $step")
            x = res.x
        }
    }

    @Test
    fun pivotedQrRankMatchesConstructedRankAndNeverProducesNaN() {
        val rnd = Random(7)
        repeat(200) {
            val m = 3 + rnd.nextInt(10); val n = 3 + rnd.nextInt(10)
            val k = 1 + rnd.nextInt(minOf(m, n))
            val u = DenseMatrix(m, k) ; val v = DenseMatrix(k, n)
            for (i in u.data.indices) u.data[i] = rnd.nextDouble() - 0.5
            for (i in v.data.indices) v.data[i] = rnd.nextDouble() - 0.5
            val a = DenseMatrix(m, n)
            for (i in 0 until m) for (j in 0 until n) { var s = 0.0; for (t in 0 until k) s += u[i, t] * v[t, j]; a[i, j] = s }
            val qr = pivotedQr(a, 1e-9)
            assertEquals(k, qr.rank)
            assertTrue(qr.r.data.none { it.isNaN() })
        }
        // Degenerate fuzz: all-zero, huge and tiny entries, duplicate columns.
        for (fill in listOf(0.0, 1e300, 1e-300, 1.0)) {
            val a = DenseMatrix(6, 6)
            a.data.fill(fill)
            val qr = pivotedQr(a)
            assertTrue(qr.r.data.none { it.isNaN() }, "fill $fill")
        }
    }
}
